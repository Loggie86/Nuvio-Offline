package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.nuvio.app.features.downloads.OfflineNetworkSettings
import com.nuvio.app.features.downloads.OfflineWifiNetworkPicker
import com.nuvio.app.features.downloads.OfflineQualityPolicy
import com.nuvio.app.features.downloads.SmartDownloadsRepository

internal fun LazyListScope.offlineDownloadsSettingsContent(isTablet: Boolean) {
    item(key = "offline-download-rules") {
        OfflineQualityPolicy.ensureLoaded()
        var resolution by remember { mutableIntStateOf(OfflineQualityPolicy.maxHeight) }
        var sizeGb by remember { mutableIntStateOf(OfflineQualityPolicy.maxSizeGb) }
        OfflineNetworkSettings.ensureLoaded()
        SmartDownloadsRepository.ensureLoaded()
        var smartTarget by remember { mutableIntStateOf(SmartDownloadsRepository.state.value.target) }
        var deleteWatched by remember { mutableStateOf(SmartDownloadsRepository.state.value.autoDeleteWatched) }
        var wifiOnly by remember { mutableStateOf(OfflineNetworkSettings.wifiOnly) }
        var blocked by remember { mutableStateOf(OfflineNetworkSettings.excludedSsids) }
        var networkName by remember { mutableStateOf("") }
        fun blockNetwork(name: String) {
            OfflineNetworkSettings.addExcludedSsid(name)
            blocked = OfflineNetworkSettings.excludedSsids
            networkName = ""
        }
        SettingsSection(title = "Offline downloads", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Maximum video resolution",
                    description = "${resolution}p — tap to switch between 720p and 1080p",
                    isTablet = isTablet,
                    onClick = {
                        resolution = if (resolution == 1080) 720 else 1080
                        OfflineQualityPolicy.setMaxHeight(resolution)
                    },
                )
                SettingsNavigationRow(
                    title = "Maximum size per file",
                    description = if (sizeGb == 0) "Unlimited" else "$sizeGb GB",
                    isTablet = isTablet,
                    onClick = {
                        sizeGb = when (sizeGb) { 2 -> 4; 4 -> 8; 8 -> 16; 16 -> 0; else -> 2 }
                        OfflineQualityPolicy.setMaxSizeGb(sizeGb)
                    },
                )
                SettingsNavigationRow(
                    title = "Reject 4K, UHD and remux",
                    description = "Always on — prevents oversized phone downloads",
                    enabled = false,
                    isTablet = isTablet,
                    onClick = {},
                )
                SettingsNavigationRow(
                    title = "Unknown quality",
                    description = "Blocked — only labelled sources can be downloaded",
                    enabled = false,
                    isTablet = isTablet,
                    onClick = {},
                )
            }
        }
        SettingsSection(title = "Smart Downloads · TV series", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Keep next unwatched episodes",
                    description = "Next $smartTarget episodes · tap for 3, 5 or 10",
                    isTablet = isTablet,
                    onClick = {
                        smartTarget = when (smartTarget) { 3 -> 5; 5 -> 10; else -> 3 }
                        SmartDownloadsRepository.setTarget(smartTarget)
                    },
                )
                SettingsSwitchRow(
                    title = "Auto-delete watched episodes",
                    description = "Remove local downloads when an episode is marked watched",
                    checked = deleteWatched,
                    isTablet = isTablet,
                    onCheckedChange = {
                        deleteWatched = it
                        SmartDownloadsRepository.setAutoDeleteWatched(it)
                    },
                )
                Text("Enable Smart Downloads for individual series from their detail pages. Downloads replenish while Nuvio Offline is running; transfers continue in the background.")
            }
        }
        SettingsSection(title = "Download network restrictions", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Wi-Fi only",
                    description = "Never download over mobile data (recommended)",
                    checked = wifiOnly,
                    isTablet = isTablet,
                    onCheckedChange = {
                        wifiOnly = it
                        OfflineNetworkSettings.setWifiOnly(it)
                    },
                )
                OfflineWifiNetworkPicker(onAdd = ::blockNetwork)
                OutlinedTextField(
                    value = networkName,
                    onValueChange = { networkName = it },
                    label = { Text("Wi-Fi name (SSID) to block") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(enabled = networkName.isNotBlank(), onClick = { blockNetwork(networkName) }) {
                    Text("Add blocked network")
                }
                if (blocked.isEmpty()) {
                    Text("No excluded Wi-Fi networks")
                } else {
                    Text("Excluded networks — tap to remove:")
                    blocked.sorted().forEach { ssid ->
                        SettingsNavigationRow(
                            title = ssid,
                            description = "Blocked from offline downloads • tap to allow",
                            isTablet = isTablet,
                            onClick = {
                                OfflineNetworkSettings.removeExcludedSsid(ssid)
                                blocked = OfflineNetworkSettings.excludedSsids
                            },
                        )
                    }
                }
                Text("Allow location access and turn on Location to identify your Wi-Fi. If the name cannot be read, downloads stay blocked whenever exclusions exist.")
            }
        }
    }
}
