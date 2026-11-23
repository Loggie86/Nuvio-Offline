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
    private fun wifiCapabilities(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.getNetworkCapabilities(manager.activeNetwork)
        if (active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return active
        // Android reports TRANSPORT_VPN as the active network with Tailscale.
        // Find the validated Wi-Fi underneath rather than assuming mobile data.
        if (active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return null
        return manager.allNetworks.asSequence()
            .mapNotNull(manager::getNetworkCapabilities)
            .firstOrNull { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
    }

    actual fun restrictionLabel(): String? {
        val context = appContext ?: return null
        OfflineNetworkSettings.ensureLoaded()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager.activeNetwork == null) return "Waiting for internet connection"
        val wifi = wifiCapabilities(context) != null
        if (!wifi) return if (OfflineNetworkSettings.wifiOnly) "Waiting for Wi-Fi" else null
        if (OfflineNetworkSettings.excludedSsids.isNotEmpty()) {
            val ssid = connectedSsid(context) ?: return "Wi-Fi name unavailable — check Location access"
            if (OfflineNetworkSettings.excludedSsids.any { it.equals(ssid, ignoreCase = true) }) {
                return "Blocked Wi-Fi: $ssid"
            }
        }
        return null
    }

    actual fun onRulesChanged() {
        appContext?.let { DownloadsPlatformDownloader.refreshNetworkRules() }
    }

    fun allowed(context: Context, network: Network? = null): Boolean {
        OfflineNetworkSettings.ensureLoaded()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.getNetworkCapabilities(network ?: manager.activeNetwork)
        val wifi = wifiCapabilities(context) != null
        // No active network, or cellular-only with Wi-Fi-only enabled.
        if (active == null || (OfflineNetworkSettings.wifiOnly && !wifi)) return false
        if (OfflineNetworkSettings.excludedSsids.isNotEmpty() && wifi) {
            val name = connectedSsid(context) ?: return false
            if (OfflineNetworkSettings.excludedSsids.any { it.equals(name, ignoreCase = true) }) return false
        }
        // A VPN may sit over Wi-Fi; its network is used for the actual HTTP request.
        return true
    }

    fun requireAllowed(context: Context, network: Network? = null) {
        if (!allowed(context, network)) {
            throw OfflineWifiRestricted(restrictionLabel() ?: "Waiting for an allowed network")
        }
    }

    private fun connectedSsid(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val caps = wifiCapabilities(context) ?: return null
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo else null
        val raw = info?.ssid?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
            ?: @Suppress("DEPRECATION") wifiManager?.connectionInfo?.ssid
        return raw?.trim()?.trim('"')?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
    }
}
