package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool

internal class AndroidDownloadScheduler(val context: Context) {
    val store = AndroidDownloadStore(File(context.filesDir, "download-transfers"))
    val directory = File(context.filesDir, "downloads")
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(item: DownloadItem): AndroidDownloadTransfer {
        val previous = store.get(item.fileName)
        val transfer = store.begin(item)
        val existing = previous?.generation == transfer.generation
        try {
            // Smart Downloads are initiated by an automatic queue, not direct user
            // interaction. UIDT can be accepted but indefinitely deferred by Android;
            // foreground WorkManager is appropriate for this persistent work.
            if (!existing || !transfer.scheduledWithWorkManager) {
                if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
                scheduleWithWorkManager(transfer, existing = false)
            }
        } catch (error: Exception) {
            fail(transfer, error)
        }
        return store.get(item.fileName) ?: transfer
    }

    private fun scheduleWithWorkManager(transfer: AndroidDownloadTransfer, existing: Boolean) {
        // Persist which scheduler owns this download: restore/cancel must not
        // mistake a WorkManager transfer for an absent JobScheduler job.
        store.update(transfer.item.fileName, transfer.generation) {
            it.copy(scheduledWithWorkManager = true)
        }
        val work = OneTimeWorkRequestBuilder<DownloadsTransferWorker>()
            .setInputData(workDataOf(FILE_NAME to transfer.item.fileName, GENERATION to transfer.generation))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                // Android may classify the Tailscale VPN as metered even over Wi-Fi.
                // Always schedule on CONNECTED and enforce Wi-Fi/SSID rules ourselves
                // immediately before and during byte transfer.
                NetworkType.CONNECTED
            ).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val operation = WorkManager.getInstance(context).enqueueUniqueWork(
            workName(transfer), if (existing) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, work,
        )
        operation.result.addListener({
            try {
                operation.result.get()
            } catch (error: Exception) {
                fail(transfer, error)
                notifyCurrent(transfer.item.fileName)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @RequiresApi(34)
    internal fun buildJob(transfer: AndroidDownloadTransfer): JobInfo =
        JobInfo.Builder(transfer.jobId, ComponentName(context, DownloadsTransferJobService::class.java))
            .setUserInitiated(true)
            .apply {
                OfflineNetworkSettings.ensureLoaded()
                if (OfflineNetworkSettings.wifiOnly) {
                    setRequiredNetwork(NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build())
                } else {
                    setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                }
            }
            .setPersisted(true)
            .setEstimatedNetworkBytes(transfer.item.totalBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString(FILE_NAME, transfer.item.fileName)
                putString(GENERATION, transfer.generation)
            })
            .build()

    @RequiresApi(34)
    private fun jobScheduler(): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)

    fun rescheduleActiveForNetworkChange() {
        store.transfers.value.values.filter { it.item.status == DownloadStatus.Downloading }.forEach { transfer ->
            cancelScheduled(transfer)
            scheduleWithWorkManager(transfer, existing = false)
        }
    }

    fun restore(item: DownloadItem): DownloadItem {
        var transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, errorMessage = null)
            } else item
        if (transfer.item.status == DownloadStatus.Downloading && !transfer.scheduledWithWorkManager) {
            // Upgrade existing UIDT jobs on the same device without discarding
            // queued downloads or any partial file already received.
            if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
            runCatching { scheduleWithWorkManager(transfer, existing = false) }
                .onFailure { fail(transfer, it as? Exception ?: IOException(it.message)) }
            transfer = store.get(item.fileName) ?: transfer
        }
        return transfer.item
    }

    fun pause(fileName: String) {
        val transfer = store.get(fileName) ?: return
        if (transfer.item.status != DownloadStatus.Downloading) return
        val paused = store.update(fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused, errorMessage = null))
        }
        cancelScheduled(transfer)
        paused?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    fun remove(fileName: String) {
        val transfer = store.get(fileName)
        store.remove(fileName)
        transfer?.let(::cancelScheduled)
        transfer?.let { DownloadsLiveStatusPlatform.removeNotification(it.item.id) }
        cleanupScope.launch {
            lock(fileName).withLock {
                if (store.get(fileName) == null) {
                    File(directory, "$fileName.part").delete()
                    DownloadSubtitleStorage(File(directory, fileName).toURI().toString()).remove()
                }
            }
        }
    }

    private fun cancelScheduled(transfer: AndroidDownloadTransfer) {
        if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
        // Always cancel possible WorkManager fallback, even on Android 14+.
        WorkManager.getInstance(context).cancelUniqueWork(workName(transfer))
    }

    suspend fun execute(
        transfer: AndroidDownloadTransfer,
        network: Network? = null,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = lock(transfer.item.fileName).withLock {
        val fileName = transfer.item.fileName
        if (!isActive(transfer)) return@withLock false
        val destination = File(directory, fileName)
        val client = if (network != null) {
            downloadHttpClient.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .connectionPool(ConnectionPool())
                .build()
        } else downloadHttpClient
        try {
            OfflineWifiPlatform.requireAllowed(context, network)
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            var lastProgressAt = 0L
            var lastPublishedBytes = -1L
            val partial = if (destination.isFile) destination else transferAndroidDownload(
                item = transfer.item,
                directory = directory,
                validator = transfer.validator,
                client = client,
                onHeaders = { total, validator ->
                    if (total != null && total > OfflineQualityPolicy.maxBytes(transfer.item.durationMinutes, transfer.item.isEpisode)) {
                        throw IOException("File exceeds the duration-based download size limit")
                    }
                    updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
                },
                networkCheck = { OfflineWifiPlatform.requireAllowed(context, network) },
                onProgress = { bytes, total ->
                    if (bytes > OfflineQualityPolicy.maxBytes(transfer.item.durationMinutes, transfer.item.isEpisode)) {
                        throw IOException("Download exceeded the duration-based size limit")
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (lastPublishedBytes < 0L || (lastPublishedBytes == 0L && bytes > 0L) ||
                        now - lastProgressAt >= 500L || bytes == total) {
                        lastProgressAt = now
                        lastPublishedBytes = bytes
                        updateActive(transfer) {
                            it.copy(item = it.item.copy(downloadedBytes = bytes, totalBytes = total))
                        }?.let(onProgress)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            // Video is the priority. Optional subtitle servers must not delay
            // the start of the transfer or indefinitely block the queue.
            try {
                withTimeoutOrNull(20_000L) {
                    DownloadSubtitles.prepare(transfer.item, destination.toURI().toString())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Embedded subtitles and any already saved tracks remain usable.
            }
            currentCoroutineContext().ensureActive()
            updateActive(transfer) { current ->
                if (partial != destination && !partial.renameTo(destination)) {
                    throw IOException("Could not finalize the downloaded file")
                }
                val bytes = destination.length()
                current.copy(item = current.item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = destination.toURI().toString(),
                    downloadedBytes = bytes,
                    totalBytes = bytes,
                    errorMessage = null,
                ))
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            val restricted = error is OfflineWifiRestricted
            val retry = restricted || shouldRetryAndroidDownload(error, transfer.retryCount)
            if (retry) {
                if (!restricted) updateActive(transfer) { it.copy(retryCount = it.retryCount + 1) }
            } else fail(transfer, error)
            retry
        } finally {
            if (network != null) withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }

    fun isActive(transfer: AndroidDownloadTransfer): Boolean = store.get(transfer.item.fileName)?.let {
        it.generation == transfer.generation && it.item.status == DownloadStatus.Downloading
    } == true

    fun fail(transfer: AndroidDownloadTransfer, error: Exception) {
        updateActive(transfer) {
            it.copy(item = it.item.copy(status = DownloadStatus.Failed, errorMessage = error.message ?: "Download failed"))
        }
    }

    fun notifyCurrent(fileName: String) {
        store.get(fileName)?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    private fun updateActive(
        transfer: AndroidDownloadTransfer,
        update: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = store.update(transfer.item.fileName, transfer.generation) {
        if (it.item.status == DownloadStatus.Downloading) {
            update(it).let { changed -> changed.copy(item = changed.item.copy(updatedAtEpochMs = System.currentTimeMillis())) }
        } else it
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        const val FILE_NAME = "download_file_name"
        const val GENERATION = "download_generation"
        const val JOB_NAMESPACE = "nuvio-downloads"
    }
}

internal fun shouldRetryAndroidDownload(error: Exception, retries: Int): Boolean =
    retries < 4 && when (error) {
        is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is IOException -> true
        else -> false
    }
