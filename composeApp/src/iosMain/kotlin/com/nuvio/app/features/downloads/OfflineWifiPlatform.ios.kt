package com.nuvio.app.features.downloads

internal actual object OfflineWifiPlatform {
    actual fun currentSsid(): String? = null
    actual fun onRulesChanged() = Unit
}
