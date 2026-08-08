package com.oneasmr.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.keystore.KeystoreDataStore
import com.oneasmr.app.data.local.settings.BaseUrlIssue
import com.oneasmr.app.data.local.settings.ServerConfig
import com.oneasmr.app.data.local.settings.ServerStore
import com.oneasmr.app.data.local.settings.validateBaseUrl
import com.oneasmr.app.data.remote.ConnectionProbe
import com.oneasmr.app.data.remote.LoginOutcome
import com.oneasmr.app.data.remote.ServerAuthGateway
import com.oneasmr.app.data.remote.TokenValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-server connection state (plan Task 24 state machine). */
sealed interface ServerPhase {
    data object Idle : ServerPhase
    data object Testing : ServerPhase
    data object LoggingIn : ServerPhase

    /** Server reachable AND enforces auth; the login form is shown. */
    data class NeedLogin(val userHint: String?) : ServerPhase

    /** Server reachable and authenticated (auth-off servers connect directly). */
    data class Connected(val authEnabled: Boolean, val user: String) : ServerPhase

    /** Probe/validate/login transport failure — user-facing [message]. */
    data class Failed(val message: String) : ServerPhase
}

data class ServerEntryUi(
    val phase: ServerPhase = ServerPhase.Idle,
    val tokenStored: Boolean = false,
)

data class ServerLoginUiState(
    val servers: List<ServerConfig> = emptyList(),
    val activeServerId: String? = null,
    val entries: Map<String, ServerEntryUi> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
) {
    fun entry(id: String): ServerEntryUi = entries[id] ?: ServerEntryUi()
}

/**
 * Server config + JWT login state machine (plan Task 24).
 *
 * All network work funnels through [ServerAuthGateway]; the UI state is
 * derived purely from its outcomes, so tests drive every transition with a
 * fake gateway + StandardTestDispatcher (zero I/O, zero real-time waits —
 * repo flake convention). Config mutations go through [ServerStore] and the
 * JWT through [KeystoreDataStore] (per-server encrypted slots, never logged).
 */
