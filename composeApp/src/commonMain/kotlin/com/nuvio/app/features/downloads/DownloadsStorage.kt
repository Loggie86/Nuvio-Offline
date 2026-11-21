package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadSmartPayload(): String?
    fun saveSmartPayload(payload: String)
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
