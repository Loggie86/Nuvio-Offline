package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Search without opening the stream selection screen, then queue the best eligible direct URL. */
internal object OfflineAutoDownload {
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
            stream.playableDirectUrl != null && OfflineQualityPolicy.rejectionReason(stream) == null
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
            episodeThumbnail = episodeThumbnail, stream = preferred,
        )
        return result.toastMessage()
    }
}
