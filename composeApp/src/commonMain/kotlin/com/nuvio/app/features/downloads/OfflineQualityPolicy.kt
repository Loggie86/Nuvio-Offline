package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage

/**
 * Offline-only source restrictions. These never affect ordinary playback.
 * Resolution is inferred from provider metadata; the download pipeline does
 * not transcode a 4K file into a smaller one.
 */
internal object OfflineQualityPolicy {
    var maxHeight: Int = 1080
        private set
    var maxSizeGb: Int = 4
        private set

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        maxHeight = StreamBadgeSettingsStorage.loadOfflineMaxHeight()?.takeIf { it == 720 || it == 1080 } ?: 1080
        maxSizeGb = StreamBadgeSettingsStorage.loadOfflineMaxSizeGb()?.takeIf { it in listOf(2, 4, 8, 16, 0) } ?: 4
        loaded = true
    }

    fun setMaxHeight(value: Int) {
        ensureLoaded()
        require(value == 720 || value == 1080)
        maxHeight = value
        StreamBadgeSettingsStorage.saveOfflineMaxHeight(value)
    }

    fun setMaxSizeGb(value: Int) {
        ensureLoaded()
        require(value in listOf(0, 2, 4, 8, 16))
        maxSizeGb = value
        StreamBadgeSettingsStorage.saveOfflineMaxSizeGb(value)
    }

    fun rejectionReason(stream: StreamItem): String? {
        ensureLoaded()
        val label = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ").lowercase()
        if (Regex("""\b(remux|blu[ -]?ray\s*remux|2160p|4k|uhd|4320p|8k)\b""").containsMatchIn(label)) {
            return "4K, UHD and remux files are excluded from offline downloads."
        }
        val detected = when {
            Regex("""\b(1080p|1920x1080|fhd|full[ -]?hd)\b""").containsMatchIn(label) -> 1080
            Regex("""\b(720p|1280x720|hd)\b""").containsMatchIn(label) -> 720
            Regex("""\b(480p|576p|sd)\b""").containsMatchIn(label) -> 480
            else -> null
        }
        if (detected == null) return "Download quality is unknown. Choose a stream labelled 720p or 1080p."
        if (detected > maxHeight) return "Only ${maxHeight}p or lower is permitted for offline downloads."
        val size = stream.behaviorHints.videoSize
        if (maxSizeGb != 0 && size != null && size > maxSizeGb.toLong() * 1024 * 1024 * 1024) {
            return "This file exceeds your ${maxSizeGb} GB download size limit."
        }
        return null
    }
}
