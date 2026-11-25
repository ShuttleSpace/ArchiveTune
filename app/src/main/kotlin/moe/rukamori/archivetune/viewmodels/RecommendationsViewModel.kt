package moe.rukamori.archivetune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.recommend.ModuleRecommendation
import moe.rukamori.archivetune.recommend.ModuleRecommendations
import moe.rukamori.archivetune.recommend.ModuleTrackPlayer
import javax.inject.Inject

@HiltViewModel
class RecommendationsViewModel
    @Inject
    constructor(
        private val recommendations: ModuleRecommendations,
        private val player: ModuleTrackPlayer,
    ) : ViewModel() {
        private val _daily = MutableStateFlow<List<ModuleRecommendation>>(emptyList())
        val daily = _daily.asStateFlow()

        private val _loading = MutableStateFlow(false)
        val loading = _loading.asStateFlow()

        private val _playingId = MutableStateFlow<String?>(null)
        val playingId = _playingId.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                _loading.value = true
                _daily.value = runCatching { recommendations.daily() }.getOrDefault(emptyList())
                _loading.value = false
            }
        }

        fun play(
            item: ModuleRecommendation,
            connection: PlayerConnection,
        ) {
            viewModelScope.launch {
                _playingId.value = item.track.id
                runCatching { player.play(connection, item.module, item.track) }
                _playingId.value = null
            }
        }
    }

@HiltViewModel
class SimilarTracksViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val recommendations: ModuleRecommendations,
        private val player: ModuleTrackPlayer,
    ) : ViewModel() {
        data class UiState(
            val loading: Boolean = true,
            val items: List<ModuleRecommendation> = emptyList(),
            val playingId: String? = null,
        )

        private val mediaId: String = savedStateHandle.get<String>("mediaId").orEmpty()
        private val artist: String = savedStateHandle.get<String>("artist").orEmpty()
        val title: String = savedStateHandle.get<String>("title").orEmpty()

        private val _state = MutableStateFlow(UiState())
        val state = _state.asStateFlow()

        init {
            load()
        }

        fun load() {
            viewModelScope.launch {
                _state.value = _state.value.copy(loading = true)
                val items = runCatching { recommendations.similar(mediaId, artist) }.getOrDefault(emptyList())
                _state.value = UiState(loading = false, items = items)
            }
        }

        fun play(
            item: ModuleRecommendation,
            connection: PlayerConnection,
        ) {
            viewModelScope.launch {
                _state.value = _state.value.copy(playingId = item.track.id)
                runCatching { player.play(connection, item.module, item.track) }
                _state.value = _state.value.copy(playingId = null)
            }
        }
    }
