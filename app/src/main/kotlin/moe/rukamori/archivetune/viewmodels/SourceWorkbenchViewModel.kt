/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.modules.InstalledModule
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.ModulePlayEntity
import moe.rukamori.archivetune.modules.ModuleRepository
import moe.rukamori.archivetune.modules.ModuleSongStore
import moe.rukamori.archivetune.modules.ModuleStream
import moe.rukamori.archivetune.modules.StreamProbe
import moe.rukamori.archivetune.modules.ModuleTrack
import javax.inject.Inject

/**
 * Drives the source workbench: pick an installed module, search it directly, and
 * resolve a playable stream. Modules only ever matched against existing songs, so
 * this is the missing "search a source" entry point.
 */
@HiltViewModel
class SourceWorkbenchViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: ModuleRepository,
        private val database: MusicDatabase,
        private val moduleSongs: ModuleSongStore,
    ) : ViewModel() {
        companion object {
            const val PAGE_SIZE = 20
        }

        /** Per-source counters for the debug console: empty results are the usual failure mode. */
        data class SourceStat(
            val queries: Int = 0,
            val empty: Int = 0,
            val failed: Int = 0,
            val lastMs: Long = 0,
            val lastError: String? = null,
        )

        data class Hit(
            val track: ModuleTrack,
            val moduleId: String,
        )

        data class UiState(
            val modules: List<InstalledModule> = emptyList(),
            /** Explicit scope; starts as every installed source. */
            val selectedModuleIds: Set<String> = emptySet(),
            /** False until the user touches the scope, so a new install auto-selects everything. */
            val selectionTouched: Boolean = false,
            /** Sources that still have a next page. */
            val moreSources: Set<String> = emptySet(),
            /** Sources the last search actually ran against; drives the result tabs. */
            val searchedModuleIds: Set<String> = emptySet(),
            /** Popup payload for a failed search or preview. */
            val error: String? = null,
            val loadingMore: Set<String> = emptySet(),
            val query: String = "",
            val hits: List<Hit> = emptyList(),
            val searching: Boolean = false,
            val message: String? = null,
            val stream: ModuleStream? = null,
            val playingTitle: String? = null,
            val playingTrack: ModuleTrack? = null,
            val trace: List<String> = emptyList(),
            val stats: Map<String, SourceStat> = emptyMap(),
        )

        private val _state = MutableStateFlow(UiState())
        val state = _state.asStateFlow()

        init {
            viewModelScope.launch {
                // The registry is lazy; without this the screen would show "not installed"
                // whenever it is the first module screen opened after a launch.
                runCatching { repository.snapshot() }
                repository.state.collect { registry ->
                    val modules = registry?.modules?.filter(InstalledModule::enabled).orEmpty()
                    _state.update { current ->
                        current.copy(
                            modules = modules,
                            selectedModuleIds =
                                if (current.selectionTouched) {
                                    current.selectedModuleIds.filterTo(mutableSetOf()) { id ->
                                        modules.any { module -> module.id == id }
                                    }
                                } else {
                                    modules.mapTo(mutableSetOf(), InstalledModule::id)
                                },
                        )
                    }
                }
            }
        }

        /** Toggles one source in the search scope. */
        fun toggleModule(id: String) =
            _state.update {
                val next = it.selectedModuleIds.toMutableSet()
                if (!next.remove(id)) next.add(id)
                it.copy(selectedModuleIds = next, selectionTouched = true)
            }

        fun selectAllModules() =
            _state.update {
                it.copy(
                    selectedModuleIds = it.modules.mapTo(mutableSetOf(), InstalledModule::id),
                    selectionTouched = true,
                )
            }

        fun invertModuleSelection() =
            _state.update {
                it.copy(
                    selectedModuleIds =
                        it.modules.mapTo(mutableSetOf(), InstalledModule::id)
                            .apply { removeAll(it.selectedModuleIds) },
                    selectionTouched = true,
                )
            }

        fun clearModuleSelection() =
            _state.update { it.copy(selectedModuleIds = emptySet(), selectionTouched = true) }

        fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

        fun search() {
            val current = _state.value
            val targets = targetsFor(current)
            val query = current.query.trim()
            if (query.isEmpty() || targets.isEmpty()) return
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        searching = true,
                        message = null,
                        error = null,
                        searchedModuleIds = targets.mapTo(mutableSetOf()) { module -> module.id },
                    )
                }
                try {
                    // Sequential on purpose: evaluating two 900 KB providers at once starves
                    // the JS timers and both requests abort with TIMEOUT.
                    val perSource =
                        targets.map { module ->
                            val began = System.currentTimeMillis()
                            val result = runCatching { withTimeout(25_000) { search(module, query, page = 1) } }
                            Triple(module, result, System.currentTimeMillis() - began)
                        }
                    val hits = perSource.flatMap { (module, result, _) ->
                        result.getOrNull()?.map { track -> Hit(track, module.id) }.orEmpty()
                    }
                    perSource.forEach { (module, result, time) ->
                        result
                            .onSuccess { trace("search ${module.manifest.name} \"$query\" -> ${it.size} tracks / ${time}ms") }
                            .onFailure { trace("search ${module.manifest.name} failed: ${describe(it)}") }
                    }
                    _state.update { state ->
                        val stats = state.stats.toMutableMap()
                        perSource.forEach { (module, result, time) ->
                            val key = module.manifest.name
                            val previous = stats[key] ?: SourceStat()
                            stats[key] =
                                result.fold(
                                    onSuccess = { list ->
                                        previous.copy(
                                            queries = previous.queries + 1,
                                            empty = previous.empty + if (list.isEmpty()) 1 else 0,
                                            lastMs = time,
                                            lastError = null,
                                        )
                                    },
                                    onFailure = { error ->
                                        previous.copy(
                                            queries = previous.queries + 1,
                                            failed = previous.failed + 1,
                                            lastMs = time,
                                            lastError = describe(error),
                                        )
                                    },
                                )
                        }
                        state.copy(
                            searching = false,
                            hits = hits,
                            moreSources =
                                perSource
                                    .filter { (_, result, _) -> (result.getOrNull()?.size ?: 0) > 0 }
                                    .mapTo(mutableSetOf()) { (module, _, _) -> module.id },
                            stats = stats,
                            message =
                                if (hits.isEmpty() && perSource.any { entry -> entry.second.isSuccess }) {
                                    context.getString(R.string.source_workbench_message_single_no_result)
                                } else {
                                    null
                                },
                            error =
                                if (hits.isEmpty() && perSource.all { entry -> entry.second.isFailure }) {
                                    perSource.firstNotNullOfOrNull { entry -> entry.second.exceptionOrNull() }
                                        ?.let { failure -> describe(failure) }
                                        ?: context.getString(R.string.source_workbench_message_no_results)
                                } else {
                                    null
                                },
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val detail = describe(error)
                    _state.update { it.copy(searching = false, hits = emptyList(), error = detail) }
                    trace("search failed: $detail")
                }
            }
        }

        /** Appends the next page of one source; the rest of the list stays put. */
        fun loadMore(moduleId: String) {
            val current = _state.value
            val module = current.modules.firstOrNull { it.id == moduleId } ?: return
            val query = current.query.trim()
            if (query.isEmpty() || moduleId in current.loadingMore) return
            val loaded = current.hits.count { it.moduleId == moduleId }
            val page = loaded / PAGE_SIZE + 1
            viewModelScope.launch {
                _state.update { it.copy(loadingMore = it.loadingMore + moduleId) }
                try {
                    val more = search(module, query, page = page)
                    _state.update { state ->
                        val merged = (state.hits + more.map { Hit(it, moduleId) }).distinctBy { it.moduleId to it.track.id }
                        state.copy(
                            hits = merged,
                            loadingMore = state.loadingMore - moduleId,
                            // A source that ignores the page argument returns the same tracks
                            // again; dropping it keeps the button honest.
                            moreSources =
                                if (more.isNotEmpty() && merged.size > state.hits.size) {
                                    state.moreSources
                                } else {
                                    state.moreSources - moduleId
                                },
                        )
                    }
                    trace("search ${module.manifest.name} page $page -> ${more.size} tracks")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val detail = describe(error)
                    _state.update { it.copy(loadingMore = it.loadingMore - moduleId) }
                    trace("search ${module.manifest.name} page $page failed: $detail")
                }
            }
        }

        /** Returns true when the track was newly written into the playlist. */
        suspend fun addToPlaylist(
            playlistId: String,
            hit: Hit,
        ): Boolean {
            val module = _state.value.modules.firstOrNull { it.id == hit.moduleId } ?: return false
            return try {
                val added = moduleSongs.addToPlaylist(playlistId, module, hit.track)
                _state.update {
                    it.copy(
                        message =
                            context.getString(
                                if (added) R.string.source_workbench_added else R.string.source_workbench_already_added,
                                hit.track.title,
                            ),
                    )
                }
                added
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = describe(error)
                _state.update { it.copy(error = detail) }
                trace("add to playlist failed: $detail")
                false
            }
        }

        fun play(hit: Hit) {
            val module = _state.value.modules.firstOrNull { it.id == hit.moduleId } ?: return
            viewModelScope.launch {
                _state.update { it.copy(message = null, error = null) }
                val started = System.currentTimeMillis()
                try {
                    val engine = "${module.id}:${module.revision}"
                    suspend fun resolve() =
                        withTimeout(25_000) {
                            repository.runtime.stream(
                                engine,
                                module.source,
                                module.origin,
                                hit.track.id,
                                module.configuration,
                                hit.track,
                            )
                        }
                    // The runtime evicts the engine after any failure, so one retry
                    // recovers from a cold engine instead of showing an error.
                    val stream =
                        try {
                            resolve()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            trace("first stream attempt failed, retrying: ${describe(error)}")
                            resolve()
                        }
                    val elapsed = System.currentTimeMillis() - started
                    _state.update { it.copy(stream = stream, playingTitle = hit.track.title, playingTrack = hit.track) }
                    recordPlay(module, hit.track)
                    trace("stream ${hit.track.title} -> ${stream.mimeType} / ${elapsed}ms\n${stream.url}")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val detail = describe(error)
                    _state.update { it.copy(error = detail) }
                    trace("stream ${hit.track.title} failed: $detail")
                }
            }
        }

        /** Module tracks are transient media items, so history needs its own table. */
        private suspend fun recordPlay(
            module: InstalledModule,
            track: ModuleTrack,
        ) {
            runCatching {
                database.modulePlayDao.record(
                    ModulePlayEntity(
                        // Store the resolvable `lt:` id, not the upstream id/URL: history entries must
                        // stay playable after the stream URL has expired.
                        mediaId = moe.rukamori.archivetune.modules.ModuleSongStore.songId(module.id, track.id),
                        title = track.title,
                        artists = track.artists.joinToString(", "),
                        album = track.album,
                        durationSeconds = track.durationSeconds,
                        thumbnailUrl = track.thumbnailUrl,
                        moduleId = module.id,
                        playedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { trace("history write failed: ${it.message}") }
        }

        fun onStreamConsumed() = _state.update { it.copy(stream = null) }

        fun clearTrace() = _state.update { it.copy(trace = emptyList()) }

        fun dismissError() = _state.update { it.copy(error = null) }

        private fun targetsFor(current: UiState): List<InstalledModule> =
            current.modules.filter { it.id in current.selectedModuleIds }

        private suspend fun search(
            module: InstalledModule,
            query: String,
            page: Int,
        ): List<ModuleTrack> {
            ModuleRepository.validateConfiguration(module.manifest, module.configuration)
            return repository.runtime.search(
                "${module.id}:${module.revision}",
                module.source,
                module.origin,
                query,
                module.configuration,
                pageSize = PAGE_SIZE,
                page = page,
            )
        }

        /** Playability probe used by the playlist sweep. */
        suspend fun isPlayable(stream: ModuleStream): Boolean = StreamProbe.isPlayable(stream)

        fun reportPlayer(line: String) = trace(line)

        // ModuleException's message is only the failure name; the useful part is the
        // diagnostic code and the underlying cause, which is what a debug trace needs.
        /**
         * Two-part message: a one-line reason a user can act on, then the raw technical
         * detail (kept because it is what makes a bug report actionable).
         */
        private fun describe(error: Throwable): String {
            val module = error as? moe.rukamori.archivetune.modules.ModuleException
            if (module == null) return error.message ?: error::class.java.simpleName
            val code = module.diagnosticCode.orEmpty()
            val reason =
                when {
                    code.contains("NULL_RESULT") || code.contains("EMPTY_RESULT") ->
                        R.string.source_error_no_result
                    code.contains("RESULT_TOO_LARGE") -> R.string.source_error_too_large
                    module.failure == moe.rukamori.archivetune.modules.ModuleFailure.TIMEOUT ->
                        R.string.source_error_timeout
                    module.failure == moe.rukamori.archivetune.modules.ModuleFailure.NETWORK ->
                        R.string.source_error_network
                    module.failure == moe.rukamori.archivetune.modules.ModuleFailure.INVALID_CONFIGURATION ->
                        R.string.source_error_config
                    else -> null
                }
            val detail = buildList {
                add(module.failure.name)
                code.takeIf(String::isNotBlank)?.let(::add)
                module.stage?.let { add(it.name) }
                module.cause?.let { add(it::class.java.simpleName + ": " + (it.message ?: "")) }
            }.joinToString(" · ")
            return reason?.let { context.getString(it) + "\n\n" + detail } ?: detail
        }

        private fun trace(line: String) {
            android.util.Log.d("LTWorkbench", line)
            _state.update { it.copy(trace = (listOf(line) + it.trace).take(20)) }
        }
    }
