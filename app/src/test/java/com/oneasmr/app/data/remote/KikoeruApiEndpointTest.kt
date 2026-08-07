package com.oneasmr.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Endpoint coverage for the kikoeru-express client against MockWebServer.
 * Every response is a hand-written fixture in src/test/resources/remote/ that
 * mirrors the wire format of kikoeru-express @ dd030f3 (post-normalize:
 * `circle`/`vas`/`tags` aggregates, INTEGER work ids, UUID va ids).
 *
 * A fresh MockWebServer per test keeps each case deterministic (stale_state
 * guard: no state leaks between cases).
 */
class KikoeruApiEndpointTest {

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

    private fun api(token: String? = null): KikoeruApi = KikoeruApi(
        baseUrl = server.url("/").toString(),
        tokenProvider = { token },
    )

    private fun jsonBody(name: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(RemoteFixtures.json(name))

    private fun takeRequest() = server.takeRequest()

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    @Test
    fun `login posts credentials and returns token`() = runBlocking {
        server.enqueue(jsonBody("auth_login"))

        val response = api().login(name = "admin", password = "password")

        assertEquals(
            "eyJhbGciOiJIUzI1NiJ9.eyJuYW1lIjoiYWRtaW4iLCJncm91cCI6ImFkbWluaXN0cmF0b3IifQ.3K5qZx7F4fQbKfD0a5BdP0n9u8s7S6d5f4g3h2j1k0l",
            response.token,
        )
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/me", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("admin", body["name"]?.jsonPrimitive?.content)
        assertEquals("password", body["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun `currentUser reports auth-enabled server`() = runBlocking {
        server.enqueue(jsonBody("auth_me_auth"))

        val response = api().currentUser()

        assertEquals("admin", response.user.name)
        assertEquals("administrator", response.user.group)
        assertTrue(response.auth)
        assertEquals("/api/auth/me", takeRequest().path)
    }

    @Test
    fun `currentUser reports auth-disabled server`() = runBlocking {
        server.enqueue(jsonBody("auth_me_noauth"))

        val response = api().currentUser()

        assertEquals("admin", response.user.name)
        assertEquals(false, response.auth)
    }

    // ------------------------------------------------------------------
    // Works / search / work
    // ------------------------------------------------------------------

    @Test
    fun `works parses items with aggregates and pagination`() = runBlocking {
        server.enqueue(jsonBody("works"))

        val page = api().works()

        assertEquals(2, page.works.size)
        // INTEGER remote work ids (RJ digits), not local string keys.
        assertEquals(6L, page.works[0].id)
        assertEquals(7L, page.works[1].id)
        val first = page.works[0]
        assertEquals("【CV：かの仔】秘密のバイト体験 ～囁き耳舐めセット～", first.title)
        // normalize() output: circleObj/vaObj/tagObj rewritten into plain objects.
        assertEquals(1L, first.circle?.id)
        assertEquals("かの仔", first.circle?.name)
        assertEquals("かの仔", first.name) // legacy top-level circle name kept
        assertEquals(true, first.nsfw)
        assertEquals("2020-01-31", first.release)
        assertEquals(12345L, first.dl_count)
        assertEquals(1320L, first.price)
        assertEquals(57L, first.review_count)
        assertEquals(4.73, first.rate_average_2dp!!, 0.0001)
        assertEquals(1, first.vas.size)
        assertEquals("b8d65f2d-19f1-4fbf-8a8e-6f6c5d2a1a11", first.vas[0].id)
        assertEquals("かの仔", first.vas[0].name)
        assertEquals(listOf("耳舐め", "バイノーラル"), first.tags.map { it.name })
        assertEquals(null, first.rank)
        assertEquals(null, first.userRating) // no review for this user yet
        assertEquals(null, first.progress)
        assertEquals(false, page.works[1].nsfw)
        // Pagination block.
        assertEquals(1, page.pagination.currentPage)
        assertEquals(12, page.pagination.pageSize)
        assertEquals(57L, page.pagination.totalCount)
        assertEquals("/api/works", takeRequest().path)
    }

    @Test
    fun `works forwards page order sort seed query params`() = runBlocking {
        server.enqueue(jsonBody("works"))

        api().works(page = 2, order = "dl_count", sort = "asc", seed = 7)

        val path = takeRequest().path
        assertTrue(path!!.startsWith("/api/works?"))
        assertTrue(path.contains("page=2"))
        assertTrue(path.contains("order=dl_count"))
        assertTrue(path.contains("sort=asc"))
        assertTrue(path.contains("seed=7"))
    }

    @Test
    fun `search by numeric keyword returns paged results`() = runBlocking {
        server.enqueue(jsonBody("search"))

        val page = api().search(keyword = "RJ123456", page = 1)

        assertEquals(1, page.works.size)
        assertEquals(6L, page.works[0].id)
        assertEquals(1L, page.pagination.totalCount)
        assertEquals("/api/search/RJ123456?page=1", takeRequest().path)
    }

    @Test
    fun `search url-encodes non-ascii keywords`() = runBlocking {
        server.enqueue(jsonBody("search"))

        api().search(keyword = "耳舐め")

        assertEquals("/api/search/%E8%80%B3%E8%88%90%E3%82%81", takeRequest().path)
    }

    @Test
    fun `work detail parses dynamic metadata and review fields`() = runBlocking {
        server.enqueue(jsonBody("work_detail"))

        val work = api().work(id = 6)

        assertEquals(6L, work.id)
        assertEquals("かの仔", work.circle?.name)
        // rate_count_detail / rank arrive as parsed JSON values.
        val detail = work.rate_count_detail!!.jsonObject
        assertEquals(160, detail["5"]?.jsonPrimitive?.content?.toIntOrNull())
        assertEquals(5, detail.size)
        assertTrue(work.rank is JsonObject)
        assertEquals(5, work.userRating)
        assertEquals("listening", work.progress)
        assertEquals("いいね", work.review_text)
        assertEquals("2021-05-02 08-15-22", work.updated_at)
        assertEquals("admin", work.user_name)
        assertEquals("/api/work/6", takeRequest().path)
    }

    // ------------------------------------------------------------------
    // Tracks
    // ------------------------------------------------------------------

    @Test
    fun `tracks parses tree with folder audio text image nodes`() = runBlocking {
        server.enqueue(jsonBody("tracks"))

        val tree = api().tracks(id = 6)

        assertEquals(4, tree.size)
        val folder = tree[0]
        assertEquals(TrackNodeType.FOLDER, folder.type)
        assertEquals("本編", folder.title)
        assertEquals(2, folder.children.size)
        assertEquals(TrackNodeType.AUDIO, folder.children[0].type)
        // hash is "{workId}/{index}" and URLs are server-relative.
        assertEquals("6/0", folder.children[0].hash)
        assertEquals("/api/media/stream/6/0", folder.children[0].mediaStreamUrl)
        assertEquals("/api/media/download/6/0", folder.children[0].mediaDownloadUrl)
        assertEquals("01.mp3", folder.children[0].title)
        assertEquals("【CV：かの仔】秘密のバイト体験", folder.children[0].workTitle)
        assertEquals(TrackNodeType.AUDIO, tree[1].type) // root-level audio (03_sleep.mp3)
        assertEquals(TrackNodeType.TEXT, tree[2].type)
        assertEquals("紹介.txt", tree[2].title)
        assertEquals(TrackNodeType.IMAGE, tree[3].type)
        assertTrue(tree[3].children.isEmpty())
        assertEquals("/api/tracks/6", takeRequest().path)
    }

    // ------------------------------------------------------------------
    // Review
    // ------------------------------------------------------------------

    @Test
    fun `reviews with filter returns reviewed works`() = runBlocking {
        server.enqueue(jsonBody("review_list"))

        val page = api().reviews(filter = ReviewFilter.LISTENING)

        assertEquals(1, page.works.size)
        assertEquals(5, page.works[0].userRating)
        assertEquals("listening", page.works[0].progress)
        assertEquals("いいね", page.works[0].review_text)
        assertEquals("2021-05-02", page.works[0].updated_at)
        assertTrue(takeRequest().path!!.contains("filter=listening"))
    }

    @Test
    fun `putReview sends body and query flags and parses message`() = runBlocking {
        server.enqueue(jsonBody("review_put"))

        val response = api().putReview(
            workId = 6,
            rating = 5,
            progress = "listening",
            reviewText = "いいね",
            starOnly = false,
        )

        assertEquals("评价成功", response.message)
        val request = takeRequest()
        assertEquals("PUT", request.method)
        assertTrue(request.path!!.contains("starOnly=false"))
        assertTrue(request.path!!.contains("progressOnly=false"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("6", body["work_id"]?.jsonPrimitive?.content)
        assertEquals("5", body["rating"]?.jsonPrimitive?.content)
        assertEquals("listening", body["progress"]?.jsonPrimitive?.content)
        assertEquals("いいね", body["review_text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deleteReview sends work_id query and parses message`() = runBlocking {
        server.enqueue(jsonBody("review_delete"))

        val response = api().deleteReview(workId = 6)

        assertEquals("删除标记成功", response.message)
        val request = takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/review?work_id=6", request.path)
    }

    // ------------------------------------------------------------------
    // Health / version
    // ------------------------------------------------------------------

    @Test
    fun `health returns plain OK text`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody("OK"),
        )

        assertEquals("OK", api().health())
        assertEquals("/api/health", takeRequest().path)
    }

    @Test
    fun `version parses update check fields`() = runBlocking {
        server.enqueue(jsonBody("version"))

        val version = api().version()

        assertEquals("3.2.0", version.current)
        assertEquals("3.2.0", version.latest_stable)
        assertEquals("3.2.1", version.latest_release)
        assertEquals(false, version.update_available)
        assertEquals(true, version.notifyUser)
        assertEquals(false, version.lockFileExists)
        assertNull(version.lockReason)
    }

    // ------------------------------------------------------------------
    // Circles / tags / vas
    // ------------------------------------------------------------------

    @Test
    fun `circles list parses labels with counts`() = runBlocking {
        server.enqueue(jsonBody("circles"))

        val labels = api().circles()

        assertEquals(2, labels.size)
        // Numeric circle ids arrive as ints; the DTO coerces them to strings.
        assertEquals("1", labels[0].id)
        assertEquals("かの仔", labels[0].name)
        assertEquals(3L, labels[0].count)
        assertEquals("2", labels[1].id)
        assertEquals("/api/circles", takeRequest().path)
    }

    @Test
    fun `circle by id parses single label`() = runBlocking {
        server.enqueue(jsonBody("circle"))

        val circle = api().circle(id = 1)

        assertEquals("1", circle.id)
        assertEquals("かの仔", circle.name)
        assertEquals(0L, circle.count) // single-label payload has no count
        assertEquals("/api/circles/1", takeRequest().path)
    }

    @Test
    fun `circle works returns paged works`() = runBlocking {
        server.enqueue(jsonBody("circle_works"))

        val page = api().circleWorks(id = 1, page = 1)

        assertEquals(1, page.works.size)
        assertEquals(6L, page.works[0].id)
        assertEquals(3L, page.pagination.totalCount)
        assertEquals("/api/circles/1/works?page=1", takeRequest().path)
    }

    @Test
    fun `tags list parses labels with string ids`() = runBlocking {
        server.enqueue(jsonBody("tags"))

        val labels = api().tags()

        assertEquals("2", labels[0].id)
        assertEquals("耳舐め", labels[0].name)
        assertEquals(5L, labels[0].count)
        assertEquals("/api/tags", takeRequest().path)
    }

    @Test
    fun `va works uses uuid string path`() = runBlocking {
        server.enqueue(jsonBody("va_works"))

        val page = api().vaWorks(id = "b8d65f2d-19f1-4fbf-8a8e-6f6c5d2a1a11")

        assertEquals(1, page.works.size)
        assertEquals(
            "/api/vas/b8d65f2d-19f1-4fbf-8a8e-6f6c5d2a1a11/works",
            takeRequest().path,
        )
    }

    // ------------------------------------------------------------------
    // Cover / check-lrc
    // ------------------------------------------------------------------

    @Test
    fun `cover downloads bytes and coverUrl builds api url`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/jpeg")
                .setBody("fake-jpeg-bytes"),
        )

        val bytes = api().cover(id = 6, type = CoverType.SAM)

        assertEquals("fake-jpeg-bytes", String(bytes))
        assertEquals("/api/cover/6?type=sam", takeRequest().path)
        assertEquals(
            server.url("/api/cover/6?type=sam").toString(),
            api().coverUrl(id = 6, type = CoverType.SAM),
        )
        // Default cover type is "main".
        assertEquals(
            server.url("/api/cover/6?type=main").toString(),
            api().coverUrl(id = 6),
        )
    }

    @Test
    fun `checkLrc found returns lrc hash`() = runBlocking {
        server.enqueue(jsonBody("check_lrc_found"))

        val result = api().checkLrc(workId = 6, index = 3)

        assertEquals(true, result.result)
        assertEquals("找到歌词文件", result.message)
        assertEquals("6/3", result.hash)
        assertEquals("/api/media/check-lrc/6/3", takeRequest().path)
    }

    @Test
    fun `checkLrc missing returns false`() = runBlocking {
        server.enqueue(jsonBody("check_lrc_missing"))

        val result = api().checkLrc(workId = 6, index = 3)

        assertEquals(false, result.result)
        assertEquals("不存在歌词文件", result.message)
        assertEquals("", result.hash)
    }

    // ------------------------------------------------------------------
    // Adversarial robustness
    // ------------------------------------------------------------------

    @Test
    fun `unknown json keys are ignored`() = runBlocking {
        // A future kikoeru release adds keys; the client must not break.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(
                    """{"works":[{"id":6,"title":"t","circle_id":1,"name":"c",""" +
                        """"circle":{"id":1,"name":"c"},"nsfw":false,"release":"2020-01-01",""" +
                        """"vas":[],"tags":[],"new_future_field":{"a":1},""" +
                        """"userRating":null,"review_text":null,"progress":null,"updated_at":null,"user_name":null}],""" +
                        """"pagination":{"currentPage":1,"pageSize":12,"totalCount":1},"new_page_field":42}""",
                ),
        )

        val page = api().works()

        assertEquals(1, page.works.size)
        assertEquals(6L, page.works[0].id)
        assertEquals(1L, page.pagination.totalCount)
    }

    @Test
    fun `unknown track node type degrades to other`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(
                    """[{"type":"video","hash":"6/0","title":"clip.mp4","workTitle":"t",""" +
                        """"mediaStreamUrl":"/api/media/stream/6/0","mediaDownloadUrl":"/api/media/download/6/0"}]""",
                ),
        )

        val tree = api().tracks(id = 6)

        assertEquals(1, tree.size)
        assertEquals(TrackNodeType.OTHER, tree[0].type)
        assertEquals("clip.mp4", tree[0].title)
    }

    // ------------------------------------------------------------------
    // Auth header
    // ------------------------------------------------------------------

    @Test
    fun `bearer token attached when token provider returns one`() = runBlocking {
        server.enqueue(jsonBody("works"))

        api(token = "jwt.header.payload.signature").works()

        assertEquals(
            "Bearer jwt.header.payload.signature",
            takeRequest().getHeader("Authorization"),
        )
    }

    @Test
    fun `no authorization header when no token`() = runBlocking {
        server.enqueue(jsonBody("works"))

        api().works()

        assertNull(takeRequest().getHeader("Authorization"))
    }
}
