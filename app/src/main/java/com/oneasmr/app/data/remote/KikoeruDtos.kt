package com.oneasmr.app.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Wire DTOs for kikoeru-express (pinned commit dd030f3e989b54d4f97ee73ff08aa662c6709694).
 *
 * The response shapes below are the POST-normalize wire format: routes/utils/normalize.js
 * rewrites the staticMetadata view's circleObj/vaObj/tagObj JSON aggregates into plain
 * `circle` / `vas` / `tags` objects and deletes the Obj keys before sending, so a client
 * NEVER sees circleObj/vaObj/tagObj on the wire (they only exist inside database/db.js).
 * nsfw is coerced to a real boolean by normalize() too.
 *
 * Remote work ids are INTEGERS (the 6-digit RJ number). They are NOT the string keys of
 * the local DB ("local:RJ123456" / "srv{n}:RJ123456" — Task 25 owns that mapping).
 */

// ---------------------------------------------------------------------------
// Auth (routes/auth.js)
// ---------------------------------------------------------------------------

/** POST /api/auth/me request body: `{name, password}` (kikoeru validates min length 5). */
@Serializable
data class LoginRequest(
    val name: String,
    val password: String,
)

/** POST /api/auth/me success response: signed HS256 JWT. */
@Serializable
data class LoginResponse(
    val token: String,
)

/** User identity inside GET /api/auth/me. */
@Serializable
data class UserDto(
    val name: String = "",
    val group: String = "",
)

/**
 * GET /api/auth/me response. `auth` tells the client whether the server enforces
 * login at all (false => "admin"/"administrator" is served without credentials).
 */
@Serializable
data class AuthMeResponse(
    val user: UserDto = UserDto(),
    val auth: Boolean = false,
)

// ---------------------------------------------------------------------------
// Pagination (shared by works/search/review/metadata-works endpoints)
// ---------------------------------------------------------------------------

/** Pagination block; kikoeru pageSize comes from config, default 12. */
@Serializable
data class PaginationDto(
    val currentPage: Int = 1,
    val pageSize: Int = 12,
    val totalCount: Long = 0,
)

// ---------------------------------------------------------------------------
// Work metadata (database/db.js staticMetadata view, normalized)
// ---------------------------------------------------------------------------

@Serializable
data class CircleDto(
    val id: Long,
    val name: String = "",
)

/** VA ids are UUID v5 strings in kikoeru (t_va.id), unlike numeric circle/tag ids. */
@Serializable
data class VaDto(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class TagDto(
    val id: Long,
    val name: String = "",
)

/**
 * A work as served by /api/works, /api/search/:keyword, /api/work/:id,
 * /api/review and /api/{circle|tag|va}s/:id/works.
 *
 * NOTE: `id` is the remote INTEGER id (the RJ digits, e.g. 6 or 123456), not the
 * local DB key. `rate_count_detail` / `rank` are the parsed JSON values of the
 * t_work TEXT columns. Per-user review fields (userRating/review_text/progress/
 * updated_at/user_name) come from the left-joined t_review row and are null when
 * the current user has no review for that work.
 */
@Serializable
data class WorkDto(
    val id: Long,
    val title: String = "",
    /** Legacy top-level circle name; normalize() keeps both this and [circle]. */
    val name: String = "",
    val circle_id: Long = 0,
    /** Parsed circleObj aggregate: `{"id": <circle_id>, "name": <circle name>}`. */
    val circle: CircleDto? = null,
    val nsfw: Boolean = false,
    /** Release date "YYYY-MM-DD". */
    val release: String = "",
    val dl_count: Long? = null,
    val price: Long? = null,
    val review_count: Long? = null,
    val rate_count: Long? = null,
    val rate_average_2dp: Double? = null,
    /** Parsed rate_count_detail JSON (object keyed by star rating, e.g. {"5":160}). */
    val rate_count_detail: JsonElement? = null,
    /** Parsed rank JSON, or null when kikoeru has no rank data. */
    val rank: JsonElement? = null,
    /** Parsed vaObj aggregate: `{"vas": [{"id","name"}]}`. */
    val vas: List<VaDto> = emptyList(),
    /** Parsed tagObj aggregate: `{"tags": [{"id","name"}]}`. */
    val tags: List<TagDto> = emptyList(),
    // Per-user review fields (left join t_review on user_name):
    val userRating: Int? = null,
    val review_text: String? = null,
    /** One of marked/listening/listened/replay/postponed, or null. */
    val progress: String? = null,
    val updated_at: String? = null,
    val user_name: String? = null,
)

/** Paged work list payload shared by all list endpoints. */
@Serializable
data class WorkPageDto(
    val works: List<WorkDto> = emptyList(),
    val pagination: PaginationDto = PaginationDto(),
)

// ---------------------------------------------------------------------------
// Tracks tree (filesystem/utils.js toTree; served by GET /api/tracks/:id)
// ---------------------------------------------------------------------------

/**
 * Track node type on the wire. kikoeru classifies mp3/ogg/opus/wav/aac/flac AND
 * video (mp4/webm/m4a) as "audio" — video attachments arrive as AUDIO nodes
 * (kikoeru legacy; the local scanner keeps them as VIDEO, see Task 6).
 * Unknown type strings degrade to OTHER instead of failing the whole parse.
 */
@Serializable(with = TrackNodeType.Serializer::class)
enum class TrackNodeType(val wire: String) {
    FOLDER("folder"),
    AUDIO("audio"),
    TEXT("text"),
    IMAGE("image"),
    OTHER("other");

    object Serializer : KSerializer<TrackNodeType> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("KikoeruTrackNodeType", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): TrackNodeType {
            val value = decoder.decodeString()
            return entries.firstOrNull { it.wire == value } ?: OTHER
        }

        override fun serialize(encoder: Encoder, value: TrackNodeType) =
            encoder.encodeString(value.wire)
    }
}

