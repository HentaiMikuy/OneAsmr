package com.oneasmr.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.domain.player.PlayQueue
import com.oneasmr.app.domain.player.PlayQueueItem

/**
 * Maps [PlayQueueItem]s to Media3 [MediaItem]s (plan Task 17).
 *
 * The mediaId is the NORMATIVE trackKey "{sourceScope}:{rjCode}:{trackIndex}"
 * built exclusively through [KeySpec] — the single source of truth for keys
 * (plan Task 4; Task 19 reads position memory back through the same key).
 * The uri is passed through verbatim — the local SAF content:// document uri
 * of the track file (the app is local-only; no remote streaming sources).
 */
fun PlayQueueItem.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(KeySpec.trackKey(sourceScope, rjCode, trackIndex))
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(trackTitle)
            .setArtist(workTitle)
            .build(),
    )
    .build()

/** Convenience: the whole queue as MediaItems (used by service + controller). */
fun PlayQueue.toMediaItems(): List<MediaItem> = items.map { it.toMediaItem() }
