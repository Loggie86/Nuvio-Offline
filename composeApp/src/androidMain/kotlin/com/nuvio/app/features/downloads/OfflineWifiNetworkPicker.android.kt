package com.nuvio.app.features.downloads

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal actual fun OfflineWifiNetworkPicker(onAdd: (String) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val name = if (permission) OfflineWifiPlatform.currentSsid() else null
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!permission) {
            TextButton(onClick = { request.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                Text("Allow Wi-Fi name access (required to block specific networks)")
            }
        } else {
            Text("Current Wi-Fi: ${name ?: "Unavailable — check Location is enabled"}")
            TextButton(enabled = name != null, onClick = { name?.let(onAdd) }) {
                Text("Block current Wi-Fi network")
            }
        }
    }
}
