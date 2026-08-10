package com.oneasmr.app.data.scanner

import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SingleFile
import com.oneasmr.app.data.local.SingleFileKind
import com.oneasmr.app.data.local.SortKeyGenerator
import com.oneasmr.app.domain.singlefile.SingleFileMeta
import com.oneasmr.app.domain.singlefile.SingleFileMetadataParser
import java.io.File

/** [SingleFileProbe.probeMedia] 的结果:容器内嵌元数据 + 可选封面字节。 */
data class MediaProbeResult(
    val durationMs: Long?,
    /** 内嵌 title 标签(yt-dlp --embed-metadata 写入);无则 null。 */
    val title: String?,
    /** 内嵌 artist 标签(yt-dlp 写频道名);无则 null。 */
    val artist: String?,
    /** 封面 JPEG 字节:视频抽帧或音频内嵌图;不可得为 null。 */
    val coverJpeg: ByteArray?,
)

/**
 * 单文件入库的 IO 探针。接口化使 [RoomSingleFilePersister] 纯 JVM 可测
 * (同 DocumentFs 约定);生产实现 AndroidSingleFileProbe 走
 * ContentResolver + MediaMetadataRetriever。所有方法失败返回 null/空 ——
 * 单个文件探测失败降级为纯文件名元数据,永不中断扫描。
 */
interface SingleFileProbe {
    /** 读边车文本(info.json);不可读返回 null。 */
    fun readSidecarText(documentUri: String): String?

    /** 读边车图片字节;不可读返回 null。 */
    fun readSidecarBytes(documentUri: String): ByteArray?

    /** 容器元数据探测;[extractCover] 为 false 时跳过昂贵的抽帧。 */
    fun probeMedia(documentUri: String, extractCover: Boolean): MediaProbeResult
}

/**
 * 单文件缩略图仓库:filesDir/single_thumbs/{fileId}.jpg。统一把边车图/
 * 抽帧/在线下载的封面落成本地文件 —— thumbSource 永远是本地路径,不存
 * SAF uri(文档 id 会因移动/重授权失效,本地文件不会)。
 */
class SingleThumbStore(private val dir: File) {

    fun write(fileId: Long, bytes: ByteArray): String? = runCatching {
        dir.mkdirs()
        val file = File(dir, "$fileId.jpg")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    fun delete(fileId: Long) {
        File(dir, "$fileId.jpg").delete()
    }
}

/**
 * Commits discovered single files into the database(与 [ScanPersister] 平行)。
 */
interface SingleFilePersister {

    /** 一次提交的行级结果:[fileId] 供重扫差集收集本轮已见 id。 */
    data class Commit(val kind: CommitKind, val fileId: Long)

