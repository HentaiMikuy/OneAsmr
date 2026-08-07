package com.oneasmr.app.data.scanner

/**
 * Numeric-aware string comparator matching kikoeru's natural-orderby behavior
 * (plan Task 6: "track1 < track2 < track10 — 对齐 kikoeru natural-orderby 行为").
 *
 * Rules (all locked by NaturalOrderComparatorTest):
 * - The string is split into alternating digit-run / non-digit-run parts and
 *   compared part by part.
 * - Two digit runs compare by NUMERIC VALUE ("2" < "10"); equal values
 *   (e.g. "1" vs "01") break ties by raw lexicographic order (deterministic).
 * - A digit run sorts BEFORE a text run (numbers first, like natural-orderby
 *   and file explorers).
 * - Two text runs compare case-insensitively first; exact ties break by
 *   case-sensitive order (natural-orderby's "base" collator then "variant").
 * - A string that is a prefix of the other sorts first ("a" < "ab").
 */
object NaturalOrderComparator : Comparator<String> {

    /** Digit runs longer than this compare by length, not value (Long overflow guard). */
    private const val MAX_NUMERIC_DIGITS = 18

    private data class Part(val raw: String, val numeric: Boolean, val numericValue: Long) {
        companion object {
            fun of(raw: String): Part {
                if (raw.isNotEmpty() && raw.all { it.isDigit() }) {
                    val stripped = raw.trimStart('0')
                    val value = if (stripped.length > MAX_NUMERIC_DIGITS) {
                        Long.MAX_VALUE
                    } else {
                        stripped.ifEmpty { "0" }.toLongOrNull() ?: 0L
                    }
                    return Part(raw, numeric = true, numericValue = value)
                }
                return Part(raw, numeric = false, numericValue = 0L)
            }
        }
    }

    override fun compare(a: String, b: String): Int {
        if (a == b) return 0
        val pa = splitParts(a)
        val pb = splitParts(b)
        val common = minOf(pa.size, pb.size)
        for (i in 0 until common) {
            val x = pa[i]
            val y = pb[i]
            val c = when {
                x.numeric && y.numeric -> {
                    if (x.numericValue != y.numericValue) {
                        x.numericValue.compareTo(y.numericValue)
                    } else {
                        // Equal value ("1" vs "01"): raw string breaks the tie.
                        x.raw.compareTo(y.raw)
                    }
                }
                x.numeric != y.numeric -> if (x.numeric) -1 else 1 // numbers first
                else -> compareText(x.raw, y.raw)
            }
            if (c != 0) return c
        }
        return pa.size - pb.size // prefix sorts first
    }

    private fun compareText(x: String, y: String): Int {
        val ignoreCase = x.compareTo(y, ignoreCase = true)
        if (ignoreCase != 0) return ignoreCase
        return x.compareTo(y)
    }

    private fun splitParts(s: String): List<Part> {
        if (s.isEmpty()) return emptyList()
        val parts = mutableListOf<Part>()
        var i = 0
        while (i < s.length) {
            var j = i
            val isDigit = s[i].isDigit()
            while (j < s.length && s[j].isDigit() == isDigit) j++
            parts += Part.of(s.substring(i, j))
            i = j
        }
        return parts
    }
}
