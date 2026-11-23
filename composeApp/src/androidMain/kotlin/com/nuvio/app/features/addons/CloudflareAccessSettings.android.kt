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
        val request = chain.request()
        return chain.proceed(if (request.isCloudflareAccessOrigin()) request.newBuilder().header("Cache-Control", "no-cache, no-store").build() else request)
    }
}
