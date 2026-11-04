package com.oneasmr.app.ui.common

import com.oneasmr.app.data.local.AgeRating
import com.oneasmr.app.data.local.settings.ContentSafetySettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared rule used by cover consumers and playback metadata.
 */
class SafeModeTest {

    @Test
    fun `nsfw on shows every cover regardless of rating`() {
        val settings = ContentSafetySettings(nsfwEnabled = true)
        (AgeRating.entries + null).forEach { assertFalse(settings.shouldCensor(it)) }
        assertTrue(settings.effectiveNsfwEnabled)
    }

    @Test
    fun `safe mode masks sensitive and unrated covers only`() {
        val settings = ContentSafetySettings(nsfwEnabled = false)
        assertTrue(settings.shouldCensor(AgeRating.R15))
        assertTrue(settings.shouldCensor(AgeRating.R18))
        // 评级未知(尚未标记/作品行查不到)按敏感处理:安全第一。
        assertTrue(settings.shouldCensor(null))
        // 全年龄作品在安全模式下保留封面 —— 胶囊与作品库必须同判定。
        assertFalse(settings.shouldCensor(AgeRating.ALL_AGES))
    }

    @Test
    fun `absolute restriction masks every rating regardless of nsfw setting`() {
        for (nsfwEnabled in listOf(false, true)) {
            val settings = ContentSafetySettings(nsfwEnabled, absoluteRestrictionEnabled = true)
            (AgeRating.entries + null).forEach { assertTrue(settings.shouldCensor(it)) }
            assertFalse(settings.effectiveNsfwEnabled)
        }
    }

    @Test
    fun `loading settings mask every rating`() {
        (AgeRating.entries + null).forEach { assertTrue(ContentSafetySettings.LOADING.shouldCensor(it)) }
    }
}
