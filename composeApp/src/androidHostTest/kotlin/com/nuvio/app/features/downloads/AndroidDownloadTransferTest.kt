package com.nuvio.app.features.downloads

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBehaviorHints

class AndroidDownloadTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun downloadsSuccessiveBoundedRangesAndPreservesValidator(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-4/11")
                .setHeader("ETag", "\"version-1\"").setBody("hello"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-9/11").setBody(" worl"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 10-10/11").setBody("d"))
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), temporary.newFolder(),
                null, onHeaders = { total, _ -> assertEquals(11L, total) }, onProgress = { _, _ -> }, chunkBytes = 5)
            assertEquals("hello world", output.readText())
            assertEquals("bytes=0-4", server.takeRequest().getHeader("Range"))
            val second = server.takeRequest()
            assertEquals("bytes=5-9", second.getHeader("Range"))
            assertEquals("\"version-1\"", second.getHeader("If-Range"))
            val third = server.takeRequest()
            assertEquals("bytes=10-14", third.getHeader("Range"))
            assertEquals("\"version-1\"", third.getHeader("If-Range"))
        }
    }

    @Test
    fun rangeThatIgnoresEndStillUsesOnlyOneChunk(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-10/11").setBody("hello world"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11").setBody(" world"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 10-10/11").setBody("d"))
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), temporary.newFolder(),
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> }, chunkBytes = 5)
            assertEquals("hello world", output.readText())
            assertEquals("bytes=0-4", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=5-9", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=10-14", server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun downloadProviderPriorityPrecedesSize() {
        fun source(name: String, amount: Long, url: String) = StreamItem(addonName = name, addonId = name,
            url = url, behaviorHints = StreamBehaviorHints(videoSize = amount))
        val plex = source("Local Plex Library", 900, "https://example.com/plex")
        val usenet = source("Usenet Ultimate", 800, "https://example.com/usenet")
        val torrent = source("Nuvio TorrServer", 100, "https://example.com/torrent")
        val other = source("Other", 50, "https://example.com/other")
        assertEquals(listOf(plex, usenet, other, torrent), listOf(torrent, other, usenet, plex).sortedWith(offlineSourceComparator()))
        assertEquals(usenet, listOf(torrent, usenet).minWithOrNull(offlineSourceComparator()))
        assertEquals(torrent, listOf(torrent).minWithOrNull(offlineSourceComparator()))
    }

    @Test
    fun metadataSizeSupportsTextAndStructuredBytes() {
        val stream = StreamItem(name = "1080p", description = "WEB-DL • 700 MB", addonName = "Test", addonId = "test")
        assertEquals(700_000_000L, reportedDownloadSize(stream))
        assertEquals(1_073_741_824L, reportedDownloadSize(stream.copy(description = "1 GiB")))
        assertEquals(500L, reportedDownloadSize(stream.copy(behaviorHints = StreamBehaviorHints(videoSize = 500L))))
        assertNull(reportedDownloadSize(stream.copy(description = "700 MB / 3 GB")))
    }

    @Test
    fun oversizedSourcesAreExcludedWithoutRetryingTheirTransfer() {
        val oversized = StreamItem(url = "https://example.com/video?token=1", addonName = "Test", addonId = "test",
            behaviorHints = StreamBehaviorHints(filename = "large.mkv"))
        val alternate = oversized.copy(url = "https://example.com/small", behaviorHints = StreamBehaviorHints(filename = "small.mkv"))
        val current = downloadItem().copy(smartDownload = true, sourceUrl = oversized.playableDirectUrl!!,
            sourceCandidateKey = oversized.downloadCandidateKey())
        val failure = DownloadSizeLimitException(2_000_000_000, 1_000_000_000)
        assertFalse(shouldRetryAndroidDownload(failure, 0))
        assertTrue(current.shouldTrySmallerSource(failure.message!!))
        val rejected = current.rejectOversizedSource()
        assertTrue(rejected.hasRejectedSizeSource(oversized.copy(url = "https://example.com/video?token=2")))
        assertFalse(rejected.hasRejectedSizeSource(alternate))
        assertFalse(rejected.copy(sizeFallbackAttempts = MAX_SIZE_FALLBACKS).shouldTrySmallerSource(failure.message!!))
    }

    @Test
    fun expiredSmartLinksRefreshOnlyOnce() {
        val item = downloadItem().copy(smartDownload = true)
        assertTrue(item.shouldRefreshSmartSource("Download failed: HTTP 410"))
        assertFalse(item.copy(sourceRefreshAttempts = 1).shouldRefreshSmartSource("Download failed: HTTP 410"))
        assertFalse(item.copy(smartDownload = false).shouldRefreshSmartSource("Download failed: HTTP 410"))
        assertFalse(item.shouldRefreshSmartSource("Download failed: HTTP 503"))
    }

    @Test
    fun queueOrderUsesShowAgeAndEpisodeOrder() {
        val first = downloadItem(id = "a").copy(parentMetaId = "first", seasonNumber = 1, episodeNumber = 1,
            status = DownloadStatus.Completed, createdAtEpochMs = 1)
        val third = first.copy(id = "c", episodeNumber = 3, status = DownloadStatus.Queued, createdAtEpochMs = 3)
        val second = first.copy(id = "b", episodeNumber = 2, status = DownloadStatus.Queued, createdAtEpochMs = 4)
        val other = third.copy(id = "d", parentMetaId = "other", createdAtEpochMs = 2)
        val items = listOf(other, third, first, second)
        assertEquals(listOf("b", "c", "d"), items.filter { it.status == DownloadStatus.Queued }
            .sortedWith(downloadQueueComparator(items)).map { it.id })
    }

    @Test
    fun reportsIntermediateProgressBeforeCompletion(): Unit = runBlocking {
        MockWebServer().use { server ->
            val body = "x".repeat(128 * 1024)
            server.enqueue(MockResponse().setBody(body).throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS))
            val progress = mutableListOf<Long>()
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), temporary.newFolder(),
                null, onHeaders = { total, _ -> assertEquals(body.length.toLong(), total) },
                onProgress = { bytes, _ -> progress.add(bytes) })
            assertEquals(body.length.toLong(), output.length())
            assertTrue(progress.any { it > 0 && it < output.length() })
            assertEquals(output.length(), progress.last())
        }
    }

    @Test
    fun resumesPartialFileWithRangeAndValidator(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11")
                .setHeader("ETag", "\"version-1\"").setBody(" world"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("hello")

            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("hello world", output.readText())
            val request = server.takeRequest()
            assertEquals("bytes=5-${5 + DOWNLOAD_RANGE_BYTES - 1}", request.getHeader("Range"))
            assertEquals("\"version-1\"", request.getHeader("If-Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertEquals("Bearer test", request.getHeader("Authorization"))
        }
    }

    @Test
    fun ignoredRangeRestartsInsteadOfAppending(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("new file"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("old prefix")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("new file", output.readText())
        }
    }

    @Test
    fun unsatisfiedRangeRetriesFromZero(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setBody("replacement"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("old prefix")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("replacement", output.readText())
            assertEquals("bytes=10-${10 + DOWNLOAD_RANGE_BYTES - 1}", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun mismatchedRangeDoesNotCorruptThePartialFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/5").setBody("bad"))
            val directory = temporary.newFolder()
            val partial = File(directory, "video.mkv.part").apply { writeText("hello") }
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertEquals("hello", partial.readText())
        }
    }

    @Test
    fun truncatedResponseNeverBecomesCompletedFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val directory = temporary.newFolder()
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertFalse(File(directory, "video.mkv").exists())
            assertTrue(File(directory, "video.mkv.part").length() < 10)
        }
    }

    @Test
    fun cancellationClosesBlockedSocketPromptlyAndKeepsPartialBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").throttleBody(1, 10, TimeUnit.SECONDS))
            val directory = temporary.newFolder()
            val task = async(Dispatchers.Default) {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            withTimeout(5_000) {
                while (File(directory, "video.mkv.part").length() == 0L) delay(10)
            }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertEquals(1L, File(directory, "video.mkv.part").length())
            assertFalse(File(directory, "video.mkv").exists())
        }
    }

    @Test
    fun retryPolicyDistinguishesTransientAndPermanentFailures() {
        assertTrue(shouldRetryAndroidDownload(IOException("connection lost"), 0))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(503), 0))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(429), 0))
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(403), 0))
        assertFalse(shouldRetryAndroidDownload(IOException("connection lost"), 4))
    }
}

internal fun downloadItem(url: String = "https://example.com/video.mkv", id: String = "test-download") = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "test-movie",
    parentMetaType = "movie",
    videoId = "test-movie",
    title = "Download test",
    streamTitle = "Test video",
    providerName = "Test",
    sourceUrl = url,
    sourceHeaders = mapOf("Authorization" to "Bearer test"),
    fileName = "video.mkv",
    status = DownloadStatus.Downloading,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
)
