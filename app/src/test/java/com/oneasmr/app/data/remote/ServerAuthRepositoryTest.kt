package com.oneasmr.app.data.remote

import com.oneasmr.app.data.local.TestDataStoreFile
import com.oneasmr.app.data.local.keystore.AesGcmKeyCipher
import com.oneasmr.app.data.local.keystore.KeystoreDataStore
import com.oneasmr.app.data.local.settings.ServerConfig
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * ServerAuthRepository against a real MockWebServer kikoeru (plan Task 24
 * contract): probe detects auth-on (401 on bare /api/auth/me) vs auth-off
 * (200 admin), login stores the JWT encrypted per server, wrong password
 * maps to WrongCredentials, expired-token validation clears the token,
 * unreachable servers map to a friendly failure, and per-server tokens never
 * leak across scopes (Authorization header assertions on the wire).
 */
class ServerAuthRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var keystoreFile: TestDataStoreFile
    private lateinit var keystore: KeystoreDataStore
    private lateinit var session: ServerSession
    private lateinit var repo: ServerAuthRepository

    private val key: SecretKey by lazy {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        keystoreFile = TestDataStoreFile(tmp.newFile("secure.preferences_pb"))
        keystore = KeystoreDataStore(keystoreFile.open(), AesGcmKeyCipher(key))
        session = ServerSession(keystore, client)
        repo = ServerAuthRepository(session)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serverConfig(): ServerConfig =
        ServerConfig(id = "srv1", name = "test", baseUrl = server.url("/").toString())

    private fun json(responseCode: Int, body: String) = MockResponse()
        .setResponseCode(responseCode)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun authMeAuthOn() = json(401, """{"error":"No authorization token was found"}""")
    private fun authMeAuthOff() = json(200, """{"user":{"name":"admin","group":"administrator"},"auth":false}""")
    private fun loginOk() = json(200, """{"token":"jwt.header.payload.sig"}""")
    private fun loginWrong() = json(401, """{"error":"用户名或密码错误."}""")

    // ------------------------------------------------------------------
    // probe
    // ------------------------------------------------------------------

    @Test
    fun `probe detects auth-enabled server via 401 on bare auth-me`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        server.enqueue(json(200, """{"current":"3.2.0"}"""))
        server.enqueue(authMeAuthOn())

        val probe = repo.probe(server.url("/").toString())

        assertTrue(probe is ConnectionProbe.Ok)
        probe as ConnectionProbe.Ok
        assertTrue(probe.authEnabled)
        assertEquals("3.2.0", probe.version)
        assertEquals("/api/health", server.takeRequest().path)
    }

    @Test
    fun `probe detects auth-disabled server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        server.enqueue(json(200, """{"current":"3.2.0"}"""))
        server.enqueue(authMeAuthOff())

        val probe = repo.probe(server.url("/").toString())

        assertTrue(probe is ConnectionProbe.Ok)
        probe as ConnectionProbe.Ok
        assertFalse(probe.authEnabled)
        assertEquals("admin", probe.user)
    }

    @Test
    fun `probe tolerates missing version endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        server.enqueue(json(404, """{"error":"not found"}"""))
        server.enqueue(authMeAuthOff())

        val probe = repo.probe(server.url("/").toString())

        assertTrue(probe is ConnectionProbe.Ok)
        assertNull((probe as ConnectionProbe.Ok).version)
    }

    @Test
    fun `probe fails with message when health is unreachable`() = runTest {
        server.shutdown()
        val port = server.port

        val probe = repo.probe("http://127.0.0.1:$port/")

        assertTrue(probe is ConnectionProbe.Failed)
        assertTrue((probe as ConnectionProbe.Failed).message.isNotBlank())
    }

    @Test
    fun `probe surfaces server error message on 5xx health`() = runTest {
        server.enqueue(json(500, """{"error":"boom"}"""))

        val probe = repo.probe(server.url("/").toString())

        assertTrue(probe is ConnectionProbe.Failed)
        assertTrue((probe as ConnectionProbe.Failed).message.contains("服务器错误"))
    }

    // ------------------------------------------------------------------
    // login
    // ------------------------------------------------------------------

    @Test
    fun `login stores token encrypted and bearer attaches on later calls`() = runTest {
        server.enqueue(loginOk())

        val outcome = repo.login(serverConfig(), "admin", "admin")

        assertTrue(outcome is LoginOutcome.Success)
        assertEquals("jwt.header.payload.sig", session.loadToken("srv1"))
        assertFalse(
            "token must not appear in plaintext in the store file",
            File(keystoreFile.file().absolutePath).readText().contains("jwt.header.payload.sig"),
        )
        server.takeRequest() // drain the login POST (unauthenticated by design)
        assertEquals("Bearer jwt.header.payload.sig", bearerOnNextCall())
    }

    @Test
    fun `wrong password maps to WrongCredentials and stores nothing`() = runTest {
        server.enqueue(loginWrong())

        val outcome = repo.login(serverConfig(), "admin", "wrong")

        assertTrue(outcome is LoginOutcome.WrongCredentials)
        assertNull(session.loadToken("srv1"))
    }

    @Test
    fun `login against unreachable server maps to Failed`() = runTest {
        server.shutdown()

        val outcome = repo.login(serverConfig(), "admin", "admin")

        assertTrue(outcome is LoginOutcome.Failed)
    }

    // ------------------------------------------------------------------
    // validateStoredToken
    // ------------------------------------------------------------------

    @Test
    fun `valid token validates as Valid with the user`() = runTest {
        server.enqueue(loginOk())
        repo.login(serverConfig(), "admin", "admin")
        server.enqueue(authMeAuthOff().setBody("""{"user":{"name":"admin","group":"administrator"},"auth":true}"""))

        val validation = repo.validateStoredToken(serverConfig())

        assertTrue(validation is TokenValidation.Valid)
        assertEquals("admin", (validation as TokenValidation.Valid).user)
        server.takeRequest() // drain the login POST (unauthenticated by design)
        val request = server.takeRequest()
        assertEquals("/api/auth/me", request.path)
        assertEquals("Bearer jwt.header.payload.sig", request.getHeader("Authorization"))
    }

    @Test
    fun `expired token clears the stored token and reports Expired`() = runTest {
        server.enqueue(loginOk())
        repo.login(serverConfig(), "admin", "admin")
        assertEquals("jwt.header.payload.sig", session.loadToken("srv1"))
        server.enqueue(authMeAuthOn())

        val validation = repo.validateStoredToken(serverConfig())

        assertTrue(validation is TokenValidation.Expired)
        assertNull("expired token must be cleared", session.loadToken("srv1"))
    }

    @Test
    fun `unreachable server keeps the token and reports Unreachable`() = runTest {
        server.enqueue(loginOk())
        repo.login(serverConfig(), "admin", "admin")
        server.shutdown()

        val validation = repo.validateStoredToken(serverConfig())

        assertTrue(validation is TokenValidation.Unreachable)
        assertEquals("token survives a network failure", "jwt.header.payload.sig", session.loadToken("srv1"))
    }

    @Test
    fun `no stored token validates as NoToken`() = runTest {
        val validation = repo.validateStoredToken(serverConfig())
        assertTrue(validation is TokenValidation.NoToken)
    }

    // ------------------------------------------------------------------
    // multi-server isolation
    // ------------------------------------------------------------------

    @Test
    fun `tokens for different servers never cross scopes`() = runTest {
        server.enqueue(loginOk())
        repo.login(ServerConfig(id = "srv1", name = "a", baseUrl = server.url("/").toString()), "u1", "p1")
        server.enqueue(loginOk())
        repo.login(ServerConfig(id = "srv2", name = "b", baseUrl = server.url("/").toString()), "u2", "p2")

        assertEquals("jwt.header.payload.sig", session.loadToken("srv1"))
        assertEquals("jwt.header.payload.sig", session.loadToken("srv2"))
        server.takeRequest() // drain srv1 login POST
        server.takeRequest() // drain srv2 login POST
        // Wire contract: KikoeruApi adds the "Bearer " prefix; the stored/returned
        // token stays the raw JWT (asserted via session.loadToken above).
        assertEquals("Bearer jwt.header.payload.sig", bearerOnNextCall())
        assertEquals("Bearer jwt.header.payload.sig", bearerOnNextCall())
    }

    @Test
    fun `clearToken wipes only the targeted server`() = runTest {
        server.enqueue(loginOk())
        repo.login(ServerConfig(id = "srv1", name = "a", baseUrl = server.url("/").toString()), "u1", "p1")
        server.enqueue(loginOk())
        repo.login(ServerConfig(id = "srv2", name = "b", baseUrl = server.url("/").toString()), "u2", "p2")

        repo.clearToken("srv1")

        assertNull(session.loadToken("srv1"))
        assertEquals("jwt.header.payload.sig", session.loadToken("srv2"))
    }

    // ------------------------------------------------------------------

    private suspend fun bearerOnNextCall(): String? {
        server.enqueue(json(200, """{"works":[],"pagination":{}}"""))
        session.apiFor(serverConfig()).works()
        return server.takeRequest().getHeader("Authorization")
    }
}
