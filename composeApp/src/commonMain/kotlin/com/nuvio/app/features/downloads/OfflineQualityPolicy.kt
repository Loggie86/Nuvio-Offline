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
        val raw = stream.clientResolve?.stream?.raw
        val label = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename,
            raw?.filename, raw?.torrentName, raw?.parsed?.resolution, raw?.parsed?.quality,
            stream.clientResolve?.filename, stream.debridCacheStatus?.cachedName).joinToString(" ").lowercase().replace('_', ' ')
        if (Regex("""\b(remux|blu[ -]?ray\s*remux|2160[pi]?|3840\s*[x×]\s*2160|4k|uhd|4320[pi]?|8k)\b""").containsMatchIn(label)) {
            return "4K, UHD and remux files are excluded from offline downloads."
        }
        val detected = when {
            Regex("""\b(1080\s*[pi]|1920\s*[x×]\s*1080|fhd|full[ -]?hd)\b""").containsMatchIn(label) || raw?.parsed?.resolution?.trim() == "1080" -> 1080
            Regex("""\b(720\s*[pi]|1280\s*[x×]\s*720|hd)\b""").containsMatchIn(label) || raw?.parsed?.resolution?.trim() == "720" -> 720
            Regex("""\b(480\s*[pi]|576\s*[pi]|sd)\b""").containsMatchIn(label) -> 480
            else -> null
        }
        if (detected == null) return "Download quality is unknown. Choose a stream labelled 720p or 1080p."
        if (detected > maxHeight) return "Only ${maxHeight}p or lower is permitted for offline downloads."
        val size = reportedDownloadSize(stream)
        if (size != null && size > maxBytes(durationMinutes, isEpisode)) {
            return "This file exceeds your duration-adjusted download size limit."
        }
        return null
    }
}

/** Prefer structured file sizes; addons often only display a size in their labels. */
internal fun reportedDownloadSize(stream: StreamItem): Long? {
    val raw = stream.clientResolve?.stream?.raw
    listOfNotNull(stream.behaviorHints.videoSize, raw?.size, stream.debridCacheStatus?.cachedSize)
        .firstOrNull { it > 0L }?.let { return it }
    val text = listOfNotNull(stream.name, stream.title, stream.description).distinct().joinToString(" ")
    val values = Regex("""(?i)\b(\d+(?:[.,]\d+)?)\s*(GiB|MiB|GB|MB)\b""").findAll(text).mapNotNull { match ->
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "gib" -> 1_073_741_824L
            "mib" -> 1_048_576L
            "gb" -> 1_000_000_000L
            else -> 1_000_000L
        }
        (number * multiplier).toLong().takeIf { it > 0 }
    }.distinct().toList()
    return values.singleOrNull()
}
