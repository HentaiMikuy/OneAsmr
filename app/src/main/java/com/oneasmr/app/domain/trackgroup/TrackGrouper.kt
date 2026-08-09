package com.oneasmr.app.domain.trackgroup

import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import java.util.EnumMap

/**
 * 音轨分组规则（作品详情页「分组」视图）。纯 JVM —— 无 Android 类型，
 * 合成树单元测试可完全确定性（同 domain/lyrics/LrcParser 约定）。
 *
 * 为什么匹配整条 relativePath 而不是父目录名：真实 RJ 作品文件夹里
 * 格式词可能出现在任意层级（`WAV/SEあり/…`、`mp3/ノイズ無/…`、
 * `1-帰り道mp3/…`），只看直接父目录会漏判。
 *
 * 每个文件节点按以下优先级短路归类（命中即停）：
 *  1. 特典 —— 路径含任一关键词（见 [TrackGrouper.BONUS_KEYWORDS]，大小写
 *     不敏感），不限文件类型：特典音频进特典组，而不是 WAV/MP3。
 *  2. 视频 / 图片 / 文本 —— 按 [TrackNodeType]。
 *  3. 音频 —— 按小写扩展名细分 wav/mp3/flac，其余进 其他音频。
 *  4. 其他 —— 剩下的 [TrackNodeType.OTHER]。
 *
 * 输出组顺序即 UI 筹码顺序（[TrackGroup] 声明序）；特典刻意排靠后——
 * 某 kikoeru 分支曾把全部文件拍平导致多结局作品被剧透，分组视图默认
 * 只展示选中组，其余组折叠在筹码后面。
 */
enum class TrackGroup(val label: String) {
    WAV("WAV"),
    MP3("MP3"),
    FLAC("FLAC"),
    OTHER_AUDIO("其他音频"),
    VIDEO("视频"),
    IMAGE("图片"),
    TEXT("文本"),
    BONUS("特典"),
    OTHER("其他"),
    ;

    /** 音频组集合：分组视图默认选中第一个音频组。 */
    val isAudio: Boolean
        get() = this == WAV || this == MP3 || this == FLAC || this == OTHER_AUDIO
}

/** 一个非空分组；[files] 保持原树的 DFS 先序（与树状视图同序）。 */
data class TrackGroupResult(
    val group: TrackGroup,
    val files: List<TrackNode>,
)

object TrackGrouper {

    /**
     * 特典关键词（一律小写形式；匹配前路径已 lowercase，日文假名/汉字
     * 不受大小写影响）。
     */
    internal val BONUS_KEYWORDS = listOf(
        "特典", "おまけ", "オマケ", "番外", "フリートーク", "キャストトーク",
        "casttalk", "bonus", "extra", "早期", "dl達成",
    )

    /** 深度优先收集文件节点并归类，按 [TrackGroup] 声明序返回非空组。 */
    fun group(root: TrackNode): List<TrackGroupResult> {
        val buckets = EnumMap<TrackGroup, MutableList<TrackNode>>(TrackGroup::class.java)
        fun walk(node: TrackNode) {
            if (node.isFolder) {
                node.children.forEach(::walk)
            } else {
                buckets.getOrPut(classifyFile(node)) { mutableListOf() } += node
            }
        }
        walk(root)
        return TrackGroup.entries.mapNotNull { key ->
            buckets[key]?.takeIf { it.isNotEmpty() }?.let { TrackGroupResult(key, it) }
        }
    }

    /** 单文件归类：特典关键词优先于一切（含格式），见文件头优先级链。 */
    internal fun classifyFile(node: TrackNode): TrackGroup {
        if (isBonusPath(node.relativePath)) return TrackGroup.BONUS
        return when (node.type) {
            TrackNodeType.VIDEO -> TrackGroup.VIDEO
            TrackNodeType.IMAGE -> TrackGroup.IMAGE
            TrackNodeType.TEXT -> TrackGroup.TEXT
            TrackNodeType.AUDIO -> audioGroupForPath(node.relativePath)
            TrackNodeType.FOLDER, TrackNodeType.OTHER -> TrackGroup.OTHER
        }
    }

    /** 特典判定：对整条相对路径（含文件名）做大小写不敏感的子串匹配。 */
    internal fun isBonusPath(relativePath: String): Boolean {
        val path = relativePath.lowercase()
        return BONUS_KEYWORDS.any { path.contains(it) }
    }

    /** 音频细分：小写扩展名映射，未知扩展名（ogg/m4a/aac/opus…）进 其他音频。 */
    internal fun audioGroupForPath(relativePath: String): TrackGroup =
        when (relativePath.substringAfterLast('.', "").lowercase()) {
            "wav" -> TrackGroup.WAV
            "mp3" -> TrackGroup.MP3
            "flac" -> TrackGroup.FLAC
            else -> TrackGroup.OTHER_AUDIO
        }
}
