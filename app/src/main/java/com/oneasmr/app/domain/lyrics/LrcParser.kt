package com.oneasmr.app.domain.lyrics

/**
 * LRC lyrics parser (plan Task 20). Pure JVM — fixture-string unit tests are
 * deterministic; no Android types, no wall-clock.
 *
 * Supported syntax:
 *  - timestamp lines: `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]`; the fraction
 *    separator may also be `:` (some files write `[00:12:34]`).
 *  - multi-timestamp lines: `[00:12.00][00:34.00]text` -> one line per
 *    timestamp, same text.
 *  - metadata lines: `[ti:]` / `[ar:]` / `[al:]` / `[by:]` / `[offset:]`
 *    (case-insensitive); `[offset:+500]` shifts every timestamp LATER by the
 *    given ms (standard "+ shifts time up"), `-` shifts earlier.
 *
 * Tolerance (the failure-path requirement): anything that is neither a valid
 * timestamp line nor a metadata line is a BAD row — it is skipped and counted
 * in [LrcLyrics.skippedLines], never a crash. Blank lines are normal and are
 * not counted. Minutes may exceed 59 (long tracks); seconds must be < 60
 * (`[00:99]` is an illegal timestamp and the row is skipped).
 *
 * Lines are sorted by (adjusted) timestamp; equal timestamps keep file order
 * (stable sort). [LrcLyrics.indexAt] resolves the active line for a playback
 * position.
 */
data class LrcLine(val timestampMs: Long, val text: String)

data class LrcMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val author: String? = null,
    /** [offset:] value in ms (0 when absent); already applied to [LrcLyrics.lines]. */
    val offsetMs: Long = 0L,
)

data class LrcLyrics(
    /** Timestamp-sorted lyric lines (offset applied). */
    val lines: List<LrcLine>,
    val metadata: LrcMetadata,
    /** Number of non-blank malformed rows that were skipped (never throws). */
    val skippedLines: Int,
) {
    /**
     * Active-line resolution: the LAST line whose timestamp <= [positionMs],
     * or -1 before the first line (nothing highlighted yet).
     */
    fun indexAt(positionMs: Long): Int {
        val n = lines.size
        if (n == 0 || positionMs < lines[0].timestampMs) return -1
        var lo = 0
        var hi = n - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timestampMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

object LrcParser {

    /**
     * Whole-content timestamp regex: 1-3 digit minutes, 1-2 digit seconds,
     * optional 1-3 digit fraction separated by `.` or `:`.
     */
    private val TIMESTAMP = Regex("""^(\d{1,3}):(\d{1,2})(?:([.:])(\d{1,3}))?$""")

    private val KNOWN_TAGS = setOf("ti", "ar", "al", "by", "offset")

    /** Any `tag:value` bracket (incl. unknown extended tags) counts as metadata-shaped. */
    private val META_LIKE = Regex("^[A-Za-z]+:.*$")

    fun parse(text: String): LrcLyrics {
        val rawLines = mutableListOf<LrcLine>()
        val metadata = mutableMapOf<String, String>()
        var skipped = 0

        for (raw in text.split("\n")) {
            var line = raw.removeSuffix("\r").trimStart()
            if (line.isEmpty()) continue // blank lines are normal, not bad rows

            // Strip all leading [bracket] groups; the remainder is the text.
            val brackets = mutableListOf<String>()
            while (line.startsWith("[")) {
                val close = line.indexOf(']')
                if (close < 0) break
                brackets += line.substring(1, close)
                line = line.substring(close + 1)
            }
            if (brackets.isEmpty()) {
                skipped++
                continue
            }

            val timestamps = mutableListOf<Long>()
            var metadataLine = false
            for (bracket in brackets) {
                parseTimestamp(bracket)?.let { timestamps += it }
                    ?: parseMetadata(bracket)?.let { (tag, value) ->
                        metadataLine = true
                        if (tag == "offset") metadata["offset"] = value else metadata[tag] = value
                    }
                    ?: run { metadataLine = metadataLine || bracket.matches(META_LIKE) }
            }

            if (timestamps.isNotEmpty()) {
                val text = line.trim()
                timestamps.forEach { rawLines += LrcLine(it, text) }
            } else if (!metadataLine) {
                // No timestamp, no tag:value bracket — a garbage row. Skip + count.
                skipped++
            }
        }

        val offsetMs = metadata["offset"]?.let { parseOffset(it) } ?: 0L
        val lines = rawLines
            .map { it.copy(timestampMs = (it.timestampMs + offsetMs).coerceAtLeast(0L)) }
            .sortedWith(compareBy { it.timestampMs }) // stable: equal timestamps keep file order

        return LrcLyrics(
            lines = lines,
            metadata = LrcMetadata(
                title = metadata["ti"],
                artist = metadata["ar"],
                album = metadata["al"],
                author = metadata["by"],
                offsetMs = offsetMs,
            ),
            skippedLines = skipped,
        )
    }

    /** `[mm:ss(.|:)xx?]` -> ms; null when malformed (bad minutes/seconds/fraction). */
    private fun parseTimestamp(bracket: String): Long? {
        val m = TIMESTAMP.matchEntire(bracket) ?: return null
        val minutes = m.groupValues[1].toLong()
        val seconds = m.groupValues[2].toLong()
        if (seconds > 59) return null // illegal seconds — the row is garbage
        val fraction = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { frac ->
            when (frac.length) {
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.toLong()
            }
        } ?: 0L
        return minutes * 60_000L + seconds * 1_000L + fraction
    }

    /** `[tag:value]` for a known tag -> Pair(tag-lowercase, trimmed value). */
    private fun parseMetadata(bracket: String): Pair<String, String>? {
        val colon = bracket.indexOf(':')
        if (colon <= 0) return null
        val tag = bracket.substring(0, colon).trim().lowercase()
        if (tag !in KNOWN_TAGS) return null
        return tag to bracket.substring(colon + 1).trim()
    }

    /** `[offset:±N]` (optional sign, plain digits) -> ms; malformed -> 0. */
    private fun parseOffset(value: String): Long {
        val s = value.trim()
        if (s.isEmpty()) return 0L
        val sign = when {
            s.startsWith('-') -> -1L
            s.startsWith('+') -> 1L
            else -> 1L
        }
        val digits = s.removePrefix("+").removePrefix("-")
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return 0L
        return sign * digits.toLong()
    }
}
