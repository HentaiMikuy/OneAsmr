package com.oneasmr.app.player

/**
 * 单档(散音视频)页面退出后的续播决策:页面 back / 被移除时,条目是继续留在
 * 会话里播放,还是暂停并回滚音频上下文。
 *
 * - 音频单档无条件续播(与作品音轨同权,不受任何开关约束)。
 * - 视频单档遵循持久化的「视频后台续播」开关:开(默认)= 退出后继续播放
 *   (画面无人看,视频页退出时已禁用视频轨,只解码音频);关 = 暂停
 *   (never play unseen)并把播放前保存的音频上下文回滚回来。
 *
 * Pure so the rule is unit-tested without an Android runtime.
 */
fun continuesAfterPageExit(isAudioOnly: Boolean, videoBackgroundPlayback: Boolean): Boolean =
    isAudioOnly || videoBackgroundPlayback
