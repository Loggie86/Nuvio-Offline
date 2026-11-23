package com.nuvio.app.features.addons

import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudflareAccessSettingsTest {
    @Test
    fun serviceTokenOnlyReachesExactHttpsOrigin() {
        val credentials = AccessCredentials("test-client", "test-secret")
        val initial = Request.Builder().url("https://$CLOUDFLARE_ACCESS_HOST/manifest.json").build()
        val authenticated = cloudflareAuthenticatedRequest(initial, credentials)
        assertEquals("test-client", authenticated.header("CF-Access-Client-Id"))
        assertEquals("test-secret", authenticated.header("CF-Access-Client-Secret"))
        listOf("http://$CLOUDFLARE_ACCESS_HOST/", "https://$CLOUDFLARE_ACCESS_HOST:8443/",
            "https://$CLOUDFLARE_ACCESS_HOST.example.org/", "https://example.org/").forEach { url ->
            val redirected = cloudflareAuthenticatedRequest(authenticated.newBuilder().url(url).build(), credentials)
            assertNull(redirected.header("CF-Access-Client-Id"))
            assertNull(redirected.header("CF-Access-Client-Secret"))
        }
        val cleared = cloudflareAuthenticatedRequest(authenticated, null)
        assertNull(cleared.header("CF-Access-Client-Secret"))
    }
}
