package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
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
    suspend fun freshSource(item: DownloadItem): StreamItem? {
        val native = ServerStreams.isNativeRequest(item.videoId)
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
        withTimeoutOrNull(35_000L) {
            StreamSearchSessions.search(ProfileRepository.activeProfileId, StreamSearchTargets(
                item.contentType, item.videoId, item.seasonNumber, item.episodeNumber, targets,
                ServerStreams.sources(item.contentType, item.videoId, item.seasonNumber, item.episodeNumber, true), plugins,
            ), forceRefresh = true).collect { result ->
                candidates += when (result) {
                    is StreamLoadCompletion.Addon -> result.group.streams
                    is StreamLoadCompletion.PluginScraper -> result.streams
                }
            }
        }
        fun isEligible(stream: StreamItem): Boolean =
            stream.playableDirectUrl?.isSupportedDownloadUrl() == true &&
                OfflineQualityPolicy.rejectionReason(stream, item.durationMinutes, item.isEpisode) == null
        val eligible = candidates.filter(::isEligible).ifEmpty { embedded.filter(::isEligible) }
        return eligible.minWithOrNull(compareBy<StreamItem> { item.sourceRefreshAttempts > 0 && it.playableDirectUrl == item.sourceUrl }
            .thenBy { it.behaviorHints.videoSize == null }
            .thenBy { it.behaviorHints.videoSize ?: Long.MAX_VALUE })
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
                stream.behaviorHints.videoSize == null
            }.thenBy { stream -> stream.behaviorHints.videoSize ?: Long.MAX_VALUE }
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
