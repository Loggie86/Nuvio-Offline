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
    /** 75, 150, 250 = 0.75, 1.50, 2.50 GiB per video hour. */
    var sizePreset: Int = 150
        private set

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        maxHeight = StreamBadgeSettingsStorage.loadOfflineMaxHeight()?.takeIf { it == 720 || it == 1080 } ?: 1080
        sizePreset = StreamBadgeSettingsStorage.loadOfflineMaxSizeGb()?.takeIf { it in listOf(75, 150, 250) } ?: 150
        loaded = true
    }

    fun setMaxHeight(value: Int) {
        ensureLoaded()
        require(value == 720 || value == 1080)
        maxHeight = value
        StreamBadgeSettingsStorage.saveOfflineMaxHeight(value)
    }

    fun setSizePreset(value: Int) {
        ensureLoaded()
        require(value in listOf(75, 150, 250))
        sizePreset = value
        StreamBadgeSettingsStorage.saveOfflineMaxSizeGb(value)
    }

    /** Uses title runtime where known; conservative fallbacks are 45m TV and 120m movies. */
    fun maxBytes(durationMinutes: Int?, isEpisode: Boolean): Long {
        ensureLoaded()
        val minutes = durationMinutes?.takeIf { it in 5..360 } ?: if (isEpisode) 45 else 120
        return (sizePreset.toLong() * 1024 * 1024 * 1024 * minutes / 100L / 60L)
    }

    fun rejectionReason(stream: StreamItem, durationMinutes: Int? = null, isEpisode: Boolean = false): String? {
        ensureLoaded()
        val label = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename).joinToString(" ").lowercase()
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
        if (size != null && size > maxBytes(durationMinutes, isEpisode)) {
            return "This file exceeds your duration-adjusted download size limit."
        }
        return null
    }
}
