package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.hasPendingEnabledManifests
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.streams.InstalledStreamAddonTarget
import com.nuvio.app.features.streams.StreamSearchTargets
import com.nuvio.app.features.streams.StreamSearchSessions
import com.nuvio.app.features.streams.StreamLoadCompletion
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.streams.toPluginProviderGroups
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Search without opening the stream selection screen, then queue the best eligible direct URL. */
internal object OfflineAutoDownload {
    /** Independent search: never replaces the currently displayed stream picker. */
    suspend fun freshSource(item: DownloadItem): OfflineSourceResult {
        val native = ServerStreams.isNativeRequest(item.videoId)
        if (!native) {
            AddonRepository.initialize()
            // Startup may have restored the queue before addon manifests are loaded.
            withTimeoutOrNull(12_000L) {
                AddonRepository.uiState.first { !it.addons.hasPendingEnabledManifests() }
            }
        }
        val addons = if (native) emptyList() else AddonRepository.uiState.value.addons.enabledAddons()
        val targets = addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(item.contentType, item.videoId)) return@mapNotNull null
            InstalledStreamAddonTarget(addon.displayTitle.ifBlank { manifest.name }, addon.streamAddonInstanceId(manifest.id), manifest)
        }
        val plugins = if (AppFeaturePolicy.pluginsEnabled && !native) {
            PluginRepository.initialize()
            val state = PluginRepository.uiState.value
            PluginRepository.getEnabledScrapersForType(item.contentType).toPluginProviderGroups(state.repositories, state.groupStreamsByRepository)
        } else emptyList()
        if (plugins.isNotEmpty()) PluginRepository.setLocalPluginSearchPaused(false)
        val candidates = mutableListOf<StreamItem>()
        val embedded = MetaDetailsRepository.findEmbeddedStreams(item.videoId)
        var failedProviders = 0
        val searchCompleted = withTimeoutOrNull(45_000L) {
            StreamSearchSessions.search(ProfileRepository.activeProfileId, StreamSearchTargets(
                item.contentType, item.videoId, item.seasonNumber, item.episodeNumber, targets,
                ServerStreams.sources(item.contentType, item.videoId, item.seasonNumber, item.episodeNumber, true), plugins,
            ), forceRefresh = true).collect { result ->
                candidates += when (result) {
                    is StreamLoadCompletion.Addon -> {
                        if (!result.group.error.isNullOrBlank()) failedProviders++
                        result.group.streams
                    }
                    is StreamLoadCompletion.PluginScraper -> {
                        if (!result.error.isNullOrBlank()) failedProviders++
                        result.streams
                    }
                }
            }
            true
        } == true
        candidates += embedded
        fun isEligible(stream: StreamItem): Boolean =
            stream.playableDirectUrl?.isSupportedDownloadUrl() == true &&
                !item.hasRejectedSizeSource(stream) &&
                OfflineQualityPolicy.rejectionReason(stream, item.durationMinutes, item.isEpisode) == null
        if (candidates.none(::isEligible)) {
            // Match the existing playback preparation path for configured, cached sources.
            PlayerSettingsRepository.ensureLoaded()
            val prepared = mutableListOf<StreamItem>()
            withTimeoutOrNull(20_000L) {
                DirectDebridStreamPreparer.prepare(
                    streams = candidates.filter { it.isInstalledAddonStream && OfflineQualityPolicy.rejectionReason(it, item.durationMinutes, item.isEpisode) == null },
                    season = item.seasonNumber, episode = item.episodeNumber,
                    playerSettings = PlayerSettingsRepository.uiState.value,
                    installedAddonNames = targets.map { it.addonName }.toSet(),
                ) { _, stream -> prepared += stream }
            }
            candidates += prepared
        }
        val eligible = candidates.filter(::isEligible)
        val selected = eligible.minWithOrNull(compareBy<StreamItem> { item.sourceRefreshAttempts > 0 && it.playableDirectUrl == item.sourceUrl }
            .thenBy { reportedDownloadSize(it) == null }
            .thenBy { reportedDownloadSize(it) ?: Long.MAX_VALUE })
        return OfflineSourceResult(selected, if (selected == null) sourceFailureSummary(item, candidates, failedProviders, searchCompleted) else null)
    }

    suspend fun enqueue(
        type: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        durationMinutes: Int? = null,
    ): String {
        OfflineQualityPolicy.ensureLoaded()
        StreamsRepository.load(type, videoId, parentMetaId, season, episode, manualSelection = true)
        val token = StreamsRepository.requestToken(type, videoId, season, episode, manualSelection = true)
        val loaded = withTimeoutOrNull(35_000L) {
            StreamsRepository.uiState.first { state ->
                state.requestToken == token && !state.isAnyLoading &&
                    (state.groups.isNotEmpty() || state.emptyStateReason != null)
            }
        } ?: return "Stream search timed out. Try again."

        val candidates = loaded.allStreams.filter { stream ->
            stream.playableDirectUrl != null && OfflineQualityPolicy.rejectionReason(stream, durationMinutes, season != null) == null
        }
        val preferred = candidates.minWithOrNull(
            compareBy<com.nuvio.app.features.streams.StreamItem> { stream ->
                // Prefer known, smaller files over unreported sizes.
                reportedDownloadSize(stream) == null
            }.thenBy { stream -> reportedDownloadSize(stream) ?: Long.MAX_VALUE }
        ) ?: return "No downloadable stream meets your quality and size limits."

        val result = DownloadsRepository.enqueueFromStream(
            contentType = type, videoId = videoId, parentMetaId = parentMetaId,
            parentMetaType = parentMetaType, title = title, logo = logo,
            poster = poster, background = background, seasonNumber = season,
            episodeNumber = episode, episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail, durationMinutes = durationMinutes, stream = preferred,
        )
        return result.toastMessage()
    }
}

internal data class OfflineSourceResult(val stream: StreamItem?, val error: String?)

internal fun sourceFailureSummary(item: DownloadItem, candidates: List<StreamItem>, failedProviders: Int, searchCompleted: Boolean): String {
    val direct = candidates.filter { it.playableDirectUrl?.isSupportedDownloadUrl() == true }
    val blocked = direct.filter { item.hasRejectedSizeSource(it) }
    val reasons = (direct - blocked.toSet()).mapNotNull { OfflineQualityPolicy.rejectionReason(it, item.durationMinutes, item.isEpisode) }
    val size = reasons.count { it.contains("size limit") }
    val unknown = reasons.count { it.contains("unknown") }
    val quality = reasons.size - size - unknown
    return buildString {
        append("${candidates.size} sources; ${direct.size} direct")
        if (size + blocked.size > 0) append(" · ${size + blocked.size} oversized")
        if (unknown > 0) append(" · $unknown unknown quality")
        if (quality > 0) append(" · $quality above quality limit")
        if (failedProviders > 0) append(" · $failedProviders provider errors")
        if (!searchCompleted) append(" · search timed out")
    }
}

// A filename is stable when a provider rotates signed URLs; otherwise use the exact URL.
internal fun StreamItem.downloadCandidateKey(): String? = behaviorHints.filename
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { "file:$addonId:$it" }
    ?: playableDirectUrl?.let { "url:$it" }

internal fun DownloadItem.hasRejectedSizeSource(stream: StreamItem): Boolean =
    stream.downloadCandidateKey() in rejectedSizeSources || "url:${stream.playableDirectUrl}" in rejectedSizeSources
