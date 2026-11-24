package com.nuvio.app.features.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import com.nuvio.app.features.addons.CloudflareAccessCacheInterceptor
import com.nuvio.app.features.addons.CloudflareAccessInterceptor

internal val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .addInterceptor(CloudflareAccessCacheInterceptor())
    .addNetworkInterceptor(CloudflareAccessInterceptor())
    .build()

internal class DownloadHttpException(val statusCode: Int) : IOException("Download failed: HTTP $statusCode")
internal const val DOWNLOAD_RANGE_BYTES = 8L * 1024 * 1024

internal suspend fun transferAndroidDownload(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    networkCheck: () -> Unit = {},
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    chunkBytes: Long = DOWNLOAD_RANGE_BYTES,
): File = coroutineScope {
    val activeCall = AtomicReference<Call?>()
    val transfer = async(Dispatchers.IO) {
        try {
            require(File(item.fileName).name == item.fileName && item.fileName.isNotBlank())
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }
            val partial = File(directory, "${item.fileName}.part")
            var offset = partial.takeIf(File::isFile)?.length() ?: 0L
            var restarted = false
            var useRanges = true
            var currentValidator = validator
            var expectedTotal = item.totalBytes?.takeIf { offset > 0L && it >= offset }
            require(chunkBytes > 0L)

            while (true) {
                ensureActive()
                networkCheck()
                val request = Request.Builder().url(item.sourceUrl).apply {
                    item.sourceHeaders.forEach { (key, value) ->
                        if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                            !key.equals("Accept-Encoding", true)) header(key, value)
                    }
                    header("Accept-Encoding", "identity")
                    if (useRanges) {
                        val end = offset + minOf(chunkBytes - 1, Long.MAX_VALUE - offset)
                        header("Range", "bytes=$offset-$end")
                        if (offset > 0L) currentValidator?.let { header("If-Range", it) }
                    }
                }.build()
                val call = client.newCall(request)
                activeCall.set(call)
                ensureActive()
                call.execute().use { response ->
                    if (response.code == 416 && useRanges && !restarted) {
                        val actualTotal = response.header("Content-Range")?.let { Regex("bytes \\*/(\\d+)").matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull() }
                        if (offset > 0L && actualTotal == offset && expectedTotal == offset) return@async partial
                        offset = 0L
                        restarted = true
                        useRanges = false
                        currentValidator = null
                        expectedTotal = null
                        return@use
                    }
                    if (!response.isSuccessful) throw DownloadHttpException(response.code)
                    val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
                    val resumed = response.code == 206
                    if (resumed && !useRanges) throw IOException("Unexpected partial response to a full download request")
                    if (resumed && (range == null || range.start != offset)) {
                        throw IOException("Server returned an invalid download range")
                    }
                    val startingBytes = if (resumed) offset else 0L
                    val body = response.body ?: throw IOException("Download response is empty")
                    val contentLength = body.contentLength().takeIf { it >= 0L }
                    val totalBytes = if (resumed) range?.total ?: expectedTotal else contentLength
                    val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    if (resumed && offset > 0L && expectedTotal != null && range?.total != null && expectedTotal != range.total) {
                        throw IOException("Download size changed during resume")
                    }
                    if (resumed && offset > 0L && currentValidator != null && responseValidator != null && currentValidator != responseValidator) {
                        throw IOException("Download source changed during resume")
                    }
                    currentValidator = responseValidator ?: currentValidator.takeIf { resumed }
                    expectedTotal = totalBytes
                    val expectedEnd = if (resumed) checkNotNull(range).end + 1 else totalBytes
                    if (resumed && contentLength != null && contentLength != expectedEnd!! - startingBytes) {
                        throw IOException("Server returned an inconsistent download range length")
                    }
                    // Some servers honor the range start but ignore its end.
                    // Consume only a bounded portion on a 206, then close and resume.
                    val chunkEnd = if (resumed && useRanges) minOf(checkNotNull(expectedEnd), startingBytes + minOf(chunkBytes, Long.MAX_VALUE - startingBytes)) else expectedEnd
                    onHeaders(totalBytes, currentValidator)
                    var downloaded = startingBytes
                    onProgress(downloaded, totalBytes)
                    body.byteStream().use { input ->
                        FileOutputStream(partial, resumed && offset > 0L).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var lastNetworkCheck = System.nanoTime()
                            while (true) {
                                ensureActive()
                                // Avoid repeated Android connectivity/SSID Binder calls for
                                // every socket read, while still enforcing network rules.
                                val now = System.nanoTime()
                                if (now - lastNetworkCheck >= TimeUnit.MILLISECONDS.toNanos(250)) {
                                    networkCheck()
                                    lastNetworkCheck = now
                                }
                                if (resumed && chunkEnd != null && downloaded == chunkEnd) break
                                val maxRead = if (resumed && chunkEnd != null) minOf(buffer.size.toLong(), chunkEnd - downloaded).toInt() else buffer.size
                                val size = input.read(buffer, 0, maxRead)
                                if (size == -1) break
                                if (expectedEnd != null && size.toLong() > expectedEnd - downloaded) {
                                    throw IOException("Server returned more bytes than requested")
                                }
                                output.write(buffer, 0, size)
                                downloaded += size
                                onProgress(downloaded, totalBytes)
                            }
                            networkCheck()
                            output.fd.sync()
                        }
                    }
                    ensureActive()
                    if (chunkEnd != null && downloaded != chunkEnd) {
                        throw IOException("Download ended before all bytes were received")
                    }
                    // A successful bounded range is not the complete file. Continue
                    // from the exact next byte until the advertised total is reached.
                    if (!resumed || (totalBytes != null && downloaded == totalBytes)) return@async partial
                    offset = downloaded
                }
            }
            @Suppress("UNREACHABLE_CODE")
            partial
        } catch (error: Exception) {
            // Closing a cancelled HTTP call throws IOException from its blocking read.
            ensureActive()
            throw error
        }
    }
    try {
        transfer.await()
    } finally {
        activeCall.get()?.cancel()
    }
}

internal data class DownloadContentRange(val start: Long, val end: Long, val total: Long?)

internal fun parseDownloadContentRange(value: String): DownloadContentRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull()
    if (end < start || end == Long.MAX_VALUE || (total != null && end >= total)) return null
    return DownloadContentRange(start, end, total)
}