    /**
     * Commits ONE file atomically。Merge semantics(同作品的保守规则):
     * - 未知 (root, relativePath) -> 新行:元数据按优先级链解析
     *   (info.json > 容器内嵌 > 文件名),封面 = 边车图 > 抽帧/内嵌图,
     *   scrapeStatus=NOT_SCRAPED。
     * - 已知行(重扫)-> 只刷新文件事实(fileName/size/lastModified)并清
     *   missing;displayTitle/channel 等元数据一律保留 —— 重扫不得清洗
     *   在线补全或边车来的更优数据。文件事实无变化且未标 missing 时
     *   完全不动行(UNCHANGED,不抖 updatedAt)。
     */
    suspend fun commitFile(candidate: SingleFileCandidate, rootFolderUri: String, nowEpochMillis: Long): Commit
}

/** Room-backed [SingleFilePersister](原子性依据同 [RoomScanPersister])。 */
class RoomSingleFilePersister(
    private val db: OneAsmrDatabase,
    private val probe: SingleFileProbe,
    private val thumbStore: SingleThumbStore,
    private val sortKeyGenerator: (String) -> String = SortKeyGenerator::generate,
) : SingleFilePersister {

    override suspend fun commitFile(
        candidate: SingleFileCandidate,
        rootFolderUri: String,
        nowEpochMillis: Long,
    ): SingleFilePersister.Commit {
        val dao = db.singleFileDao()
        val existing = dao.getByLocation(rootFolderUri, candidate.relativePath)
        if (existing != null) return refresh(existing, candidate, nowEpochMillis)

        val meta = resolveMeta(candidate)
        val id = dao.insert(
            SingleFile(
                rootFolderUri = rootFolderUri,
                relativePath = candidate.relativePath,
                fileName = candidate.fileName,
                displayTitle = meta.merged.displayTitle,
                titleSortKey = sortKeyGenerator(meta.merged.displayTitle),
                kind = if (candidate.isVideo) SingleFileKind.VIDEO else SingleFileKind.AUDIO,
                youtubeId = meta.merged.youtubeId,
                channel = meta.merged.channel,
                uploadDate = meta.merged.uploadDate,
                durationMs = meta.merged.durationMs,
                sizeBytes = candidate.sizeBytes,
                lastModified = candidate.lastModified,
                thumbSource = null,
                sourceUrl = meta.merged.sourceUrl,
                scrapeStatus = ScrapeStatus.NOT_SCRAPED,
                missing = false,
                addedAt = nowEpochMillis,
                updatedAt = nowEpochMillis,
            ),
        )
        if (id == -1L) {
            // 唯一索引竞态(同位置并发提交):按已存在处理。
            val raced = dao.getByLocation(rootFolderUri, candidate.relativePath)
                ?: return SingleFilePersister.Commit(CommitKind.UNCHANGED, fileId = -1L)
            return refresh(raced, candidate, nowEpochMillis)
        }
        // 封面字节在拿到行 id 后才能落文件名,故插入后补写 thumbSource。
        meta.coverJpeg?.let { bytes ->
            thumbStore.write(id, bytes)?.let { path ->
                dao.getById(id)?.let { dao.update(it.copy(thumbSource = path)) }
            }
        }
        return SingleFilePersister.Commit(CommitKind.INSERTED, fileId = id)
    }

    /** 重扫刷新:只动文件事实与 missing,元数据保留(见接口 KDoc)。 */
    private suspend fun refresh(
        existing: SingleFile,
        candidate: SingleFileCandidate,
        nowEpochMillis: Long,
    ): SingleFilePersister.Commit {
        val unchanged = existing.fileName == candidate.fileName &&
            existing.sizeBytes == candidate.sizeBytes &&
            existing.lastModified == candidate.lastModified &&
            !existing.missing
        if (unchanged) return SingleFilePersister.Commit(CommitKind.UNCHANGED, existing.id)
        db.singleFileDao().update(
            existing.copy(
                fileName = candidate.fileName,
                sizeBytes = candidate.sizeBytes,
                lastModified = candidate.lastModified,
                missing = false,
                updatedAt = nowEpochMillis,
            ),
        )
        return SingleFilePersister.Commit(CommitKind.UPDATED, existing.id)
    }

    private data class ResolvedMeta(val merged: SingleFileMeta, val coverJpeg: ByteArray?)

    /** 优先级链:info.json > 容器内嵌 > 文件名;封面:边车图 > 抽帧/内嵌图。 */
    private fun resolveMeta(candidate: SingleFileCandidate): ResolvedMeta {
        val infoMeta = candidate.infoJsonUri
            ?.let { probe.readSidecarText(it) }
            ?.let { SingleFileMetadataParser.fromInfoJson(it) }
        val nameMeta = SingleFileMetadataParser.fromFileName(candidate.fileName)
        val sidecarThumb = candidate.thumbUri?.let { probe.readSidecarBytes(it) }
        val probed = probe.probeMedia(candidate.documentUri, extractCover = sidecarThumb == null)
        val embeddedMeta = SingleFileMeta(
            displayTitle = probed.title.orEmpty(),
            youtubeId = null,
            channel = probed.artist,
            uploadDate = null,
            durationMs = probed.durationMs,
            sourceUrl = null,
        )
        return ResolvedMeta(
            merged = SingleFileMetadataParser.merge(
                infoMeta,
                SingleFileMetadataParser.merge(embeddedMeta, nameMeta),
            ),
            coverJpeg = sidecarThumb ?: probed.coverJpeg,
        )
    }
}

/**
 * 手动移除一个单文件(同 [removeWork] 的清理面):行删除级联清收藏夹成员,
 * 播放位置按 "single:{id}:" 前缀清,缩略图文件一并删。永不被扫描自动调用。
 */
suspend fun removeSingleFile(db: OneAsmrDatabase, thumbStore: SingleThumbStore, fileId: Long) {
    db.singleFileDao().deleteById(fileId)
    db.playbackStateDao().deleteForWorkPrefix(KeySpec.singleFileTrackKeyPrefix(fileId))
    thumbStore.delete(fileId)
}
