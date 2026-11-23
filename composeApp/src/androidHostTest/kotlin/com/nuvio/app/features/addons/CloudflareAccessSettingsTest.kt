package com.nuvio.app.features.addons

import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class CloudflareAccessSettingsTest {
    @Test
    fun localUrlsAreMappedWithoutChangingPathOrQuery() {
        val original = "http://192.168.4.145:1337/config%2Fexample/manifest.json?value=a%2Fb".toHttpUrl()
        assertEquals("https://$CLOUDFLARE_ACCESS_HOST/usenet/config%2Fexample/manifest.json?value=a%2Fb", remapAddonUrl(original).toString())
        assertEquals("https://$CLOUDFLARE_ACCESS_HOST/abc/manifest.json", remapAddonUrl("http://192.168.4.145:3232/abc/manifest.json".toHttpUrl()).toString())
        val unrelated = "http://192.168.4.145:8123/".toHttpUrl()
        assertEquals(unrelated, remapAddonUrl(unrelated))
        assertFalse(shouldUseRemoteRoute(true, true, "Home", "Home"))
        assertTrue(shouldUseRemoteRoute(true, true, "Home", "Work"))
        assertTrue(shouldUseRemoteRoute(true, true, "Home", null))
        assertFalse(shouldUseRemoteRoute(true, false, "Home", "Work"))
    }
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
