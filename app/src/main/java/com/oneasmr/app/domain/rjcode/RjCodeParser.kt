package com.oneasmr.app.domain.rjcode

/**
 * Extracts a work code from an arbitrary string (folder/file name).
 *
 * Supports prefixes RJ / BJ / VJ (case-insensitive) followed by exactly 6 or 8
 * digits. The first match in the string wins. Prefixes are normalized to
 * uppercase; the result carries the original prefix so BJ/VJ numbers are never
 * mixed into the RJ namespace (local DB key = prefix + digits, [RjCode.canonical]).
 *
 * Legacy note: kikoeru-express filesystem/utils.js used
 * `folder.match(/RJ(\d{6})/)` (RJ + 6 digits only). This parser extends that to
 * RJ/BJ/VJ x {6,8}; 5-, 7- and 9-digit runs must NOT match.
 */
object RjCodeParser {

    /**
     * RJ/BJ/VJ (case-insensitive) followed by exactly 6 or 8 digits.
     *
     * The `(?<![0-9])` / `(?![0-9])` lookarounds pin the digit run to exactly
     * 6 or 8 digits: a 5-, 7- or 9-digit run must not match, and a prefix
     * directly adjacent to another digit (e.g. "2RJ123456") is rejected.
     */
    private val CODE_PATTERN = Regex("(?i)(?<![0-9])(RJ|BJ|VJ)(\\d{6}|\\d{8})(?![0-9])")

    /**
     * Parses [input] and returns the first work code found, or null if none.
     * Null / empty / blank input yields null. The returned prefix is
     * normalized to uppercase; the numeric part keeps leading zeros, so the
     * local DB key ([RjCode.canonical]) is exactly prefix + digits, e.g.
     * "RJ123456" or "BJ012345".
     */
    fun parse(input: String?): RjCode? {
        if (input.isNullOrBlank()) return null
        val match = CODE_PATTERN.find(input) ?: return null
        return RjCode(
            prefix = match.groupValues[1].uppercase(),
            digits = match.groupValues[2],
        )
    }
}
