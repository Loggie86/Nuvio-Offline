package com.nuvio.app.features.addons

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl
import com.nuvio.app.features.downloads.OfflineWifiPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal actual object CloudflareAccessSettings {
    actual val supported = true
    private const val alias = "nuvio.cloudflare.access.v1"
    private var preferences: SharedPreferences? = null
    @Volatile private var credentials: AccessCredentials? = null

    @Synchronized fun initialize(context: Context) {
        if (preferences != null) return
        preferences = context.getSharedPreferences("nuvio_cloudflare_access", Context.MODE_PRIVATE)
        credentials = runCatching {
            val parts = preferences?.getString("encrypted", null)?.split('.') ?: return@runCatching null
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val decoded = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8).split('\n', limit = 2)
            require(decoded.size == 2)
            AccessCredentials(decoded[0], decoded[1])
        }.getOrNull()
    }

    actual fun isConfigured() = credentials != null
    internal fun current(): AccessCredentials? = credentials

    actual fun routingEnabled(): Boolean = preferences?.getBoolean("remote_routing", true) ?: true
    actual fun setRoutingEnabled(enabled: Boolean) {
        check(preferences?.edit()?.putBoolean("remote_routing", enabled)?.commit() == true)
    }
    actual fun homeSsid(): String = preferences?.getString("home_ssid", "").orEmpty()
    actual fun setHomeSsid(ssid: String) {
        check(preferences?.edit()?.putString("home_ssid", ssid.trim())?.commit() == true)
    }
    internal fun useRemoteRoute(): Boolean = shouldUseRemoteRoute(routingEnabled(), isConfigured(), homeSsid(), OfflineWifiPlatform.currentSsid())
    actual fun routeDescription(): String = when {
        !routingEnabled() -> "Original addon URLs (routing disabled)"
        !isConfigured() -> "Original addon URLs (no service token)"
        useRemoteRoute() -> "Remote · authenticated Cloudflare gateway"
        else -> "Home Wi-Fi · direct local connection"
    }

    actual suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        if (credentials == null) return@withContext "No saved service token"
        try {
            val request = Request.Builder().url("https://$CLOUDFLARE_ACCESS_HOST/manifest.json").build()
            val client = AddonHttpClientProvider.get().newBuilder().followRedirects(false).followSslRedirects(false)
                .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code in 300..399 || response.code == 401 || response.code == 403 -> "Authentication rejected (HTTP ${response.code}). Check the Service Auth policy."
                    !response.isSuccessful -> "Gateway returned HTTP ${response.code}. Check the tunnel destination."
                    else -> {
                        val manifest = runCatching { Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject }.getOrNull()
                        if (manifest?.containsKey("id") == true && manifest.containsKey("resources")) "✓ Connected · token accepted and addon manifest loaded"
                        else "Unexpected gateway response. Check the tunnel destination."
                    }
                }
            }
        } catch (_: Exception) { "Could not reach the gateway. Check internet connection." }
    }

    @Synchronized actual fun save(clientId: String, clientSecret: String) {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        require(id.isNotEmpty() && secret.isNotEmpty() && (id + secret).all { it.code in 33..126 }) { "Enter a valid client ID and secret" }
        val prefs = checkNotNull(preferences)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal("$id\n$secret".toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString("encrypted", encoded).commit()) { "Unable to save credentials" }
        credentials = AccessCredentials(id, secret)
    }

    @Synchronized actual fun clear() {
        check(preferences?.edit()?.remove("encrypted")?.commit() == true) { "Unable to remove credentials" }
        credentials = null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}

internal class AccessCredentials(val id: String, val secret: String)
private class AccessRequestTag
private const val CLIENT_ID = "CF-Access-Client-Id"
private const val CLIENT_SECRET = "CF-Access-Client-Secret"

internal fun Request.isCloudflareAccessOrigin(): Boolean =
    url.isHttps && url.host == CLOUDFLARE_ACCESS_HOST && url.port == 443

internal fun shouldUseRemoteRoute(enabled: Boolean, configured: Boolean, homeSsid: String, currentSsid: String?): Boolean =
    enabled && configured && (homeSsid.isBlank() || currentSsid != homeSsid)

/** Remap only known services on this user's server. Do not change stored/synced addon URLs. */
internal fun remapAddonUrl(url: HttpUrl): HttpUrl {
    if (url.host !in setOf("192.168.4.145", "100.103.120.108", "mint-media.tail6b0186.ts.net")) return url
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return url
    val prefix = when (url.port) {
        3232 -> "" // The existing tunnel root is AIO Metadata.
        7780 -> "/plex"
        1337 -> "/usenet"
        18100 -> "/torrent"
        7795 -> "/watchly"
        else -> return url
    }
    return url.newBuilder().scheme("https").host(CLOUDFLARE_ACCESS_HOST).port(443)
        .encodedPath(prefix + url.encodedPath).build()
}

/** Apply per network hop, including redirects. Never forward credentials to another origin. */
internal fun cloudflareAuthenticatedRequest(request: Request, credentials: AccessCredentials?): Request {
    if (request.isCloudflareAccessOrigin() && credentials != null) {
        return request.newBuilder().header(CLIENT_ID, credentials.id).header(CLIENT_SECRET, credentials.secret)
            .tag(AccessRequestTag::class.java, AccessRequestTag()).build()
    }
    if (request.tag(AccessRequestTag::class.java) != null ||
        (credentials != null && (request.header(CLIENT_ID) == credentials.id || request.header(CLIENT_SECRET) == credentials.secret))) {
        return request.newBuilder().removeHeader(CLIENT_ID).removeHeader(CLIENT_SECRET).tag(AccessRequestTag::class.java, null).build()
    }
    return request
}

internal class CloudflareAccessInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(cloudflareAuthenticatedRequest(chain.request(), CloudflareAccessSettings.current()))
}

internal class CloudflareAccessCacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val routedUrl = if (CloudflareAccessSettings.useRemoteRoute()) remapAddonUrl(original.url) else original.url
        val request = if (routedUrl != original.url) original.newBuilder().url(routedUrl).removeHeader("Host").build() else original
        return chain.proceed(if (request.isCloudflareAccessOrigin()) request.newBuilder().header("Cache-Control", "no-cache, no-store").build() else request)
    }
}
