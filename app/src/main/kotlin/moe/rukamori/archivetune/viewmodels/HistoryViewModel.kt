/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.HistorySource
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.AlbumEntity
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.Event
import moe.rukamori.archivetune.db.entities.EventWithSong
import moe.rukamori.archivetune.db.entities.ModulePlayEntity
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.utils.reportException
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        val database: MusicDatabase,
    ) : ViewModel() {
        var historySource = MutableStateFlow(HistorySource.LOCAL)
        private val _remoteHistoryState = MutableStateFlow<RemoteHistoryUiState>(RemoteHistoryUiState.Loading)
        val remoteHistoryState: StateFlow<RemoteHistoryUiState> = _remoteHistoryState
        private val loadedEvents = mutableListOf<EventWithSong>()
        val events = MutableStateFlow<Map<DateAgo, List<EventWithSong>>>(emptyMap())
        /**
         * Fork feature: playback history of custom-source (module) tracks. These are transient
         * MediaItems, not Room `Song` rows, so they live in their own table.
         */
        val modulePlays: StateFlow<List<ModulePlayEntity>> =
            database.modulePlayDao
                .recent(50)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val isLoadingMoreEvents = MutableStateFlow(false)
        val canLoadMoreEvents = MutableStateFlow(true)

        private val today = LocalDate.now()
        private val thisMonday = today.with(DayOfWeek.MONDAY)
        private val lastMonday = thisMonday.minusDays(7)

        init {
            loadMoreEvents()
            // Fork feature: fold custom-source (module) plays into the same date groups.
            viewModelScope.launch {
                modulePlays.collect { plays ->
                    moduleEvents = plays.map(::moduleEvent)
                    regroup()
                }
            }
        }

        private var moduleEvents: List<EventWithSong> = emptyList()

        private fun regroup() {
            events.value = (loadedEvents + moduleEvents).groupEventsByDate()
        }

        /** Module tracks are transient MediaItems, so they are synthesized into the same shape. */
        private fun moduleEvent(play: ModulePlayEntity): EventWithSong {
            val artists =
                play.artists
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            return EventWithSong(
                event =
                    Event(
                        // Negative synthetic id: the list keys on event.id, and all
                        // unsaved rows default to 0, which crashes LazyColumn.
                        id = -Math.abs(play.mediaId.hashCode()).toLong() - 1L,
                        songId = "module:${play.mediaId}",
                        timestamp =
                            LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(play.playedAt),
                                ZoneId.systemDefault(),
                            ),
                        playTime = (play.durationSeconds ?: 0) * 1000L,
                    ),
                song =
                    Song(
                        song =
                            SongEntity(
                                id = "module:${play.mediaId}",
                                title = play.title,
                                duration = play.durationSeconds ?: -1,
                                thumbnailUrl = play.thumbnailUrl,
                                albumName = play.album,
                            ),
                        artists = artists.map { ArtistEntity(id = it, name = it) },
                        album =
                            play.album?.let {
                                AlbumEntity(
                                    id = "module-album:$it",
                                    title = it,
                                    songCount = 1,
                                    duration = play.durationSeconds ?: 0,
                                    thumbnailUrl = play.thumbnailUrl,
                                )
                            },
                    ),
            )
        }

        fun loadMoreEvents() {
            if (isLoadingMoreEvents.value || !canLoadMoreEvents.value) return

            isLoadingMoreEvents.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val page = database.events(limit = HISTORY_PAGE_SIZE, offset = loadedEvents.size)
                    loadedEvents += page
                    regroup()
                    canLoadMoreEvents.value = page.size == HISTORY_PAGE_SIZE
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    reportException(exception)
                    canLoadMoreEvents.value = false
                } finally {
                    isLoadingMoreEvents.value = false
                }
            }
        }

        fun fetchRemoteHistory() {
            _remoteHistoryState.value = RemoteHistoryUiState.Loading
            viewModelScope.launch(Dispatchers.IO) {
                YouTube
                    .musicHistory()
                    .onSuccess {
                        _remoteHistoryState.value = it.toRemoteUiState()
                    }.onFailure {
                        _remoteHistoryState.value = RemoteHistoryUiState.Error
                        reportException(it)
                    }
            }
        }

        /**
         * Fetches remote history without transitioning the UI to a Loading state.
         *
         * - [RemoteHistoryUiState.Error]   → delegates to [fetchRemoteHistory] (user sees spinner)
         * - [RemoteHistoryUiState.Loading] → fetches silently; transitions to Error on failure
         * - [RemoteHistoryUiState.Empty]   → fetches silently; transitions to Error on failure
         * - [RemoteHistoryUiState.Success] → fetches silently; keeps cached data + logs warning on failure
         *
         * Call from a coroutine context (e.g. LaunchedEffect or viewModelScope.launch).
         */
        suspend fun fetchRemoteHistorySilent() {
            val snapshot = _remoteHistoryState.value

            if (snapshot is RemoteHistoryUiState.Error) {
                fetchRemoteHistory()
                return
            }

            withContext(Dispatchers.IO) {
                try {
                    val page = YouTube.musicHistory().getOrThrow()
                    _remoteHistoryState.value = page.toRemoteUiState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag("History").w(e, "Silent remote history fetch failed")
                    when (snapshot) {
                        is RemoteHistoryUiState.Success -> {
                            // Keep cached data; don't disrupt the user
                        }

                        is RemoteHistoryUiState.Loading,
                        is RemoteHistoryUiState.Empty,
                        -> {
                            _remoteHistoryState.value = RemoteHistoryUiState.Error
                        }
                    }
                }
            }
        }

        private fun HistoryPage.toRemoteUiState(): RemoteHistoryUiState =
            if (sections?.any { it.songs.isNotEmpty() } == true) {
                RemoteHistoryUiState.Success(this)
            } else {
                RemoteHistoryUiState.Empty
            }

        /**
         * Non-suspend wrapper for call sites that are not already in a coroutine
         * (e.g. click handlers in Compose).
         */
        fun enqueueSilentFetch() {
            viewModelScope.launch(Dispatchers.IO) {
                fetchRemoteHistorySilent()
            }
        }

        fun removeEventsFromHistory(eventIds: List<Long>) {
            val uniqueEventIds = eventIds.distinct()
            if (uniqueEventIds.isEmpty()) return

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    database.deleteEventsByIds(uniqueEventIds)
                    loadedEvents.clear()
                    events.value = emptyMap()
                    canLoadMoreEvents.value = true
                    loadMoreEvents()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    reportException(exception)
                }
            }
        }

        private fun List<EventWithSong>.groupEventsByDate(): Map<DateAgo, List<EventWithSong>> =
            groupBy {
                val date = it.event.timestamp.toLocalDate()
                val daysAgo = ChronoUnit.DAYS.between(date, today).toInt()
                when {
                    daysAgo == 0 -> DateAgo.Today
                    daysAgo == 1 -> DateAgo.Yesterday
                    date >= thisMonday -> DateAgo.ThisWeek
                    date >= lastMonday -> DateAgo.LastWeek
                    else -> DateAgo.Other(date.withDayOfMonth(1))
                }
            }.toSortedMap(
                compareBy { dateAgo ->
                    when (dateAgo) {
                        DateAgo.Today -> 0L
                        DateAgo.Yesterday -> 1L
                        DateAgo.ThisWeek -> 2L
                        DateAgo.LastWeek -> 3L
                        is DateAgo.Other -> ChronoUnit.DAYS.between(dateAgo.date, today)
                    }
                },
            ).mapValues { entry ->
                entry.value.distinctBy { it.song.id }
            }
    }

private const val HISTORY_PAGE_SIZE = 100

sealed interface RemoteHistoryUiState {
    data object Loading : RemoteHistoryUiState

    data class Success(
        val page: HistoryPage,
    ) : RemoteHistoryUiState

    data object Empty : RemoteHistoryUiState

    data object Error : RemoteHistoryUiState
}

sealed class DateAgo {
    data object Today : DateAgo()

    data object Yesterday : DateAgo()

    data object ThisWeek : DateAgo()

    data object LastWeek : DateAgo()

    class Other(
        val date: LocalDate,
    ) : DateAgo() {
        override fun equals(other: Any?): Boolean {
            if (other is Other) return date == other.date
            return super.equals(other)
        }

        override fun hashCode(): Int = date.hashCode()
    }
}
