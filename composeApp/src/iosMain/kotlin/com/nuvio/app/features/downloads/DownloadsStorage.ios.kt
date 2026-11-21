package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"

    actual fun loadSmartPayload(): String? = NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of("smart_downloads_v1"))
    actual fun saveSmartPayload(payload: String) { NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of("smart_downloads_v1")) }

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }
}
