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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal actual fun OfflineWifiNetworkPicker(onAdd: (String) -> Unit) {
    val context = LocalContext.current
    fun isPreciseLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    // Track permission as Compose state: merely reading a local "refresh" variable
    // doesn't cause recomposition when Android returns from the permission dialog.
    var permissionGranted by remember { mutableStateOf(isPreciseLocationGranted()) }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted && isPreciseLocationGranted()
    }
    val name = if (permissionGranted) OfflineWifiPlatform.currentSsid() else null

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!permissionGranted) {
            TextButton(onClick = {
                if (isPreciseLocationGranted()) {
                    permissionGranted = true
                } else {
                    request.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }) {
                Text("Allow precise location to identify Wi-Fi networks")
            }
        } else {
            Text("Current Wi-Fi: ${name ?: "Unavailable — check that Location is on and Precise location is allowed"}")
            TextButton(enabled = name != null, onClick = { name?.let(onAdd) }) {
                Text("Block current Wi-Fi network")
            }
            TextButton(onClick = { permissionGranted = isPreciseLocationGranted() }) {
                Text("Refresh current Wi-Fi")
            }
        }
    }
}