@HiltViewModel
class ServerLoginViewModel @Inject constructor(
    private val gateway: ServerAuthGateway,
    private val serverStore: ServerStore,
    private val keystore: KeystoreDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerLoginUiState())
    val uiState: StateFlow<ServerLoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(serverStore.servers, serverStore.activeServerId) { servers, active ->
                servers to active
            }.collect { (servers, active) ->
                _uiState.update {
                    it.copy(
                        servers = servers,
                        activeServerId = active,
                        entries = it.entries.filterKeys { id -> servers.any { s -> s.id == id } },
                    )
                }
            }
        }
        refresh()
    }

    /** Reloads configs + stored-token flags, then probes/validates the active server. */
    fun refresh() {
        viewModelScope.launch {
            val servers = serverStore.serversOnce()
            val activeId = serverStore.activeServerId.first()
            _uiState.update {
                it.copy(entries = it.entries + servers.associate { s ->
                    s.id to it.entry(s.id).copy(tokenStored = false)
                })
            }
            // tokenStored flags are keystore reads (suspend, cheap)
            for (s in servers) {
                val has = keystore.serverToken(s.id) != null
                _uiState.update { st ->
                    val entry = st.entry(s.id).copy(tokenStored = has)
                    st.copy(entries = st.entries + (s.id to entry))
                }
            }
            val active = servers.firstOrNull { it.id == activeId } ?: return@launch
            test(active.id, announce = false)
        }
    }

    /** 连接测试: probe -> auth off: Connected; auth on: validate stored token or NeedLogin. */
    fun test(serverId: String, announce: Boolean = true) {
        if (busyFor(serverId)) return
        setPhase(serverId, ServerPhase.Testing)
        viewModelScope.launch {
            val server = serverStore.serversOnce().firstOrNull { it.id == serverId }
            server ?: return@launch
            when (val probe = gateway.probe(server.normalizedBaseUrl)) {
                is ConnectionProbe.Failed -> {
                    setPhase(serverId, ServerPhase.Failed(probe.message))
                    if (announce) message(probe.message)
                }
                is ConnectionProbe.Ok -> {
                    if (probe.authEnabled) {
                        val validation = gateway.validateStoredToken(server)
                        when (validation) {
                            is TokenValidation.Valid -> setPhase(serverId, ServerPhase.Connected(true, validation.user))
                            is TokenValidation.NoToken -> setPhase(serverId, ServerPhase.NeedLogin(probe.user))
                            is TokenValidation.Expired -> {
                                refreshTokenFlag(serverId)
                                setPhase(serverId, ServerPhase.NeedLogin(null))
                                message(validation.message)
                            }
                            is TokenValidation.Unreachable -> {
                                setPhase(serverId, ServerPhase.Failed(validation.message))
                                if (announce) message(validation.message)
                            }
                        }
                    } else {
                        setPhase(serverId, ServerPhase.Connected(false, probe.user))
                    }
                }
            }
        }
    }

    /** name/password -> POST /api/auth/me; success persists the encrypted token. */
    fun login(serverId: String, name: String, password: String) {
        if (busyFor(serverId)) return
        setPhase(serverId, ServerPhase.LoggingIn)
        viewModelScope.launch {
            val server = serverStore.serversOnce().firstOrNull { it.id == serverId } ?: return@launch
            when (val outcome = gateway.login(server, name, password)) {
                is LoginOutcome.Success -> {
                    refreshTokenFlag(serverId)
                    setPhase(serverId, ServerPhase.Connected(true, outcome.user))
                }
                is LoginOutcome.WrongCredentials -> {
                    // Stay on the login form so the user can retry; the banner carries the error.
                    setPhase(serverId, ServerPhase.NeedLogin(null))
                    message(outcome.message)
                }
                is LoginOutcome.Failed -> {
                    setPhase(serverId, ServerPhase.Failed(outcome.message))
                    message(outcome.message)
                }
            }
        }
    }

    fun logout(serverId: String) {
        if (busyFor(serverId)) return
        viewModelScope.launch {
            gateway.clearToken(serverId)
            refreshTokenFlag(serverId)
            setPhase(serverId, ServerPhase.Idle)
        }
    }

    fun setActive(serverId: String) {
        viewModelScope.launch {
            serverStore.setActiveServer(serverId)
            test(serverId)
        }
    }

    fun addServer(name: String, baseUrl: String) {
        val issue = validateBaseUrl(baseUrl)
        if (issue != null) {
            message(baseUrlIssueText(issue))
            return
        }
        if (name.isBlank()) {
            message("服务器名称不能为空")
            return
        }
        viewModelScope.launch {
            val server = serverStore.addServer(name, baseUrl)
            test(server.id)
        }
    }

    fun updateServer(id: String, name: String, baseUrl: String) {
        val issue = validateBaseUrl(baseUrl)
        if (issue != null) {
            message(baseUrlIssueText(issue))
            return
        }
        if (name.isBlank()) {
            message("服务器名称不能为空")
            return
        }
        viewModelScope.launch {
            serverStore.updateServer(id, name, baseUrl)
            message("已保存服务器设置")
        }
    }

    fun removeServer(id: String) {
        viewModelScope.launch {
            gateway.clearToken(id)
            serverStore.removeServer(id)
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun busyFor(serverId: String): Boolean =
        _uiState.value.entry(serverId).phase is ServerPhase.Testing ||
            _uiState.value.entry(serverId).phase is ServerPhase.LoggingIn

    private fun setPhase(serverId: String, phase: ServerPhase) {
        _uiState.update {
            it.copy(entries = it.entries + (serverId to it.entry(serverId).copy(phase = phase)))
        }
    }

    private suspend fun refreshTokenFlag(serverId: String) {
        val has = keystore.serverToken(serverId) != null
        _uiState.update {
            it.copy(entries = it.entries + (serverId to it.entry(serverId).copy(tokenStored = has)))
        }
    }

    private fun message(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    private fun baseUrlIssueText(issue: BaseUrlIssue): String = when (issue) {
        BaseUrlIssue.BLANK -> "服务器地址不能为空"
        BaseUrlIssue.NO_SCHEME -> "地址需以 http:// 或 https:// 开头"
        BaseUrlIssue.NO_HOST -> "地址格式不正确（缺少主机名）"
    }
}
