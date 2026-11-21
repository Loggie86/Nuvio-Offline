package com.nuvio.app.features.downloads

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal actual fun OfflineWifiNetworkPicker(onAdd: (String) -> Unit) {
    Text("Enter the Wi-Fi network name below to exclude it.")
}
