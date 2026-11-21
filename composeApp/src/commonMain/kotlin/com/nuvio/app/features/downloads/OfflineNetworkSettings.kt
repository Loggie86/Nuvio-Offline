package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBadgeSettingsStorage

/** Download-only network restrictions; independent of online playback. */
internal object OfflineNetworkSettings {
    var wifiOnly: Boolean = true
        private set
    var excludedSsids: Set<String> = emptySet()
        private set
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        wifiOnly = StreamBadgeSettingsStorage.loadOfflineWifiOnly() ?: true
        excludedSsids = StreamBadgeSettingsStorage.loadOfflineExcludedSsids()
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        loaded = true
    }

    fun setWifiOnly(value: Boolean) {
        ensureLoaded()
        wifiOnly = value
        StreamBadgeSettingsStorage.saveOfflineWifiOnly(value)
        OfflineWifiPlatform.onRulesChanged()
    }

    fun addExcludedSsid(ssid: String) {
        ensureLoaded()
        val name = ssid.trim().trim('"')
        if (name.isEmpty() || name.equals("<unknown ssid>", ignoreCase = true)) return
        excludedSsids = excludedSsids + name
        persist()
    }

    fun removeExcludedSsid(ssid: String) {
        ensureLoaded()
        excludedSsids = excludedSsids - ssid
        persist()
    }

    private fun persist() {
        StreamBadgeSettingsStorage.saveOfflineExcludedSsids(excludedSsids.sorted().joinToString("\n"))
        OfflineWifiPlatform.onRulesChanged()
    }
}

internal expect object OfflineWifiPlatform {
    fun currentSsid(): String?
    fun restrictionLabel(): String?
    fun onRulesChanged()
}
