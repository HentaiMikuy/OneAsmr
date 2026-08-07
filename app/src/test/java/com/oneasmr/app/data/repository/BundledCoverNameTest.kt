package com.oneasmr.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locking of [isBundledCoverName]: the plan's cover.jpg / folder.jpg / 封面* set. */
class BundledCoverNameTest {

    @Test
    fun `common bundled cover names match case-insensitively`() {
        assertTrue(isBundledCoverName("cover.jpg"))
        assertTrue(isBundledCoverName("Cover.JPG"))
        assertTrue(isBundledCoverName("folder.jpg"))
        assertTrue(isBundledCoverName("FOLDER.png"))
        assertTrue(isBundledCoverName("folder.webp"))
        assertTrue(isBundledCoverName("cover.jpeg"))
        assertTrue(isBundledCoverName("封面.jpg"))
        assertTrue(isBundledCoverName("封面图.png"))
        assertTrue(isBundledCoverName("  封面.webp"))
    }

    @Test
    fun `non-cover files do not match`() {
        assertFalse(isBundledCoverName("track.mp3"))
        assertFalse(isBundledCoverName("cover")) // bare name, no extension
        assertFalse(isBundledCoverName("coverart.jpg"))
        assertFalse(isBundledCoverName("folder_icon.png"))
        assertFalse(isBundledCoverName("myfolder.jpg"))
        assertFalse(isBundledCoverName(""))
    }
}
