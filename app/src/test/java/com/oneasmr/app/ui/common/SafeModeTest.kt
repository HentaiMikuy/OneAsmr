package com.oneasmr.app.ui.common

import com.oneasmr.app.data.local.AgeRating
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI-side safe-mode rule every cover consumer shares (grid cards, search
 * rows, review rows, the mini player pill).
 */
class SafeModeTest {

    @Test
    fun `nsfw on shows every cover regardless of rating`() {
        assertFalse(safeModeHidesCover(nsfwEnabled = true, ageRating = AgeRating.ALL_AGES))
        assertFalse(safeModeHidesCover(nsfwEnabled = true, ageRating = AgeRating.R15))
        assertFalse(safeModeHidesCover(nsfwEnabled = true, ageRating = AgeRating.R18))
        assertFalse(safeModeHidesCover(nsfwEnabled = true, ageRating = null))
    }

    @Test
    fun `safe mode masks sensitive and unrated covers only`() {
        assertTrue(safeModeHidesCover(nsfwEnabled = false, ageRating = AgeRating.R15))
        assertTrue(safeModeHidesCover(nsfwEnabled = false, ageRating = AgeRating.R18))
        // 评级未知(尚未标记/作品行查不到)按敏感处理:安全第一。
        assertTrue(safeModeHidesCover(nsfwEnabled = false, ageRating = null))
        // 全年龄作品在安全模式下保留封面 —— 胶囊与作品库必须同判定。
        assertFalse(safeModeHidesCover(nsfwEnabled = false, ageRating = AgeRating.ALL_AGES))
    }
}
