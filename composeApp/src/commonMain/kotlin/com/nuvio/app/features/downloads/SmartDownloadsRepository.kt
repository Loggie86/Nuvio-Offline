package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.releasedPlayableEpisodes
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
internal data class SmartEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumbnail: String? = null,
    val runtimeMinutes: Int? = null,
)

@Serializable
internal data class SmartSeries(
    val id: String,
    val name: String,
    val poster: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val episodes: List<SmartEpisode> = emptyList(),
)

@Serializable
internal data class SmartDownloadState(
    val target: Int = 3,
    val autoDeleteWatched: Boolean = false,
    val followed: List<SmartSeries> = emptyList(),
)

/** Keeps the next N unwatched episodes queued for series explicitly followed by the user. */
internal object SmartDownloadsRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(SmartDownloadState())
    val state: StateFlow<SmartDownloadState> = mutable.asStateFlow()
    private var loaded = false
    private var observing = false
    private val lastUnsuccessfulAttempt = mutableMapOf<String, Long>()
    private val mutableErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = mutableErrors.asStateFlow()

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        mutable.value = runCatching {
            json.decodeFromString<SmartDownloadState>(DownloadsStorage.loadSmartPayload().orEmpty())
        }.getOrDefault(SmartDownloadState())
    }

    fun onProfileChanged() {
        mutableErrors.value = emptyMap()
        lastUnsuccessfulAttempt.clear()
        loaded = false
        ensureLoaded()
    }

    private fun save(value: SmartDownloadState) {
        mutable.value = value
        DownloadsStorage.saveSmartPayload(json.encodeToString(value))
    }

    fun setTarget(value: Int) {
        ensureLoaded()
        require(value in listOf(3, 5, 10))
        save(mutable.value.copy(target = value))
    }

    fun setAutoDeleteWatched(value: Boolean) {
        ensureLoaded()
        save(mutable.value.copy(autoDeleteWatched = value))
    }

    fun isFollowing(seriesId: String): Boolean {
        ensureLoaded()
        return mutable.value.followed.any { it.id == seriesId }
    }

    fun toggleSeries(meta: MetaDetails) {
        ensureLoaded()
        val existing = mutable.value.followed
        if (existing.any { it.id == meta.id }) {
            mutableErrors.value = mutableErrors.value - meta.id
            save(mutable.value.copy(followed = existing.filterNot { it.id == meta.id }))
        } else {
            val episodes = meta.releasedPlayableEpisodes(CurrentDateProvider.todayIsoDate()).filter { it.season != null && it.episode != null && it.available && (it.season ?: 0) > 0 }
                .map { SmartEpisode(it.id, it.season!!, it.episode!!, it.title, it.thumbnail, it.runtime) }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            save(mutable.value.copy(followed = existing + SmartSeries(
                meta.id, meta.name, meta.poster, meta.logo, meta.background, episodes,
            )))
            if (episodes.isEmpty()) {
                mutableErrors.value = mutableErrors.value + (meta.id to "No released episode details available")
            }
        }
    }

    fun updateSeries(meta: MetaDetails) {
        ensureLoaded()
        if (!isFollowing(meta.id)) return
        val episodes = meta.releasedPlayableEpisodes(CurrentDateProvider.todayIsoDate()).filter { it.season != null && it.episode != null && it.available && (it.season ?: 0) > 0 }
            .map { SmartEpisode(it.id, it.season!!, it.episode!!, it.title, it.thumbnail, it.runtime) }
            .sortedWith(compareBy({ it.season }, { it.episode }))
        val revised = mutable.value.followed.map { series ->
            if (series.id == meta.id) series.copy(episodes = episodes, name = meta.name,
                poster = meta.poster, logo = meta.logo, background = meta.background) else series
        }
        if (revised != mutable.value.followed) save(mutable.value.copy(followed = revised))
    }

    fun retrySeries(seriesId: String) {
        lastUnsuccessfulAttempt.keys.filter { it.startsWith("$seriesId|") }.forEach(lastUnsuccessfulAttempt::remove)
        mutableErrors.value = mutableErrors.value - seriesId
        scope.launch { runCatching { reconcile() } }
    }

    fun ensureStarted() {
        ensureLoaded()
        if (observing) return
        observing = true
        DownloadsRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        scope.launch {
            combine(mutable, WatchedRepository.uiState, WatchProgressRepository.uiState, DownloadsRepository.uiState) {
                    settings, watched, progress, downloads ->
                // Ignore byte-counter updates; recompute only if completion, queue, or rules changed.
                listOf(settings.hashCode(), watched.watchedKeys.hashCode(),
                    progress.entries.filter { it.isEffectivelyCompleted }.map { it.videoId }.hashCode(),
                    downloads.items.map { Triple(it.id, it.status, it.seasonNumber to it.episodeNumber) }.hashCode())
                    .joinToString(":")
            }.distinctUntilChanged().collect {
                delay(900)
                runCatching { reconcile() }
            }
        }
    }

    private suspend fun reconcile() = mutex.withLock {
        val settings = mutable.value
        if (settings.followed.isEmpty()) return@withLock
        val watched = WatchedRepository.uiState.value.watchedKeys
        val completed = WatchProgressRepository.uiState.value.entries.filter { it.isEffectivelyCompleted }
        val downloads = DownloadsRepository.uiState.value.items
        fun isWatched(series: SmartSeries, ep: SmartEpisode): Boolean =
            watchedItemKey("series", series.id, ep.season, ep.episode) in watched ||
                completed.any { it.parentMetaId == series.id && it.seasonNumber == ep.season && it.episodeNumber == ep.episode }

        for (series in settings.followed) {
            if (settings.autoDeleteWatched) {
                downloads.filter { it.parentMetaId == series.id && it.seasonNumber != null && it.episodeNumber != null &&
                    series.episodes.any { ep -> ep.season == it.seasonNumber && ep.episode == it.episodeNumber && isWatched(series, ep) } }
                    .forEach { DownloadsRepository.cancelDownload(it.id) }
            }
            val upcoming = series.episodes.filterNot { isWatched(series, it) }.take(settings.target)
            if (upcoming.isEmpty()) {
                mutableErrors.value = mutableErrors.value + (series.id to "No unwatched released episodes found")
            }
            // Fill the requested target now; don't depend on another show being added
            // to trigger the next episode. Failed searches use a cooldown to prevent loops.
            for (episode in upcoming) {
                val existing = DownloadsRepository.uiState.value.items.any { item ->
                    item.parentMetaId == series.id && item.seasonNumber == episode.season &&
                        item.episodeNumber == episode.episode && item.status != DownloadStatus.Failed
                }
                if (existing) continue
                val key = "${series.id}|${episode.season}|${episode.episode}"
                val now = DownloadsClock.nowEpochMs()
                if (now - (lastUnsuccessfulAttempt[key] ?: 0L) < 10 * 60 * 1000L) continue
                val result = runCatching { OfflineAutoDownload.enqueue(
                    type = "series", videoId = episode.id, parentMetaId = series.id, parentMetaType = "series",
                    title = series.name, logo = series.logo, poster = series.poster, background = series.background,
                    season = episode.season, episode = episode.episode, episodeTitle = episode.title,
                    episodeThumbnail = episode.thumbnail, durationMinutes = episode.runtimeMinutes,
                ) }.getOrElse { error -> error.message ?: "Episode source search failed" }
                val queued = DownloadsRepository.uiState.value.items.any { item ->
                    item.parentMetaId == series.id && item.seasonNumber == episode.season &&
                        item.episodeNumber == episode.episode && item.status != DownloadStatus.Failed
                }
                if (!queued) {
                    lastUnsuccessfulAttempt[key] = now
                    mutableErrors.value = mutableErrors.value + (series.id to "S${episode.season}E${episode.episode}: $result")
                } else {
                    lastUnsuccessfulAttempt.remove(key)
                    mutableErrors.value = mutableErrors.value - series.id
                }
            }
        }
    }
}
