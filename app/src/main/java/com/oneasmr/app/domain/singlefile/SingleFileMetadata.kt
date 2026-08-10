package com.oneasmr.app.domain.singlefile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 单文件(流媒体下载的散音视频,如 YouTube ASMR)元数据解析。纯 JVM ——
 * 无 Android 类型,单元测试完全确定性(同 domain/lyrics/LrcParser、
 * domain/trackgroup/TrackGrouper 约定)。
 *
 * 元数据来源优先级(短路合并,见 [SingleFileMetadataParser.merge]):
 *  1. yt-dlp 边车 `.info.json`(最富:标题/频道/上传日期/时长/原始链接)
 *  2. 内嵌容器元数据(MediaMetadataRetriever 读取,Android 侧传入)
 *  3. 文件名启发(剥掉尾部 ` [11位视频ID]` 与扩展名)
 *
 * 标题里的【】括号、emoji、全角字符一律原样保留 —— 日语 ASMR 标题的
 * 【ASMR】/【耳かき】前缀本身就是可读信息,过度清洗反而丢失辨识度。
 */
data class SingleFileMeta(
    /** 展示标题(永不为空;最差情况 = 去扩展名的文件名)。 */
    val displayTitle: String,
    /** YouTube 11 位视频 ID(`[A-Za-z0-9_-]{11}`);无法确定时为 null。 */
    val youtubeId: String?,
    /** 频道名(info.json channel/uploader 或内嵌 artist);未知为 null。 */
    val channel: String?,
    /** ISO-8601 上传日期 "yyyy-MM-dd";未知为 null。 */
    val uploadDate: String?,
    /** 时长毫秒;未知为 null。 */
    val durationMs: Long?,
    /** 原始网页链接(info.json webpage_url);未知为 null。 */
    val sourceUrl: String?,
)

object SingleFileMetadataParser {

    /**
     * yt-dlp 默认输出模板 `%(title)s [%(id)s].%(ext)s` 的尾部 ID 段。
     * 只认「紧贴扩展名前、方括号包裹、恰好 11 位」的形式 —— 日语标题里
     * 合法出现的方括号(如『Re[骨伝導]』)长度对不上,不会误剥。旧
     * youtube-dl 的 `标题-ID` 连字符形式刻意不支持:日文标题以连字符
     * 结尾接 11 位假名罗马字的误判率太高,裸文件宁可少截不错截。
     */
    private val TRAILING_ID = Regex("""\s*\[([A-Za-z0-9_-]{11})]$""")

    /** 宽容解析:info.json 手工裁剪/字段缺失/类型漂移都不该炸掉扫描。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 从文件名解析(第 3 级兜底)。[fileName] 含扩展名;返回的
     * [SingleFileMeta] 只可能填 displayTitle 与 youtubeId。
     */
    fun fromFileName(fileName: String): SingleFileMeta {
        val stem = fileName.substringBeforeLast('.', fileName)
        val match = TRAILING_ID.find(stem)
        val title = (if (match != null) stem.removeRange(match.range) else stem).trim()
        return SingleFileMeta(
            // 全 ID 文件名(如 "dQw4w9WgXcQ [dQw4w9WgXcQ].mp4" 剥完为空)退回原 stem。
            displayTitle = title.ifEmpty { stem.trim() },
            youtubeId = match?.groupValues?.get(1),
            channel = null,
            uploadDate = null,
            durationMs = null,
            sourceUrl = null,
        )
    }

    /**
     * 解析 yt-dlp `.info.json` 文本(第 1 级)。解析失败(损坏/非 JSON
     * 对象)返回 null,调用方落回下一级 —— 扫描永不因单个边车损坏中断。
     *
     * 字段对应 yt-dlp infojson 输出:`channel` 优先于 `uploader`(后者在
     * 老版本里可能是频道自定义 URL 名);`upload_date` 为 "yyyyMMdd";
     * `duration` 可能是整数或小数秒。
     */
    fun fromInfoJson(text: String): SingleFileMeta? {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun str(key: String): String? =
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }
                ?.content?.takeIf { it.isNotBlank() }
        val title = str("title") ?: return null
        val durationSec = runCatching { obj["duration"]?.jsonPrimitive?.doubleOrNull }.getOrNull()
        return SingleFileMeta(
            displayTitle = title,
            youtubeId = str("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) },
            channel = str("channel") ?: str("uploader"),
            uploadDate = str("upload_date")?.let { toIsoDate(it) },
            durationMs = durationSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
            sourceUrl = str("webpage_url"),
        )
    }

    /**
     * 合并各级来源,[primary] 逐字段优先([primary] 为 null 的字段用
     * [fallback] 补)。displayTitle 例外:primary 的空白标题视同缺失。
     */
    fun merge(primary: SingleFileMeta?, fallback: SingleFileMeta): SingleFileMeta {
        if (primary == null) return fallback
        return SingleFileMeta(
            displayTitle = primary.displayTitle.ifBlank { fallback.displayTitle },
            youtubeId = primary.youtubeId ?: fallback.youtubeId,
            channel = primary.channel ?: fallback.channel,
            uploadDate = primary.uploadDate ?: fallback.uploadDate,
            durationMs = primary.durationMs ?: fallback.durationMs,
            sourceUrl = primary.sourceUrl ?: fallback.sourceUrl,
        )
    }

    /** "20240315" -> "2024-03-15";位数/数字不符返回 null。 */
    private fun toIsoDate(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length != 8) return null
        return "${digits.take(4)}-${digits.drop(4).take(2)}-${digits.drop(6)}"
    }
}
