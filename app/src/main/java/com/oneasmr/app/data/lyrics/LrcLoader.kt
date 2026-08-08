package com.oneasmr.app.data.lyrics

import com.oneasmr.app.data.text.DecodeFailure
import com.oneasmr.app.data.text.TextDecoding
import com.oneasmr.app.data.text.TextFileReader
import com.oneasmr.app.domain.lyrics.LrcLyrics
import com.oneasmr.app.domain.lyrics.LrcParser
import java.io.IOException
import javax.inject.Inject

/**
 * Loads + decodes an .lrc document into parsed lyrics (plan Task 20).
 *
 * Charset strategy reuses the Task 14 pattern (juniversalchardet + strict
 * decode + byte round-trip verification): the detected charset is tried
 * first, then the [TextDecoding.CANDIDATES] fallback list — the first
 * charset whose decode VERIFIES wins, so a mis-detected file still decodes
 * correctly instead of rendering mojibake.
 *
 * Failure policy: unreadable/undecodable documents return null (the caller
 * hides the lyrics entry — "无歌词时隐藏入口"); a corrupt LRC that DECODES
 * fine is parsed with bad rows skipped and counted, never a crash.
 */
class LrcLoader @Inject constructor(private val reader: TextFileReader) {

    /** @return parsed lyrics, or null when the file is unreadable/undecodable/empty. */
    suspend fun load(documentUri: String): LrcLyrics? {
        val bytes = try {
            reader.read(documentUri)
        } catch (e: IOException) {
            return null
        }
        val text = decodeBestEffort(bytes) ?: return null
        val parsed = LrcParser.parse(text)
        return parsed.lines.takeIf { it.isNotEmpty() }?.let { parsed }
    }

    private fun decodeBestEffort(bytes: ByteArray): String? {
        val detected = TextDecoding.detectEncoding(bytes)
        val candidates = buildList {
            detected?.let { add(it) }
            addAll(TextDecoding.CANDIDATES)
        }.distinct()
        for (charset in candidates) {
            val decoded = try {
                TextDecoding.decodeAndVerify(bytes, charset)
            } catch (e: DecodeFailure) {
                continue
            }
            if (decoded.verified) return decoded.text
        }
        return null
    }
}
