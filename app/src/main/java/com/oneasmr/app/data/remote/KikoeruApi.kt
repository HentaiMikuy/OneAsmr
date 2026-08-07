package com.oneasmr.app.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * kikoeru-express API client (Task 23).
 *
 * Wire contract mirrors kikoeru-express at pinned commit
 * dd030f3e989b54d4f97ee73ff08aa662c6709694 (routes + database/db.js
 * staticMetadata view, post-normalize response shapes — see [KikoeruDtos]).
 *
 * [baseUrl] is the SERVER ROOT (scheme://host[:port]) and is always injected —
 * no server address is hardcoded here; the client appends `/api` itself
 * (Task 26 streams at `{baseUrl}/api/media/stream/...`, Task 24 stores the
 * server root). A baseUrl that already ends in `/api` is tolerated.
 *
 * Auth: when [tokenProvider] returns a token, every request carries
 * `Authorization: Bearer <token>`. The streaming `?token=` query variant is
 * Task 26's job — nothing here wires Media3.
 *
 * All failures are mapped to [KikoeruException] variants by [KikoeruErrorMapper]
 * (401 -> AuthExpired, 5xx -> Server, other HTTP -> HttpError, malformed body
 * -> Parse, transport -> Network; cancellation is rethrown).
 */
class KikoeruApi(
    baseUrl: String,
    private val tokenProvider: () -> String? = { null },
    client: OkHttpClient = OkHttpClient(),
    json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Server root + "/api/" — e.g. "http://host:9527/api/". */
    private val apiBaseUrl: String = buildString {
        val root = baseUrl.trimEnd('/')
        append(if (root.endsWith("/api")) root else "$root/api")
        append('/')
    }

    private val service: KikoeruService = Retrofit.Builder()
        .baseUrl(apiBaseUrl)
        .client(
            client.newBuilder()
                .addInterceptor { chain ->
                    val token = tokenProvider()
                    val request = if (token.isNullOrBlank()) {
                        chain.request()
                    } else {
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                    }
                    chain.proceed(request)
                }
                .build(),
        )
        .addConverterFactory(
            json.asConverterFactory("application/json".toMediaType()),
        )
        .build()
        .create(KikoeruService::class.java)

    // ------------------------------------------------------------------
    // Auth (routes/auth.js)
    // ------------------------------------------------------------------

    /** POST /api/auth/me — returns the JWT for the given credentials. */
    suspend fun login(name: String, password: String): LoginResponse =
        call { service.login(LoginRequest(name, password)) }

    /** GET /api/auth/me — current user + whether the server enforces auth. */
    suspend fun currentUser(): AuthMeResponse = call { service.currentUser() }

    // ------------------------------------------------------------------
    // Works (routes/metadata.js)
    // ------------------------------------------------------------------

    suspend fun works(
        page: Int? = null,
        order: String? = null,
        sort: String? = null,
        seed: Long? = null,
    ): WorkPageDto = call { service.works(page, order, sort, seed) }

    suspend fun search(
        keyword: String,
        page: Int? = null,
        order: String? = null,
        sort: String? = null,
    ): WorkPageDto = call { service.search(keyword, page, order, sort) }

    suspend fun work(id: Long): WorkDto = call { service.work(id) }

    suspend fun tracks(id: Long): List<TrackNodeDto> = call { service.tracks(id) }

    /**
     * Cover image bytes for a work. Prefer [coverUrl] + Coil (Task 25 loads
     * covers directly); this exists for tests and non-Coil callers.
     */
    suspend fun cover(id: Long, type: CoverType = CoverType.MAIN): ByteArray =
        call { service.cover(id, type.wire).bytes() }

    /** Absolute cover URL for Coil to load (e.g. ".../api/cover/6?type=main"). */
    fun coverUrl(id: Long, type: CoverType = CoverType.MAIN): String =
        "${apiBaseUrl}cover/$id?type=${type.wire}"

    // ------------------------------------------------------------------
    // Review (routes/review.js)
    // ------------------------------------------------------------------

    /** GET /api/review — works reviewed by the current user, optionally filtered. */
    suspend fun reviews(
        filter: ReviewFilter? = null,
        page: Int? = null,
        order: String? = null,
        sort: String? = null,
    ): WorkPageDto = call { service.reviews(filter?.wire, page, order, sort) }

    /**
     * PUT /api/review — upsert the current user's review.
     * starOnly=true (default) only writes [rating]; progressOnly=true only
     * writes [progress]; starOnly=false writes rating + reviewText + progress.
     */
    suspend fun putReview(
        workId: Long,
        rating: Int? = null,
        progress: String? = null,
        reviewText: String? = null,
        starOnly: Boolean = true,
        progressOnly: Boolean = false,
    ): MessageResponse = call {
        service.putReview(
            ReviewRequest(work_id = workId, rating = rating, review_text = reviewText, progress = progress),
            starOnly = starOnly,
            progressOnly = progressOnly,
        )
    }

    /** DELETE /api/review?work_id= — removes the current user's review. */
    suspend fun deleteReview(workId: Long): MessageResponse =
        call { service.deleteReview(workId) }

    // ------------------------------------------------------------------
    // Health / version
    // ------------------------------------------------------------------

    /** GET /api/health — plain-text "OK" (kikoeru sends text, not JSON). */
    suspend fun health(): String = call { service.health().string() }

    /** GET /api/version — server version + update check fields. */
    suspend fun version(): VersionResponse = call { service.version() }

    // ------------------------------------------------------------------
    // Circles / tags / vas (routes/metadata.js)
    // ------------------------------------------------------------------

    suspend fun circles(): List<LabelDto> = call { service.circles() }
    suspend fun circle(id: Long): LabelDto = call { service.circle(id) }
    suspend fun circleWorks(
        id: Long,
        page: Int? = null,
        order: String? = null,
        sort: String? = null,
    ): WorkPageDto = call { service.circleWorks(id, page, order, sort) }

    suspend fun tags(): List<LabelDto> = call { service.tags() }
    suspend fun tag(id: Long): LabelDto = call { service.tag(id) }
    suspend fun tagWorks(
        id: Long,
        page: Int? = null,
        order: String? = null,
        sort: String? = null,
    ): WorkPageDto = call { service.tagWorks(id, page, order, sort) }

    /** VA ids are UUID strings in kikoeru — [id] is the string uuid. */
    suspend fun vas(): List<LabelDto> = call { service.vas() }
    suspend fun va(id: String): LabelDto = call { service.va(id) }
    suspend fun vaWorks(
        id: String,
        page: Int? = null,
        order: String? = null,
        sort: String? = null,
    ): WorkPageDto = call { service.vaWorks(id, page, order, sort) }

    // ------------------------------------------------------------------
    // LRC (routes/media.js) — Task 26 wires the actual lyric flow.
    // ------------------------------------------------------------------

    /** GET /api/media/check-lrc/:id/:index — does a matching .lrc exist? */
    suspend fun checkLrc(workId: Long, index: Int): CheckLrcResponse =
        call { service.checkLrc(workId, index) }

    // ------------------------------------------------------------------

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw KikoeruErrorMapper.map(t)
    }
}

