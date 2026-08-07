package com.oneasmr.app.data.local

/**
 * NORMATIVE KEY SPEC — single source of truth for all database keys.
 *
 * Later tasks (19 playback resume, 25 unified repository, 26 remote streaming)
 * MUST build keys exclusively through this object. Do not inline the format
 * anywhere else.
 *
 * Rules (from plan Task 4):
 * - [rjCode] = bare normalized code, prefix + digits, e.g. "RJ123456". Never
 *   carries a source prefix ("local"/"srvN").
 * - [sourceScope] = "local" for local works; "srv1".."srvN" for remote server
 *   config ids. Server configs do not exist before Task 24, but the format is
 *   fixed here.
 * - [workId] = "{sourceScope}:{rjCode}", e.g. "local:RJ123456". Remote works
 *   never enter the local work table; a remote work id is only ever a string
 *   used by the unified repository (Task 25).
 * - [trackKey] = "{sourceScope}:{rjCode}:{trackIndex}", e.g. "local:RJ123456:3"
 *   or "srv1:RJ123456:3". The trackKey embeds the BARE rjCode — never work.id —
 *   so there is exactly one ":" per part and no double prefix.
 *
 * Example consistency invariants (locked by KeySpecTest):
 * - "local:RJ123456" parses back to (local, RJ123456) for work ids.
 * - "local:RJ123456:3" parses back to (local, RJ123456, 3) for track keys.
 * - trackKey never starts with workId + ":" (double prefix is impossible
 *   because the middle part is the bare rjCode, not the work id).
 */
object KeySpec {

    /** Fixed source scope for works stored in the local database. */
    const val LOCAL_SOURCE = "local"

    /** Remote scopes are "srv{n}" with n >= 1. */
    fun remoteSource(serverIndex: Int): String = "srv$serverIndex"

    /** Work id key: "{sourceScope}:{rjCode}". */
    fun workId(sourceScope: String, rjCode: String): String = "$sourceScope:$rjCode"

    /** Playback-state key: "{sourceScope}:{rjCode}:{trackIndex}". */
    fun trackKey(sourceScope: String, rjCode: String, trackIndex: Int): String =
        "$sourceScope:$rjCode:$trackIndex"

    /** Parsed parts of a work id. */
    data class WorkIdParts(val sourceScope: String, val rjCode: String)

    /** Parsed parts of a track key. */
    data class TrackKeyParts(val sourceScope: String, val rjCode: String, val trackIndex: Int)

    /**
     * Splits a work id of the form "{sourceScope}:{rjCode}".
     * Returns null when the shape does not match (e.g. missing colon, empty
     * part, or a stray third segment).
     */
    fun parseWorkId(id: String): WorkIdParts? {
        val parts = id.split(':')
        if (parts.size != 2) return null
        val (scope, code) = parts
        if (scope.isEmpty() || code.isEmpty()) return null
        return WorkIdParts(scope, code)
    }

    /**
     * Splits a track key of the form "{sourceScope}:{rjCode}:{trackIndex}".
     * Returns null when the shape does not match.
     */
    fun parseTrackKey(key: String): TrackKeyParts? {
        val parts = key.split(':')
        if (parts.size != 3) return null
        val (scope, code, indexStr) = parts
        if (scope.isEmpty() || code.isEmpty()) return null
        val index = indexStr.toIntOrNull() ?: return null
        if (index < 0) return null
        return TrackKeyParts(scope, code, index)
    }
}
