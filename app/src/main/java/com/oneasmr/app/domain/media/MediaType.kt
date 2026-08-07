package com.oneasmr.app.domain.media

/**
 * Media category of a file, decided by extension (see [MediaClassifier]).
 */
enum class MediaType {
    AUDIO,
    VIDEO,
    TEXT,
    IMAGE,
    OTHER,
}
