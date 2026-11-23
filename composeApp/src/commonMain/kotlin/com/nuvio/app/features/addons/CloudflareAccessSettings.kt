package com.nuvio.app.features.addons

internal const val CLOUDFLARE_ACCESS_HOST = "nuvio.thelogans.com.au"

internal expect object CloudflareAccessSettings {
    val supported: Boolean
    fun isConfigured(): Boolean
    fun save(clientId: String, clientSecret: String)
    fun clear()
    fun routingEnabled(): Boolean
    fun setRoutingEnabled(enabled: Boolean)
    fun homeSsid(): String
    fun setHomeSsid(ssid: String)
    fun routeDescription(): String
    suspend fun testConnection(): String
}
