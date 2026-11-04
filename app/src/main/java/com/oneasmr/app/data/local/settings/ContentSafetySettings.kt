package com.oneasmr.app.data.local.settings

import com.oneasmr.app.data.local.AgeRating
import com.oneasmr.app.data.local.isCensored

/** 封面与曲名共用的显示规则；绝对限制优先于 NSFW 和作品年龄分级。 */
data class ContentSafetySettings(
    val nsfwEnabled: Boolean = true,
    val absoluteRestrictionEnabled: Boolean = false,
) {
    val effectiveNsfwEnabled: Boolean get() = nsfwEnabled && !absoluteRestrictionEnabled

    fun shouldCensor(ageRating: AgeRating?): Boolean =
        absoluteRestrictionEnabled || (!nsfwEnabled && ageRating.isCensored())

    companion object {
        /** 设置尚未读出时先限制显示，避免冷启动短暂露出清晰封面。 */
        val LOADING = ContentSafetySettings(absoluteRestrictionEnabled = true)
    }
}
