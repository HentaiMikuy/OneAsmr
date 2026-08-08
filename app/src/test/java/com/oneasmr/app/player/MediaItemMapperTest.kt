package com.oneasmr.app.player

import androidx.media3.common.MediaItem
import com.oneasmr.app.domain.player.PlayQueue
import com.oneasmr.app.domain.player.PlayQueueItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MediaItem construction from the domain PlayQueue model (plan Task 17:
 * mediaId = normative trackKey via KeySpec; the local SAF content:// uri
 * rides through setUri — the app is local-only).
 *
 * Robolectric provides the real android.net.Uri implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemMapperTest {

    private fun item(uri: String) = PlayQueueItem(
        sourceScope = "local",
        rjCode = "RJ100200",
        trackIndex = 3,
        trackTitle = "track3.wav",
        workTitle = "RJ100200 标题",
        uri = uri,
    )

    @Test
    fun `mediaId is the KeySpec trackKey`() {
        assertEquals("local:RJ100200:3", item("content://doc/t3").toMediaItem().mediaId)
    }

    @Test
    fun `local SAF uri is passed through verbatim`() {
        val mediaItem: MediaItem = item("content://com.android.externalstorage.documents/document/primary%3AAsmrLib%2FRJ100200%2Ftrack3.wav").toMediaItem()
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3AAsmrLib%2FRJ100200%2Ftrack3.wav",
            mediaItem.localConfiguration?.uri.toString(),
        )
    }

    @Test
    fun `arbitrary uri string passes through verbatim`() {
        val mediaItem: MediaItem =
            item("https://example.com/any/uri").toMediaItem()
        assertEquals(
            "https://example.com/any/uri",
            mediaItem.localConfiguration?.uri.toString(),
        )
    }

    @Test
    fun `metadata carries track title and work title`() {
        val mediaItem = item("content://doc/t3").toMediaItem()
        assertEquals("track3.wav", mediaItem.mediaMetadata.title.toString())
        assertEquals("RJ100200 标题", mediaItem.mediaMetadata.artist.toString())
    }

    @Test
    fun `whole queue maps in order with keyed mediaIds`() {
        val queue = PlayQueue(
            workId = "local:RJ100200",
            items = listOf(
                item("content://doc/t1").copy(trackIndex = 1, trackTitle = "track1.mp3"),
                item("content://doc/t2").copy(trackIndex = 2, trackTitle = "track2.mp3"),
            ),
            startIndex = 1,
        )
        assertEquals(
            listOf("local:RJ100200:1", "local:RJ100200:2"),
            queue.toMediaItems().map { it.mediaId },
        )
    }
}
