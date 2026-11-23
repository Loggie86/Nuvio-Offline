package com.nuvio.app.features.addons

internal actual object CloudflareAccessSettings {
    actual val supported = false
    actual fun isConfigured() = false
    actual fun save(clientId: String, clientSecret: String) = Unit
    actual fun clear() = Unit
    actual fun routingEnabled() = false
    actual fun setRoutingEnabled(enabled: Boolean) = Unit
    actual fun homeSsid() = ""
    actual fun setHomeSsid(ssid: String) = Unit
    actual fun routeDescription() = "Not supported"
    actual suspend fun testConnection() = "Not supported"
}
