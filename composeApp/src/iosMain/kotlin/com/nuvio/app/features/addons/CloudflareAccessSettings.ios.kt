package com.nuvio.app.features.addons

internal actual object CloudflareAccessSettings {
    actual val supported = false
    actual fun isConfigured() = false
    actual fun save(clientId: String, clientSecret: String) = Unit
    actual fun clear() = Unit
}
