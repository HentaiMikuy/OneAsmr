package com.oneasmr.app.domain.player

/**
 * Pure JVM ops for the enriched video player page (plan Task 1 of
 * video-player-controls): vertical-drag fraction mapping (brightness/volume),
 * ±10s skip clamping, and exact-same-folder sibling filtering for the
 * single-file playlist. No Android types — unit-tested without a device.
 */
object VideoGestureOps {

    /** Seek step of the skip buttons and double-tap seek: 10 seconds. */
    const val SKIP_STEP_MS = 10_000L

    /**
     * Maps a vertical drag to a value fraction: an upward drag (negative
     * [deltaYPx], screen coordinates) increases the value, and dragging the
     * full screen height corresponds to a fraction delta of 1.0. Returns 0f
     * for a zero/negative height so a degenerate layout never divides by zero.
     */
    fun dragDeltaToFraction(deltaYPx: Float, totalHeightPx: Float): Float {
        if (totalHeightPx <= 0f) return 0f
        return -deltaYPx / totalHeightPx
    }

    /** Applies a fraction delta to the current value, clamped to 0f..1f. */
    fun applyFraction(current: Float, delta: Float): Float =
        (current + delta).coerceIn(0f, 1f)

    /**
     * Skip target position clamped to [0, duration]; a negative/unknown
     * duration behaves like 0 so the result is never negative.
     */
    fun skipTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long =
        (positionMs + deltaMs).coerceIn(0L, maxOf(durationMs, 0L))

    /**
     * Parent directory of a display-name relative path ("a/b/c.mp4" -> "a/b";
     * a root-level "a.mp4" -> ""). The path convention is "/"-separated with
     * no leading slash (see TrackNode.relativePath / SingleFile.relativePath).
     */
    fun siblingFolderOf(relativePath: String): String =
        relativePath.substringBeforeLast('/', "")

    /**
     * True when both paths live in EXACTLY the same parent directory —
     * nested subfolders must NOT match ("a/b/c.mp4" vs "a/d.mp4" -> false,
     * "a/b/c.mp4" vs "a/b/d.mp4" -> true, two root-level files -> true).
     */
    fun isSibling(entryPath: String, referencePath: String): Boolean =
        siblingFolderOf(entryPath) == siblingFolderOf(referencePath)
}
