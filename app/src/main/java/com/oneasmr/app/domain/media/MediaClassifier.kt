package com.oneasmr.app.domain.media

/**
 * Classifies a file by extension (case-insensitive) into a [MediaType].
 *
 * Whitelist (plan Task 3, exact — do not add/remove entries):
 *  - AUDIO: mp3 / wav / flac / ogg / opus / aac / m4a
 *  - VIDEO: mp4 / webm / mkv / mov
 *  - TEXT:  lrc / srt / ass / txt
 *  - IMAGE: jpg / jpeg / png / webp
 * Everything else (including missing/blank extension) is [MediaType.OTHER].
 */
object MediaClassifier {

    /** AUDIO whitelist — exact entries from plan Task 3. */
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "ogg", "opus", "aac", "m4a")

    /** VIDEO whitelist — exact entries from plan Task 3. */
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov")

    /** TEXT whitelist — exact entries from plan Task 3. */
    private val TEXT_EXTENSIONS = setOf("lrc", "srt", "ass", "txt")

    /** IMAGE whitelist — exact entries from plan Task 3. */
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /**
     * Classifies [fileName] by its last extension (case-insensitive).
     * Null / empty / blank input, a trailing dot, or an extension outside the
     * whitelist is [MediaType.OTHER].
     */
    fun classify(fileName: String?): MediaType {
        if (fileName.isNullOrBlank()) return MediaType.OTHER
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .trim()
            .lowercase()
        return when (ext) {
            in AUDIO_EXTENSIONS -> MediaType.AUDIO
            in VIDEO_EXTENSIONS -> MediaType.VIDEO
            in TEXT_EXTENSIONS -> MediaType.TEXT
            in IMAGE_EXTENSIONS -> MediaType.IMAGE
            else -> MediaType.OTHER
        }
    }
}
