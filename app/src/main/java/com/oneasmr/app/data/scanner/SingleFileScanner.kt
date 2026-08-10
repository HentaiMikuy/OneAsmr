package com.oneasmr.app.data.scanner

import com.oneasmr.app.domain.media.MediaClassifier
import com.oneasmr.app.domain.media.MediaType
import com.oneasmr.app.domain.rjcode.RjCodeParser

/**
 * 单文件库根的发现结果:一个待入库的散音视频文件及其已配对的边车。
 * documentUri 只在本次扫描会话内有效(入库时读边车/探测用),不落库 ——
 * 播放与作品一样按 relativePath 现场解析(SAF 文档 id 会因移动/重授权
 * 失效,display-name 路径才是稳定身份,同 WorkPathResolver 的设计依据)。
 */
data class SingleFileCandidate(
    /** 相对根的 display-name 路径(含文件名,无首斜杠)。 */
    val relativePath: String,
    /** 文件显示名(含扩展名)。 */
    val fileName: String,
    /** content:// document uri —— 仅本次扫描内探测/读边车用。 */
    val documentUri: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    /** 同名 `.info.json` 边车的 document uri;无则 null。 */
    val infoJsonUri: String?,
    /** 同名缩略图边车(jpg/jpeg/png/webp)的 document uri;无则 null。 */
    val thumbUri: String?,
)

/**
 * 单文件库根的递归发现(与 [LibraryScanner] 平行的另一条流水线)。
 *
 * 规则:
 * - 深度优先、自然序遍历,但 RJ 命名的目录整棵跳过 —— 那是作品文件夹,
 *   归 [LibraryScanner] 流水线(混合库两条流水线共扫同一根时互不重叠;
 *   纯单文件根里混入的作品也因此不会被拍平成散音频)。
 * - 每个目录内,凡 [MediaClassifier] 判为 AUDIO/VIDEO 的文件都是候选;
 *   同目录下「同 stem」的 `.info.json` 与图片文件被认作它的边车,且
 *   不会再被当作独立候选(图片/文本本来就不在候选白名单里)。
 *   yt-dlp 的边车命名即「媒体文件同 stem 换扩展名」:
 *   `title [id].mp4` + `title [id].info.json` + `title [id].webp`。
 * - 不可读目录跳过并 [ScanCallback.onWarning],扫描继续(同作品扫描)。
 * - [ScanCallback.isActive] 为 false 时抛 [ScanAbortedException] 整体中止。
 *
 * 复用 [ScanCallback] 的进度/警告/取消通道,但 onWorkFound 不适用 ——
 * 单文件经 [onFileFound] 回调逐个提交(与作品一样每文件一个事务)。
 */
class SingleFileScanner(private val fs: DocumentFs) {

    /** 本次运行已发现的文件数(进度心跳)。一次扫描一个实例。 */
    private var found = 0

    interface Callback {
        /** 发现一个媒体文件;调用方原子提交(每文件一个 Room 事务)。 */
        suspend fun onFileFound(candidate: SingleFileCandidate)

        fun onProgress(currentDir: String, filesFound: Int)

        fun onWarning(message: String)

        fun isActive(): Boolean
    }

    suspend fun scanRoot(callback: Callback) {
        scanDir(FsPath(emptyList()), relativeDir = "", callback = callback)
    }

    private suspend fun scanDir(path: FsPath, relativeDir: String, callback: Callback) {
        if (!callback.isActive()) throw ScanAbortedException("scan cancelled")
        callback.onProgress(relativeDir, found)
        val children = try {
            fs.listChildren(path)
        } catch (e: DocumentReadException) {
            callback.onWarning("unreadable directory '$relativeDir': ${e.message}")
            return
        }
        val sorted = children.sortedWith(entryComparator)
        // stem(小写) -> 边车文件;先建索引再遍历媒体文件,顺序无关。
        val infoJsonByStem = HashMap<String, FsEntry>()
        val thumbByStem = HashMap<String, FsEntry>()
        for (entry in sorted) {
            if (entry.isDirectory) continue
            val lower = entry.name.lowercase()
            when {
                lower.endsWith(INFO_JSON_SUFFIX) ->
                    infoJsonByStem.putIfAbsent(lower.removeSuffix(INFO_JSON_SUFFIX), entry)
                MediaClassifier.classify(entry.name) == MediaType.IMAGE ->
                    thumbByStem.putIfAbsent(lower.substringBeforeLast('.'), entry)
            }
        }
        for (entry in sorted) {
            when {
                entry.isDirectory -> {
                    // RJ 命名的目录是作品文件夹,归作品流水线(混合库根走
                    // LibraryScanner 收录);单文件扫描绝不进去拍平 —— 有
                    // 作品结构的内容不该被解析成一个个散音频。
                    if (RjCodeParser.parse(entry.name) == null) {
                        scanDir(path + entry.documentId, joinPath(relativeDir, entry.name), callback)
                    }
                }
                else -> {
                    val type = MediaClassifier.classify(entry.name)
                    if (type != MediaType.AUDIO && type != MediaType.VIDEO) continue
                    val stem = entry.name.lowercase().substringBeforeLast('.')
                    found += 1
                    val candidate = SingleFileCandidate(
                        relativePath = joinPath(relativeDir, entry.name),
                        fileName = entry.name,
                        documentUri = entry.documentUri,
                        isVideo = type == MediaType.VIDEO,
                        sizeBytes = entry.size,
                        lastModified = entry.lastModified,
                        infoJsonUri = infoJsonByStem[stem]?.documentUri,
                        thumbUri = thumbByStem[stem]?.documentUri,
                    )
                    callback.onProgress(candidate.relativePath, found)
                    callback.onFileFound(candidate)
                }
            }
        }
    }

    private fun joinPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    private companion object {
        /** yt-dlp 元数据边车后缀(`标题 [id].info.json`)。 */
        const val INFO_JSON_SUFFIX = ".info.json"

        val entryComparator = Comparator<FsEntry> { a, b -> NaturalOrderComparator.compare(a.name, b.name) }
    }
}

/**
 * 单文件重扫的 missing 差集(同 [RescanDiffComputer] 的规则,主键为 Long):
 * 只有「完整枚举过的根」下、本轮未再发现、且尚未标记的行才进候补。
 * 纯函数,Room/fs 无关,单测锁定。
 */
object SingleFileRescanDiff {

    data class StoredRef(val id: Long, val rootFolderUri: String, val missing: Boolean)

    fun computeMissing(
        discoveredIds: Set<Long>,
        stored: List<StoredRef>,
        completeRootUris: Set<String>,
    ): List<Long> = stored.asSequence()
        .filter { it.rootFolderUri in completeRootUris }
        .filterNot { it.id in discoveredIds }
        .filterNot { it.missing }
        .map { it.id }
        .toList()
}
