package com.oneasmr.app.domain.player

/**
 * 播放页的四档循环策略：顺序播放 → 列表循环 → 单曲循环 → 播完暂停。
 *
 * 前三档就是 media3 的原生 repeat mode（[baseMode]）；「播完暂停」在 media3 里
 * 没有对应的枚举值 —— 它是播放器级别的 `pauseAtEndOfMediaItems` 开关（播到当前
 * 曲目末尾就停住、不自动进下一首），所以基档取 [RepeatMode.OFF]。这里用一层包装
 * 表达"第 4 档"，与 [VideoRepeatMode] 的做法一致，而 [RepeatMode] 保持 media3 的
 * 纯净三档（[com.oneasmr.app.player.toMedia3] 的 round-trip 契约不受影响）。
 */
enum class AudioRepeatMode(
    val baseMode: RepeatMode,
    val pauseAtEnd: Boolean = false,
) {
    /** 顺序播放：播完队列最后一首就停。 */
    OFF(RepeatMode.OFF),

    /** 列表循环：整个队列循环。 */
    ALL(RepeatMode.ALL),

    /** 单曲循环：当前曲目反复。 */
    ONE(RepeatMode.ONE),

    /** 播完暂停：当前曲目播到结尾即停，不自动进下一首。 */
    PAUSE_AT_END(RepeatMode.OFF, pauseAtEnd = true),
    ;

    /** 下一档（OFF -> ALL -> ONE -> PAUSE_AT_END -> OFF）。 */
    fun next(): AudioRepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> PAUSE_AT_END
        PAUSE_AT_END -> OFF
    }

    companion object {
        /**
         * media3 的 repeat 值 + 播放器的 pause-at-end 开关 → 应用层档位。
         * 开关只在基档为 OFF 时才有意义（非 OFF 档与它互斥，服务端会主动清掉，
         * 见 `PlaybackService.onRepeatModeChanged`）。
         */
        fun of(baseMode: RepeatMode, pauseAtEnd: Boolean): AudioRepeatMode =
            if (pauseAtEnd && baseMode == RepeatMode.OFF) PAUSE_AT_END else fromBase(baseMode)

        /** 丢掉"播完暂停"维度的基档投影。 */
        fun fromBase(baseMode: RepeatMode): AudioRepeatMode = when (baseMode) {
            RepeatMode.OFF -> OFF
            RepeatMode.ALL -> ALL
            RepeatMode.ONE -> ONE
        }
    }
}
