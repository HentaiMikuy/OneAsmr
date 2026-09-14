package com.oneasmr.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBackgroundPolicyTest {

    @Test
    fun `audio-only single files always continue after the page is left`() {
        assertTrue(continuesAfterPageExit(isAudioOnly = true, videoBackgroundPlayback = true))
        assertTrue(continuesAfterPageExit(isAudioOnly = true, videoBackgroundPlayback = false))
    }

    @Test
    fun `video single files follow the background playback switch`() {
        assertTrue(continuesAfterPageExit(isAudioOnly = false, videoBackgroundPlayback = true))
        assertFalse(continuesAfterPageExit(isAudioOnly = false, videoBackgroundPlayback = false))
    }
}
