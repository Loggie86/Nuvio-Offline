package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.downloads.OfflineQualityPolicy

internal fun LazyListScope.offlineDownloadsSettingsContent(isTablet: Boolean) {
    item(key = "offline-download-rules") {
        OfflineQualityPolicy.ensureLoaded()
        var resolution by remember { mutableIntStateOf(OfflineQualityPolicy.maxHeight) }
        var sizeGb by remember { mutableIntStateOf(OfflineQualityPolicy.maxSizeGb) }
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
    }
}