/**
 * A node of the /api/tracks/:id tree. Folder nodes carry only [type]/[title]/
 * [children]; file nodes carry [hash]/[workTitle]/[mediaStreamUrl]/
 * [mediaDownloadUrl]. hash is "{workId}/{index}" (e.g. "6/0") and the URLs are
 * relative to the server root (e.g. "/api/media/stream/6/0").
 */
@Serializable
data class TrackNodeDto(
    val type: TrackNodeType = TrackNodeType.OTHER,
    val title: String = "",
    val hash: String? = null,
    val workTitle: String? = null,
    val mediaStreamUrl: String? = null,
    val mediaDownloadUrl: String? = null,
    val children: List<TrackNodeDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Review (routes/review.js)
// ---------------------------------------------------------------------------

/** PUT /api/review body. Nulls are sent explicitly; kikoeru validates with
 *  optional({nullable: true}) so they are tolerated. */
@Serializable
data class ReviewRequest(
    val work_id: Long,
    val rating: Int? = null,
    val review_text: String? = null,
    val progress: String? = null,
)

/** {message: ...} success payload for PUT/DELETE /api/review. */
@Serializable
data class MessageResponse(
    val message: String = "",
)

// ---------------------------------------------------------------------------
// Misc (routes/version.js, routes/media.js)
// ---------------------------------------------------------------------------

/** GET /api/version payload (kikoeru package.json version + GitHub check). */
@Serializable
data class VersionResponse(
    val current: String = "",
    val latest_stable: String? = null,
    val latest_release: String? = null,
    val update_available: Boolean = false,
    val notifyUser: Boolean = false,
    val lockFileExists: Boolean = false,
    val lockReason: String? = null,
)

/** GET /api/media/check-lrc/:id/:index payload. */
@Serializable
data class CheckLrcResponse(
    val result: Boolean = false,
    val message: String = "",
    val hash: String = "",
)

// ---------------------------------------------------------------------------
// Labels: circles / tags / vas (routes/metadata.js)
// ---------------------------------------------------------------------------

/**
 * One item of GET /api/{circle|tag|va}s/ (label list with work counts) or of
 * GET /api/{circle|tag|va}s/:id (single label without count).
 *
 * `id` accepts BOTH a JSON number (circle/tag) and a JSON string (VA uuid) —
 * kikoeru t_va.id is a UUID string while t_circle/t_tag use integer ids.
 */
@Serializable
data class LabelDto(
    @Serializable(with = StringIdSerializer::class)
    val id: String = "",
    val name: String = "",
    /** Work count, present in list responses (knex count alias). */
    val count: Long = 0,
)

/** Deserializes a JSON number or string into a plain String id. */
object StringIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("KikoeruStringOrIntId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: throw SerializationException("StringIdSerializer requires a JSON decoder")
        return (element as? JsonPrimitive)?.contentOrNull
            ?: throw SerializationException("expected a string or int label id, got $element")
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