/** Cover image sizes served by kikoeru (routes/metadata.js /api/cover/:id). */
enum class CoverType(val wire: String) {
    MAIN("main"),
    SAM("sam"),
    SIZE_240("240x240"),
    SIZE_360("360x360"),
}

/** Review progress filters for GET /api/review (routes/review.js). */
enum class ReviewFilter(val wire: String) {
    MARKED("marked"),
    LISTENING("listening"),
    LISTENED("listened"),
    REPLAY("replay"),
    POSTPONED("postponed"),
}

/** Retrofit service — paths are relative to the injected `{baseUrl}/api/`. */
internal interface KikoeruService {

    @POST("auth/me")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("auth/me")
    suspend fun currentUser(): AuthMeResponse

    @GET("works")
    suspend fun works(
        @Query("page") page: Int?,
        @Query("order") order: String?,
        @Query("sort") sort: String?,
        @Query("seed") seed: Long?,
    ): WorkPageDto

    @GET("search/{keyword}")
    suspend fun search(
        @Path("keyword") keyword: String,
        @Query("page") page: Int?,
        @Query("order") order: String?,
        @Query("sort") sort: String?,
    ): WorkPageDto

    @GET("work/{id}")
    suspend fun work(@Path("id") id: Long): WorkDto

    @GET("tracks/{id}")
    suspend fun tracks(@Path("id") id: Long): List<TrackNodeDto>

    @GET("cover/{id}")
    suspend fun cover(@Path("id") id: Long, @Query("type") type: String?): ResponseBody

    @GET("review")
    suspend fun reviews(
        @Query("filter") filter: String?,
        @Query("page") page: Int?,
        @Query("order") order: String?,
        @Query("sort") sort: String?,
    ): WorkPageDto

    @PUT("review")
    suspend fun putReview(
        @Body body: ReviewRequest,
        @Query("starOnly") starOnly: Boolean?,
        @Query("progressOnly") progressOnly: Boolean?,
    ): MessageResponse

    @DELETE("review")
    suspend fun deleteReview(@Query("work_id") workId: Long): MessageResponse

    @GET("health")
    suspend fun health(): ResponseBody

    @GET("version")
    suspend fun version(): VersionResponse

    @GET("circles")
    suspend fun circles(): List<LabelDto>

    @GET("circles/{id}")
    suspend fun circle(@Path("id") id: Long): LabelDto

    @GET("circles/{id}/works")
    suspend fun circleWorks(
        @Path("id") id: Long,
        @Query("page") page: Int?,
        @Query("order") order: String?,
        @Query("sort") sort: String?,
    ): WorkPageDto

    @GET("tags")
    suspend fun tags(): List<LabelDto>

    @GET("tags/{id}")
    suspend fun tag(@Path("id") id: Long): LabelDto

    @GET("tags/{id}/works")
    suspend fun tagWorks(
        @Path("id") id: Long,
        @Query("page") page: Int?,
        @Query("order") order: String?,
        @Query("sort") sort: String?,
    ): WorkPageDto

    @GET("vas")
    suspend fun vas(): List<LabelDto>

    @GET("vas/{id}")
    suspend fun va(@Path("id") id: String): LabelDto

    @GET("vas/{id}/works")
    suspend fun vaWorks(
        @Path("id") id: String,
        @Query("page") page: Int?,
        @Query("order") order: String?,
        @Query("sort") sort: String?,
    ): WorkPageDto

    @GET("media/check-lrc/{id}/{index}")
    suspend fun checkLrc(@Path("id") id: Long, @Path("index") index: Int): CheckLrcResponse
}
