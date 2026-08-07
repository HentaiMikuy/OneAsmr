package com.oneasmr.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Locks the unified error mapping table:
 *   401 -> AuthExpired | 404/other -> HttpError | 5xx -> Server
 *   malformed 2xx body -> Parse | transport -> Network | cancellation rethrown.
 * Both halves are covered: the pure [KikoeruErrorMapper] table (no network) and
 * end-to-end cases where MockWebServer actually serves the bad status/body.
 */
class KikoeruErrorMappingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): KikoeruApi = KikoeruApi(baseUrl = server.url("/").toString())

    // ------------------------------------------------------------------
    // Pure mapper table (no network) — deterministic by construction.
    // ------------------------------------------------------------------

    private fun httpException(code: Int): HttpException =
        HttpException(
            Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())),
        )

    @Test
    fun `mapper table 401 to AuthExpired`() {
        assertTrue(KikoeruErrorMapper.map(httpException(401)) is KikoeruException.AuthExpired)
    }

    @Test
    fun `mapper table 404 to HttpError with code`() {
        val mapped = KikoeruErrorMapper.map(httpException(404)) as KikoeruException.HttpError
        assertEquals(404, mapped.code)
    }

    @Test
    fun `mapper table 422 to HttpError with code`() {
        val mapped = KikoeruErrorMapper.map(httpException(422)) as KikoeruException.HttpError
        assertEquals(422, mapped.code)
    }

    @Test
    fun `mapper table 500 and 503 to Server with code`() {
        assertEquals(500, (KikoeruErrorMapper.map(httpException(500)) as KikoeruException.Server).code)
        assertEquals(503, (KikoeruErrorMapper.map(httpException(503)) as KikoeruException.Server).code)
    }

    @Test
    fun `mapper table malformed body to Parse`() {
        // Produce a REAL JsonDecodingException via an actual failed parse
        // (the JsonDecodingException constructor is deprecated in 1.11.0).
        val malformed = try {
            Json.parseToJsonElement("not json")
            error("expected parse failure")
        } catch (e: SerializationException) {
            e
        }
        assertTrue(KikoeruErrorMapper.map(malformed) is KikoeruException.Parse)
    }

    @Test
    fun `mapper table io error to Network`() {
        val mapped = KikoeruErrorMapper.map(IOException("connection refused"))
        assertTrue(mapped is KikoeruException.Network)
    }

    @Test
    fun `mapper table rethrows cancellation unchanged`() {
        assertThrows(CancellationException::class.java) {
            KikoeruErrorMapper.map(CancellationException("stop"))
        }
    }

    @Test
    fun `mapper table passes through existing kikoeru exception`() {
        val original = KikoeruException.AuthExpired()
        assertTrue(KikoeruErrorMapper.map(original) === original)
    }

    // ------------------------------------------------------------------
    // End-to-end mapping through the real client + MockWebServer.
    // ------------------------------------------------------------------

    @Test
    fun `end to end 401 maps to AuthExpired`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"error":"用户名或密码错误."}"""),
        )
        runBlocking {
            val thrown = runCatching { api().works() }.exceptionOrNull()
            assertTrue(thrown is KikoeruException.AuthExpired)
        }
    }

    @Test
    fun `end to end 404 maps to HttpError with code`() {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"error":"社团6不存在"}"""),
        )
        runBlocking {
            val thrown = runCatching { api().circle(id = 6) }.exceptionOrNull() as KikoeruException.HttpError
            assertEquals(404, thrown.code)
        }
    }

    @Test
    fun `end to end 500 maps to Server with code`() {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"error":"服务器错误"}"""),
        )
        runBlocking {
            val thrown = runCatching { api().works() }.exceptionOrNull() as KikoeruException.Server
            assertEquals(500, thrown.code)
        }
    }

    @Test
    fun `end to end malformed json maps to Parse`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("this is not json at all"),
        )
        runBlocking {
            val thrown = runCatching { api().works() }.exceptionOrNull()
            assertTrue(thrown is KikoeruException.Parse)
        }
    }

    @Test
    fun `end to end json of wrong shape maps to Parse`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("[]"),
        )
        runBlocking {
            val thrown = runCatching { api().works() }.exceptionOrNull()
            assertTrue(thrown is KikoeruException.Parse)
        }
    }

    @Test
    fun `end to end connection refused maps to Network`() {
        // Server on a port that is immediately closed again: deterministic
        // connection-refused, no DNS involved.
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/").toString()
        dead.shutdown()

        val deadApi = KikoeruApi(baseUrl = deadUrl)
        runBlocking {
            val thrown = runCatching { deadApi.works() }.exceptionOrNull()
            assertTrue(thrown is KikoeruException.Network)
        }
    }
}
