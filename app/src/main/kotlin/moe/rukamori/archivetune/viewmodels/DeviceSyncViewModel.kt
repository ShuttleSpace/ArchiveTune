package moe.rukamori.archivetune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.sync.DeviceSync
import moe.rukamori.archivetune.sync.SyncClient
import moe.rukamori.archivetune.sync.SyncDiscovery
import moe.rukamori.archivetune.sync.SyncPeer
import moe.rukamori.archivetune.sync.SyncServer
import moe.rukamori.archivetune.sync.localIpv4Addresses
import javax.inject.Inject

@HiltViewModel
class DeviceSyncViewModel
    @Inject
    constructor(
        private val deviceSync: DeviceSync,
        private val client: SyncClient,
    ) : ViewModel() {
        data class UiState(
            val hosting: Boolean = false,
            val code: String = "",
            val addresses: List<String> = localIpv4Addresses(),
            val busy: Boolean = false,
            val peers: List<SyncPeer> = emptyList(),
            val scanning: Boolean = false,
            val status: String? = null,
            val error: String? = null,
        )

        private val _state = MutableStateFlow(UiState())
        val state = _state.asStateFlow()

        private var server: SyncServer? = null

        fun startHosting() {
            if (server?.isRunning == true) return
            val next =
                SyncServer(
                    onPush = { payload ->
                        deviceSync.apply(payload).also { summary ->
                            _state.update { it.copy(status = "收到数据：$summary") }
                        }
                    },
                )
            runCatching { next.start(viewModelScope) }
                .onSuccess {
                    server = next
                    _state.update {
                        it.copy(
                            hosting = true,
                            code = next.code,
                            addresses = localIpv4Addresses(),
                            error = null,
                        )
                    }
                }.onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "无法开始接收") }
                }
        }

        fun stopHosting() {
            server?.stop()
            server = null
            _state.update { it.copy(hosting = false) }
        }

        fun scan() {
            if (_state.value.scanning) return
            viewModelScope.launch {
                _state.update { it.copy(scanning = true, error = null) }
                val peers = runCatching { SyncDiscovery.scan() }.getOrDefault(emptyList())
                _state.update { it.copy(scanning = false, peers = peers) }
            }
        }

        fun send(
            host: String,
            code: String,
        ) {
            if (host.isBlank() || code.isBlank()) {
                _state.update { it.copy(error = "请填写目标 IP 与配对码") }
                return
            }
            viewModelScope.launch {
                _state.update { it.copy(busy = true, error = null, status = null) }
                val payload =
                    runCatching { deviceSync.snapshot() }
                        .getOrElse { error ->
                            _state.update { it.copy(busy = false, error = error.message) }
                            return@launch
                        }
                runCatching { client.push(host, code, payload) }
                    .onSuccess { summary ->
                        _state.update { it.copy(busy = false, status = summary.ifBlank { "发送完成" }) }
                    }.onFailure { error ->
                        _state.update { it.copy(busy = false, error = error.message ?: "发送失败") }
                    }
            }
        }

        override fun onCleared() {
            server?.stop()
            server = null
        }
    }
