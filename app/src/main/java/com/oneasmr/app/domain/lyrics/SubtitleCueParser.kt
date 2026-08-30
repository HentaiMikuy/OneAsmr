package com.oneasmr.app.domain.lyrics

/**
 * WebVTT / SRT cue parser. Pure JVM — fixture-string unit tests are
 * deterministic; no Android types, no wall-clock (same contract as
 * [LrcParser]).
 *
 * Output reuses the [LrcLyrics] model so the whole lyrics chain (panel,
 * strip, highlight-follow) works unchanged: one [LrcLine] per cue, timestamp
 * = cue START time, payload lines joined with a space.
 *
 * Supported syntax:
 *  - timing lines: `mm:ss.ttt --> ...` (VTT short form) and
 *    `hh:mm:ss.ttt --> ...`; the fraction separator may be `.` (VTT) or `,`
 *    (SRT). Anything after the arrow (end time, VTT cue settings) is ignored.
 *  - `WEBVTT` header, NOTE/STYLE/REGION blocks, SRT index lines and VTT cue
 *    identifiers are ignored naturally: only lines containing `-->` start a
 *    cue, and payload is read strictly BELOW the timing line.
 *  - payload markup: `<...>` tags (voice/karaoke/italic spans) are stripped;
 *    `&amp;` `&lt;` `&gt;` `&nbsp;` are unescaped.
 *
 * Tolerance (same failure policy as [LrcParser]): a `-->` line whose start
 * timestamp does not parse is a BAD row — skipped and counted in
 * [LrcLyrics.skippedLines], never a crash. Cues with an empty payload are
 * dropped silently (nothing to display). Lines are sorted by timestamp;
 * equal timestamps keep file order (stable sort).
 */
object SubtitleCueParser {

    /** Start timestamp of a timing line: optional hours, then mm:ss + fraction. */
    private val CUE_START = Regex("""^(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})$""")

    private val TAG = Regex("""<[^>]*>""")

    fun parse(text: String): LrcLyrics {
        val rows = text.split("\n").map { it.removeSuffix("\r") }
        val rawLines = mutableListOf<LrcLine>()
        var skipped = 0

        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            if ("-->" !in row) {
                i++
                continue
            }
            val startMs = parseStart(row)
            if (startMs == null) {
                skipped++
                i++
                continue
            }
            // Payload: the lines below the timing line, up to the first blank
            // line (cue separator) or the next timing line (tolerates a
            // missing separator).
            val payload = StringBuilder()
            var j = i + 1
            while (j < rows.size && rows[j].isNotBlank() && "-->" !in rows[j]) {
                val cleaned = unescape(TAG.replace(rows[j], "")).trim()
                if (cleaned.isNotEmpty()) {
                    if (payload.isNotEmpty()) payload.append(' ')
                    payload.append(cleaned)
                }
                j++
            }
            if (payload.isNotEmpty()) rawLines += LrcLine(startMs, payload.toString())
            i = j
        }

        return LrcLyrics(
            lines = rawLines.sortedWith(compareBy { it.timestampMs }),
            metadata = LrcMetadata(),
            skippedLines = skipped,
        )
    }

    /** `[hh:]mm:ss[.,]ttt --> ...` -> start ms; null when malformed. */
    private fun parseStart(row: String): Long? {
        val start = row.substringBefore("-->").trim()
        val m = CUE_START.matchEntire(start) ?: return null
        val hours = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        val minutes = m.groupValues[2].toLong()
        val seconds = m.groupValues[3].toLong()
        if (seconds > 59) return null
        if (m.groupValues[1].isNotEmpty() && minutes > 59) return null
        val fraction = m.groupValues[4].let { frac ->
            when (frac.length) {
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.toLong()
            }
        }
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000L + fraction
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
}
