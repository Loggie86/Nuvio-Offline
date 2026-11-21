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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.nuvio.app.features.downloads.OfflineNetworkSettings
import com.nuvio.app.features.downloads.OfflineWifiNetworkPicker
import com.nuvio.app.features.downloads.OfflineQualityPolicy
import com.nuvio.app.features.downloads.SmartDownloadsRepository

internal fun LazyListScope.offlineDownloadsSettingsContent(isTablet: Boolean) {
    item(key = "offline-download-rules") {
        OfflineQualityPolicy.ensureLoaded()
        var resolution by remember { mutableIntStateOf(OfflineQualityPolicy.maxHeight) }
        var sizePreset by remember { mutableIntStateOf(OfflineQualityPolicy.sizePreset) }
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
                    title = "Download size · per hour",
                    description = when (sizePreset) {
                        75 -> "Storage Saver · 0.75 GB/hour"
                        250 -> "High Quality · 2.5 GB/hour"
                        else -> "Balanced · 1.5 GB/hour (default)"
                    } + " · tap to change",
                    isTablet = isTablet,
                    onClick = {
                        sizePreset = when (sizePreset) { 75 -> 150; 150 -> 250; else -> 75 }
                        OfflineQualityPolicy.setSizePreset(sizePreset)
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
        Spacer(modifier = Modifier.height(24.dp))
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
                Text(
                    "Enable Smart Downloads from each TV series page. Nuvio fills the next unwatched episodes while the app is running.",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
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
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.foundation.layout.Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    OfflineWifiNetworkPicker(onAdd = ::blockNetwork)
                }
                OutlinedTextField(
                    value = networkName,
                    onValueChange = { networkName = it },
                    label = { Text("Wi-Fi name (SSID) to block") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                TextButton(modifier = Modifier.padding(horizontal = 10.dp), enabled = networkName.isNotBlank(), onClick = { blockNetwork(networkName) }) {
                    Text("Add blocked network")
                }
                if (blocked.isEmpty()) {
                    Text("No excluded Wi-Fi networks", modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                } else {
                    Text("Excluded networks — tap to remove:", modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
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
                Text(
                    "Precise Location must be enabled to identify Wi-Fi names. Unknown networks stay blocked when exclusions are set.",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }
        }
    }
}
