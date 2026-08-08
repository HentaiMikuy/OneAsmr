package com.oneasmr.app.ui.settings

import com.oneasmr.app.data.local.TestDataStoreFile
import com.oneasmr.app.data.local.keystore.AesGcmKeyCipher
import com.oneasmr.app.data.local.keystore.KeystoreDataStore
import com.oneasmr.app.data.local.settings.ServerConfig
import com.oneasmr.app.data.local.settings.ServerStore
import com.oneasmr.app.data.remote.ConnectionProbe
import com.oneasmr.app.data.remote.LoginOutcome
import com.oneasmr.app.data.remote.ServerAuthGateway
import com.oneasmr.app.data.remote.TokenValidation
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ServerLoginViewModel state machine (plan Task 24): config add/edit/switch,
 * probe-driven login flow, token store round-trip, expiry re-login prompt and
 * the 401/unreachable failure paths. All network I/O is faked via
 * [FakeGateway]; configs/tokens are REAL stores over temp files. The test
 * dispatcher (StandardTestDispatcher) makes every transition deterministic —
 * no real-time waits (repo flake convention).
 */
class ServerLoginViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var serverStore: ServerStore
    private lateinit var serverStoreFile: TestDataStoreFile
    private lateinit var keystore: KeystoreDataStore
    private lateinit var keystoreFile: TestDataStoreFile
    private lateinit var gateway: FakeGateway
    private lateinit var viewModel: ServerLoginViewModel

    private val key: SecretKey by lazy {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private class FakeGateway : ServerAuthGateway {
        var probeResult: (String) -> ConnectionProbe = { ConnectionProbe.Ok(false, "admin", null) }
        var loginResult: (ServerConfig, String, String) -> LoginOutcome =
            { _, name, _ -> LoginOutcome.Success(name) }
        var validateResult: (ServerConfig) -> TokenValidation = { TokenValidation.NoToken }
        val probed = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        var keystore: KeystoreDataStore? = null

        override suspend fun probe(baseUrl: String): ConnectionProbe {
            probed += baseUrl
            return probeResult(baseUrl)
        }

        override suspend fun login(server: ServerConfig, name: String, password: String): LoginOutcome {
            val result = loginResult(server, name, password)
            if (result is LoginOutcome.Success) {
                keystore?.saveServerToken(server.id, "jwt.$name.token")
            }
            return result
        }

        override suspend fun validateStoredToken(server: ServerConfig): TokenValidation {
            val result = validateResult(server)
            if (result is TokenValidation.Expired) keystore?.removeServerToken(server.id)
            return result
        }

        override suspend fun clearToken(serverId: String) {
            cleared += serverId
            keystore?.removeServerToken(serverId)
        }
    }

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        serverStoreFile = TestDataStoreFile(tmp.newFile("server_config.preferences_pb"))
        serverStore = ServerStore(serverStoreFile.open())
        keystoreFile = TestDataStoreFile(tmp.newFile("secure.preferences_pb"))
        keystore = KeystoreDataStore(keystoreFile.open(), AesGcmKeyCipher(key))
        gateway = FakeGateway().apply { keystore = this@ServerLoginViewModelTest.keystore }
        viewModel = ServerLoginViewModel(gateway, serverStore, keystore)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        serverStoreFile.restart()
        keystoreFile.restart()
        Dispatchers.resetMain()
    }

    private suspend fun awaitState(predicate: (ServerLoginUiState) -> Boolean): ServerLoginUiState =
        viewModel.uiState.first { predicate(it) }

    private suspend fun seedServer(id: String = "srv1", name: String = "NAS", baseUrl: String = "http://10.0.2.2:9527") =
        serverStore.addServer(name, baseUrl).also { check(it.id == id) }

    // ---- config CRUD ------------------------------------------------------

    @Test
    fun `add server persists config and probes it`() = runTest(scheduler) {
        viewModel.addServer("NAS", "http://10.0.2.2:9527")

        val state = awaitState { it.servers.size == 1 && it.entry("srv1").phase is ServerPhase.Connected }
        assertEquals("srv1", state.servers[0].id)
        assertEquals("NAS", state.servers[0].name)
        assertEquals("srv1", state.activeServerId)
        assertTrue(gateway.probed.contains("http://10.0.2.2:9527"))
        assertTrue(state.entry("srv1").phase is ServerPhase.Connected)
    }

    @Test
    fun `add server rejects invalid base url with a message`() = runTest(scheduler) {
        viewModel.addServer("NAS", "192.168.1.5:9527")

        val state = awaitState { it.message != null }
        assertTrue(state.message!!.contains("http"))
        assertTrue(state.servers.isEmpty())
    }

    @Test
    fun `edit server rewrites config keeping the id`() = runTest(scheduler) {
        seedServer()
        viewModel.updateServer("srv1", "NAS2", "http://10.0.2.2:9528")

        val state = awaitState { it.servers.firstOrNull()?.name == "NAS2" }
        assertEquals("srv1", state.servers[0].id)
        assertEquals("http://10.0.2.2:9528", state.servers[0].baseUrl)
    }

    @Test
    fun `remove server clears config and token`() = runTest(scheduler) {
        seedServer()
        gateway.validateResult = { TokenValidation.Valid("admin") }
        keystore.saveServerToken("srv1", "jwt.admin.token")
        viewModel.refresh()
        awaitState { (it.entry("srv1").phase as? ServerPhase.Connected) != null }

        viewModel.removeServer("srv1")

        val state = awaitState { it.servers.isEmpty() }
        assertTrue(state.activeServerId == null)
        assertTrue(gateway.cleared.contains("srv1"))
        assertNull(keystore.serverToken("srv1"))
    }

    @Test
    fun `switch active server probes the newly selected one`() = runTest(scheduler) {
        seedServer("srv1", "A", "http://a:1")
        seedServer("srv2", "B", "http://b:2")
        gateway.probeResult = { url -> if (url == "http://b:2") ConnectionProbe.Ok(false, "admin", null) else ConnectionProbe.Failed("no") }

        viewModel.setActive("srv2")

        // Await the probe transition itself: only http://b:2 probes reach Connected.
        val state = awaitState { it.entry("srv2").phase is ServerPhase.Connected }
        assertEquals("srv2", state.activeServerId)
        assertTrue(state.entry("srv2").phase is ServerPhase.Connected)
        assertTrue(gateway.probed.contains("http://b:2"))
        assertFalse("srv1 must not be probed by the switch", gateway.probed.contains("http://a:1"))
    }

    // ---- login state machine ----------------------------------------------

    @Test
    fun `auth-off server connects without login`() = runTest(scheduler) {
        seedServer()
        gateway.probeResult = { ConnectionProbe.Ok(false, "admin", "3.2.0") }

        viewModel.test("srv1")

        val state = awaitState { it.entry("srv1").phase is ServerPhase.Connected }
        val phase = state.entry("srv1").phase as ServerPhase.Connected
        assertFalse(phase.authEnabled)
        assertEquals("admin", phase.user)
        assertNull(keystore.serverToken("srv1"))
    }

    @Test
    fun `auth-on server without token shows login form`() = runTest(scheduler) {
        seedServer()
        gateway.probeResult = { ConnectionProbe.Ok(true, "", "3.2.0") }

        viewModel.test("srv1")

        val state = awaitState { it.entry("srv1").phase is ServerPhase.NeedLogin }
        assertTrue(state.entry("srv1").phase is ServerPhase.NeedLogin)
    }

    @Test
    fun `login success persists encrypted token and connects`() = runTest(scheduler) {
        seedServer()
        gateway.probeResult = { ConnectionProbe.Ok(true, "", "3.2.0") }
        viewModel.test("srv1")
        awaitState { it.entry("srv1").phase is ServerPhase.NeedLogin }

        viewModel.login("srv1", "admin", "admin")

        val state = awaitState { it.entry("srv1").phase is ServerPhase.Connected }
        assertEquals("admin", (state.entry("srv1").phase as ServerPhase.Connected).user)
        assertEquals("jwt.admin.token", keystore.serverToken("srv1"))
        assertTrue(state.entry("srv1").tokenStored)
        assertFalse(
            "token must not leak into the store file in plaintext",
            keystoreFile.file().readText().contains("jwt.admin.token"),
        )
    }

    @Test
    fun `wrong password keeps login form and shows clear error`() = runTest(scheduler) {
        seedServer()
        gateway.probeResult = { ConnectionProbe.Ok(true, "", "3.2.0") }
        viewModel.test("srv1")
        awaitState { it.entry("srv1").phase is ServerPhase.NeedLogin }
        gateway.loginResult = { _, _, _ -> LoginOutcome.WrongCredentials("用户名或密码错误（401）") }

        viewModel.login("srv1", "admin", "wrong")

        val state = awaitState { it.message != null }
        assertTrue(state.message!!.contains("用户名或密码错误"))
        assertTrue(state.entry("srv1").phase is ServerPhase.NeedLogin)
        assertNull(keystore.serverToken("srv1"))
    }

    @Test
    fun `unreachable server shows failed phase with clear error`() = runTest(scheduler) {
        seedServer()
        gateway.probeResult = { ConnectionProbe.Failed("无法连接服务器（网络错误）") }

        viewModel.test("srv1")

        val state = awaitState { it.entry("srv1").phase is ServerPhase.Failed }
        assertEquals("无法连接服务器（网络错误）", (state.entry("srv1").phase as ServerPhase.Failed).message)
        assertEquals(state.message, "无法连接服务器（网络错误）")
    }

    @Test
    fun `stored token validates on refresh and stays connected`() = runTest(scheduler) {
        seedServer()
        gateway.probeResult = { ConnectionProbe.Ok(true, "", "3.2.0") }
        gateway.validateResult = { TokenValidation.Valid("admin") }
        keystore.saveServerToken("srv1", "jwt.admin.token")

        viewModel.refresh()

        val state = awaitState { it.entry("srv1").phase is ServerPhase.Connected }
        assertTrue(state.entry("srv1").tokenStored)
        assertEquals("jwt.admin.token", keystore.serverToken("srv1"))
    }

    @Test
    fun `expired token clears storage and prompts re-login`() = runTest(scheduler) {
        seedServer()
        gateway.probeResult = { ConnectionProbe.Ok(true, "", "3.2.0") }
        gateway.validateResult = { TokenValidation.Expired("登录已过期，请重新登录") }
        keystore.saveServerToken("srv1", "expired.jwt")

        viewModel.refresh()

        val state = awaitState { it.entry("srv1").phase is ServerPhase.NeedLogin }
        assertTrue(state.message!!.contains("登录已过期"))
        assertNull("expired token must be wiped", keystore.serverToken("srv1"))
        assertFalse(state.entry("srv1").tokenStored)
    }

    @Test
    fun `logout clears the token and returns to idle`() = runTest(scheduler) {
        seedServer()
        gateway.probeResult = { ConnectionProbe.Ok(true, "", "3.2.0") }
        gateway.validateResult = { TokenValidation.Valid("admin") }
        keystore.saveServerToken("srv1", "jwt.admin.token")
        viewModel.refresh()
        awaitState { it.entry("srv1").phase is ServerPhase.Connected }

        viewModel.logout("srv1")

        val state = awaitState { it.entry("srv1").phase is ServerPhase.Idle }
        assertNull(keystore.serverToken("srv1"))
        assertFalse(state.entry("srv1").tokenStored)
    }

    @Test
    fun `two servers keep independent tokens`() = runTest(scheduler) {
        seedServer("srv1", "A", "http://a:1")
        seedServer("srv2", "B", "http://b:2")
        gateway.probeResult = { ConnectionProbe.Ok(true, "", null) }
        gateway.loginResult = { _, name, _ -> LoginOutcome.Success(name) }
        viewModel.test("srv1")
        awaitState { it.entry("srv1").phase is ServerPhase.NeedLogin }
        viewModel.login("srv1", "u1", "p1")
        awaitState { it.entry("srv1").phase is ServerPhase.Connected }
        viewModel.test("srv2")
        awaitState { it.entry("srv2").phase is ServerPhase.NeedLogin }
        viewModel.login("srv2", "u2", "p2")
        awaitState { it.entry("srv2").phase is ServerPhase.Connected }

        assertEquals("jwt.u1.token", keystore.serverToken("srv1"))
        assertEquals("jwt.u2.token", keystore.serverToken("srv2"))
    }
}
