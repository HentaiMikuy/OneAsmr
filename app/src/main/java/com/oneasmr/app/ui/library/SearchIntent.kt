package com.oneasmr.app.ui.library

/**
 * Task 13 search-intent classification — pure JVM, unit-testable.
 *
 * Hit rules (plan): a full-string `(RJ|BJ|VJ)?\d{6,8}` is a CODE LOOKUP; any
 * other input is an FTS5 keyword search.
 *
 * - With a prefix: one canonical code to probe ("RJ123456").
 * - Without a prefix (bare 6/8 digits): all three prefixes are probed, since
 *   the local DB key carries the prefix ("local:RJ123456").
 * - A 6-8 digit query that is NOT an RjCodeParser shape (e.g. 7 digits, or a
 *   bare-digit string with no matching work) is still classified DirectCode
 *   here; the lookup MISSES and the caller must fall back to FTS — that
 *   fallback (never an empty page for a 7-digit input) lives in the
 *   ViewModel/DAO layer and is exercised by tests.
 */
object SearchIntentClassifier {

    /** Full-string code pattern: optional RJ/BJ/VJ prefix + exactly 6 or 8 digits. */
    private val CODE_ONLY = Regex("""(?i)(RJ|BJ|VJ)?(\d{6,8})""")

    sealed interface Intent {
        /** Exact code lookup. [codes] are the canonical keys to probe, e.g. "RJ123456". */
        data class DirectCode(val codes: List<String>) : Intent

        /** FTS keyword search over title/circle/tag/va. */
        data class FtsQuery(val term: String) : Intent
    }

    fun classify(input: String?): Intent {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return Intent.FtsQuery(trimmed)
        val match = CODE_ONLY.matchEntire(trimmed) ?: return Intent.FtsQuery(trimmed)
        val prefix = match.groupValues[1].takeIf { it.isNotEmpty() }?.uppercase()
        val digits = match.groupValues[2]
        val codes = if (prefix != null) {
            listOf(prefix + digits)
        } else {
            listOf("RJ", "BJ", "VJ").map { it + digits }
        }
        return Intent.DirectCode(codes)
    }
}
