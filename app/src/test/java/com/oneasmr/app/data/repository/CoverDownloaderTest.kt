package com.oneasmr.app.data.repository

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Wire-level tests for [OkHttpCoverDownloader] (plan Task 10): 200 → file
 * written, 404 → NOT_FOUND (placeholder path), 5xx/IO → FAILED, browser UA +
 * work-page Referer headers on every request.
 */
class CoverDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    @Test
    fun `200 writes the body into the target file`() = runBlocking {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
        val target = File(tmp.newFolder("ok"), "out.jpg")

        val result = OkHttpCoverDownloader().download(server.url("/c.jpg").toString(), "http://ref", target)

        assertEquals(CoverDownloadResult.OK, result)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun `404 maps to NOT_FOUND and leaves no file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val target = File(tmp.newFolder("nf"), "out.jpg")

        val result = OkHttpCoverDownloader().download(server.url("/c.jpg").toString(), null, target)

        assertEquals(CoverDownloadResult.NOT_FOUND, result)
        assertFalse(target.exists())
    }

    @Test
    fun `5xx maps to FAILED and leaves no file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val target = File(tmp.newFolder("err"), "out.jpg")

        val result = OkHttpCoverDownloader().download(server.url("/c.jpg").toString(), null, target)

        assertEquals(CoverDownloadResult.FAILED, result)
        assertFalse(target.exists())
    }

    @Test
    fun `unreachable host maps to FAILED without throwing`() = runBlocking {
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/c.jpg").toString()
        dead.shutdown()

        val result = OkHttpCoverDownloader().download(deadUrl, null, File(tmp.newFolder("io"), "out.jpg"))

        assertEquals(CoverDownloadResult.FAILED, result)
    }

    @Test
    fun `sends browser UA and referer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(1))))
        OkHttpCoverDownloader().download(server.url("/c.jpg").toString(), "https://www.dlsite.com/maniax/work/=/product_id/RJ123456.html", File(tmp.newFolder("hdr"), "o.jpg"))

        val req = server.takeRequest()
        assertTrue(req.getHeader("User-Agent")!!.contains("Mozilla"))
        assertEquals("https://www.dlsite.com/maniax/work/=/product_id/RJ123456.html", req.getHeader("Referer"))
    }
}
