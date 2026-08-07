package com.oneasmr.app.data.text

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.mozilla.universalchardet.UniversalDetector

/**
 * Text-file decoding core (plan Task 14: "文本节点→内置文本查看器（编码自动探测，
 * UTF-8/Shift_JIS 等）…必须处理乱码（探测失败时给编码切换菜单）").
 *
 * Pure JVM so fixture-byte unit tests are deterministic:
 *
 * 1. [detectEncoding] runs juniversalchardet over the raw bytes (UTF-8 /
 *    Shift_JIS / GB18030 / EUC-JP / Big5 / ...).
 * 2. [decode] decodes with a strict decoder (REPORT on malformed input — a
 *    wrong charset FAILS loudly instead of silently emitting replacement
 *    chars / mojibake).
 * 3. [verifyRoundTrip] re-encodes the decoded text and requires byte-identical
 *    output. This is the mojibake guard: a charset that "decodes" a Shift_JIS
 *    file into garbage almost never round-trips, so [decodeAndVerify] rejects
 *    it and the UI must show the encoding-switch menu instead of the text.
 *
 * Round-trip is not bulletproof (any single-byte charset is a bijection over
 * bytes — e.g. ISO-8859-1 always verifies), which is why the user-facing
 * encoding menu stays reachable at all times; it is the documented fallback
 * when auto-detection cannot be trusted.
 */
object TextDecoding {

    /**
     * Encodings offered in the manual switch menu, most common first. All are
     * supported by the JVM/Android charset registry.
     */
    val CANDIDATES: List<String> = listOf(
        "UTF-8",
        "Shift_JIS",
        "GB18030",
        "EUC-JP",
        "Big5",
        "EUC-KR",
        "windows-1252",
        "ISO-8859-1",
        "UTF-16LE",
        "UTF-16BE",
    )

    /** Charset names juniversalchardet emits that map to a supported Java charset. */
    private val DETECTED_TO_JAVA = mapOf(
        "WINDOWS-1252" to "windows-1252",
        "ISO-8859-9" to "ISO-8859-1", // windows-1254 ≈ 8859-9; near-enough fallback
    )

    /**
     * Detects the byte order mark first (authoritative when present), else
     * juniversalchardet. Returns a Java-normalized charset name or null when
     * the detector has no confident answer (too little data, unknown bytes).
     */
    fun detectEncoding(bytes: ByteArray): String? {
        val bom = bomEncoding(bytes) ?: detectWithoutBom(bytes)
        return bom?.let { javaName(it) }
    }

    /** juniversalchardet probe; returns its raw name or null. */
    fun detectWithoutBom(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val detector = UniversalDetector()
        try {
            detector.handleData(bytes, 0, bytes.size)
            detector.dataEnd()
            return detector.getDetectedCharset()?.takeIf { it.isNotBlank() }
        } finally {
            detector.reset()
        }
    }

    /** BOM sniff: UTF-8 / UTF-16LE / UTF-16BE / UTF-32 when the file starts with one. */
    fun bomEncoding(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> "UTF-8"
        bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte() -> "UTF-32LE"
        bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte() -> "UTF-32BE"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
        else -> null
    }

    /**
     * Strict decode: throws [DecodeFailure] on malformed input or an unknown
     * charset — the caller renders an error + the encoding menu, NEVER garbage.
     * A single leading BOM (U+FEFF) decoded from the bytes is stripped so the
     * viewer never displays the invisible BOM character.
     */
    fun decode(bytes: ByteArray, charsetName: String): String {
        val name = javaName(charsetName)
        val charset = try {
            Charset.forName(name)
        } catch (e: Exception) {
            throw DecodeFailure("未知编码 $name", e)
        }
        val text = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            throw DecodeFailure("编码 $name 无法解码该文件（字节与编码不匹配）", e)
        }
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }

    /** True when decoding with [charsetName] reproduces the file byte-for-byte. */
    fun verifyRoundTrip(bytes: ByteArray, charsetName: String): Boolean = try {
        val name = javaName(charsetName)
        val text = decode(bytes, name)
        val charset = Charset.forName(name)
        val encoded = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        val out = ByteArray(encoded.remaining())
        encoded.get(out)
        // BOMs: the input may carry one, the encoder may add one — compare content only.
        stripBom(bytes.toList()) == stripBom(out.toList())
    } catch (e: Exception) {
        false
    }

    /**
     * Detect + decode + verify in one step (the text viewer's auto path).
     *
     * @return [Decoded(text, charset, verified)]; [verified] false means the
     *   charset decoded SOMETHING but the output is not trustworthy (mojibake
     *   risk) — the caller must offer the encoding menu.
     *
     * Verification = byte round-trip AND, for the single-byte fallback
     * charsets (windows-1252 / ISO-8859-1, which decode EVERY byte sequence
     * and therefore always round-trip), the decoded text must be pure ASCII:
     * a CJK file mis-detected as windows-1252 produces non-ASCII garbage that
     * passes round-trip but is still mojibake — that case is rejected here so
     * the auto path never renders it.
     */
    fun decodeAndVerify(bytes: ByteArray, charsetName: String): Decoded {
        val text = decode(bytes, charsetName)
        val verified = verifyRoundTrip(bytes, charsetName) && !isSuspiciousSingleByte(charsetName, text)
        return Decoded(text = text, charset = charsetName, verified = verified)
    }

    private fun isSuspiciousSingleByte(charsetName: String, text: String): Boolean {
        if (charsetName.uppercase() !in SINGLE_BYTE_FALLBACKS) return false
        return text.any { it.code >= 0x80 }
    }

    /** Single-byte charsets that decode every byte sequence (bijections). */
    private val SINGLE_BYTE_FALLBACKS = setOf("WINDOWS-1252", "ISO-8859-1")

    /** Strips a leading BOM (any encoding) from a byte list. */
    private fun stripBom(bytes: List<Byte>): List<Byte> = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> bytes.drop(3)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> bytes.drop(2)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> bytes.drop(2)
        else -> bytes
    }

    /** Normalizes juniversalchardet / user charset names to Java registry names. */
    private fun javaName(name: String): String =
        DETECTED_TO_JAVA[name.uppercase()] ?: name

    data class Decoded(val text: String, val charset: String, val verified: Boolean)
}

/** Raised when bytes cannot be decoded with a charset (never a crash — UI error). */
class DecodeFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
