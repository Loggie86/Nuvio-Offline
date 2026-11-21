package com.nuvio.app.features.downloads

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException

internal class OfflineWifiRestricted(message: String) : IOException(message)

internal actual object OfflineWifiPlatform {
    private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    actual fun currentSsid(): String? = appContext?.let(::connectedSsid)
    actual fun onRulesChanged() {
        appContext?.let { DownloadsPlatformDownloader.refreshNetworkRules() }
    }

    fun allowed(context: Context, network: Network? = null): Boolean {
        OfflineNetworkSettings.ensureLoaded()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(network ?: manager.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (OfflineNetworkSettings.wifiOnly && !wifi) return false
        if (wifi && OfflineNetworkSettings.excludedSsids.isNotEmpty()) {
            val name = connectedSsid(context)
            // Fail closed when Android redacts SSID or Location is disabled.
            if (name == null) return false
            if (OfflineNetworkSettings.excludedSsids.any { it.equals(name, ignoreCase = true) }) return false
        }
        return caps != null
    }

    fun requireAllowed(context: Context, network: Network? = null) {
        if (!allowed(context, network)) throw OfflineWifiRestricted("Waiting for an allowed Wi-Fi network")
    }

    private fun connectedSsid(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = if (Build.VERSION.SDK_INT >= 31) (caps.transportInfo as? WifiInfo) else null
        val raw = info?.ssid?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
            ?: @Suppress("DEPRECATION") wifiManager?.connectionInfo?.ssid
        return raw?.trim()?.trim('"')?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
    }
}
