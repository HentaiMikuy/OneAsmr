package com.oneasmr.app.data.repository

import android.content.Context
import android.util.Log
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SortKeyGenerator
import com.oneasmr.app.data.remote.RequestPacer
import com.oneasmr.app.data.remote.youtube.YouTubeOEmbedClient
import com.oneasmr.app.data.scanner.SingleThumbStore
import com.oneasmr.app.worker.ScanLibraryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次在线补全的结果(UI 提示文案分流)。 */
enum class EnrichOutcome { OK, NO_VIDEO_ID, NOT_FOUND, ALREADY_RUNNING }

/**
 * 单档在线补全(YouTube oEmbed):按行 youtubeId 取标题/频道名 + 封面,
 * 写回 single_file。与作品刮削同心智 —— 手动触发、失败标 FAILED 可重试、
 * 成功标 OK;共享全局 [RequestPacer]。
 *
 * 覆盖规则:oEmbed 是权威源(用户显式点了补全),标题/频道/封面一律
 * 覆盖本地解析值;sourceUrl 缺失时由 id 推导。文件名带 [ID] 的裸文件
 * 一键即可;完全无 ID 的行走「关联视频链接」先绑 id(见
 * [com.oneasmr.app.domain.singlefile.YouTubeLinkParser])。
 */
@Singleton
class SingleFileEnricher @Inject constructor(
    @ApplicationContext context: Context,
    private val db: OneAsmrDatabase,
    client: okhttp3.OkHttpClient,
    pacer: RequestPacer,
) {

    private val oembed = YouTubeOEmbedClient(client, pacer)
    private val thumbStore =
        SingleThumbStore(File(context.filesDir, ScanLibraryWorker.SINGLE_THUMBS_DIR))
    private val running = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /** 绑定视频 ID(手动关联)并顺势补全。 */
    suspend fun bindAndEnrich(fileId: Long, videoId: String): EnrichOutcome {
        val dao = db.singleFileDao()
        val row = dao.getById(fileId) ?: return EnrichOutcome.NOT_FOUND
        dao.update(
            row.copy(
                youtubeId = videoId,
                sourceUrl = row.sourceUrl ?: "https://youtu.be/$videoId",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return enrich(fileId)
    }

    suspend fun enrich(fileId: Long): EnrichOutcome = withContext(Dispatchers.IO) {
        if (!running.add(fileId)) return@withContext EnrichOutcome.ALREADY_RUNNING
        try {
            val dao = db.singleFileDao()
            val row = dao.getById(fileId) ?: return@withContext EnrichOutcome.NOT_FOUND
            val videoId = row.youtubeId ?: return@withContext EnrichOutcome.NO_VIDEO_ID
            val meta = oembed.fetchMeta(videoId)
            if (meta == null) {
                dao.getById(fileId)?.let {
                    dao.update(it.copy(scrapeStatus = ScrapeStatus.FAILED, updatedAt = System.currentTimeMillis()))
                }
                Log.w(TAG, "enrich failed: file=$fileId video=$videoId")
                return@withContext EnrichOutcome.NOT_FOUND
            }
            // 封面失败不拉低整体结果:元数据到手即算成功,缩略图尽力而为。
            val thumbPath = oembed.fetchThumbnail(videoId)?.let { thumbStore.write(fileId, it) }
            dao.getById(fileId)?.let { current ->
                dao.update(
                    current.copy(
                        displayTitle = meta.title,
                        titleSortKey = SortKeyGenerator.generate(meta.title),
                        channel = meta.authorName ?: current.channel,
                        sourceUrl = current.sourceUrl ?: "https://youtu.be/$videoId",
                        thumbSource = thumbPath ?: current.thumbSource,
                        scrapeStatus = ScrapeStatus.OK,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            Log.i(TAG, "enrich ok: file=$fileId video=$videoId title='${meta.title}'")
            EnrichOutcome.OK
        } finally {
            running.remove(fileId)
        }
    }

    private companion object {
        const val TAG = "OneAsmrSingleEnrich"
    }
}
