package com.oneasmr.app.ui.singles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.Collection
import com.oneasmr.app.data.local.CollectionDao
import com.oneasmr.app.data.local.CollectionItem
import com.oneasmr.app.data.local.CollectionWithCount
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.PlaybackStateDao
import com.oneasmr.app.data.local.SingleFile
import com.oneasmr.app.data.local.SingleFileDao
import com.oneasmr.app.data.repository.EnrichOutcome
import com.oneasmr.app.data.repository.RootGrantStatus
import com.oneasmr.app.data.repository.ScanRootKind
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.repository.SingleFileEnricher
import com.oneasmr.app.data.scanner.SingleThumbStore
import com.oneasmr.app.data.scanner.removeSingleFile
import com.oneasmr.app.domain.singlefile.YouTubeLinkParser
import com.oneasmr.app.worker.ScanLibraryWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 全部文件列表的排序模式(仅会话内,不落盘)。 */
enum class SinglesOrder(val label: String) {
    ADDED_DESC("最近加入"),
    TITLE("标题"),
    DURATION_DESC("时长"),
}

/** 一行文件 + 派生的展示态(收藏、续播进度)。 */
data class SingleFileRow(
    val file: SingleFile,
    val isFavorite: Boolean,
    /** 0..1 续播进度;无记录为 null(行内不画进度条)。 */
    val progressFraction: Float?,
)

data class SinglesUiState(
    val collections: List<CollectionWithCount> = emptyList(),
    val files: List<SingleFileRow> = emptyList(),
    val order: SinglesOrder = SinglesOrder.ADDED_DESC,
    /** fileId -> 所属收藏夹 id 集(归属对话框勾选态的种子)。 */
    val memberships: Map<Long, Set<Long>> = emptyMap(),
    /** 是否已配置任何单文件库根(空态文案分流:去设置 vs 去扫描)。 */
    val hasSingleRoot: Boolean = false,
)

/**
 * 单档库首页数据流:整表流 + 内存派生(排序/收藏态/进度),量级为
 * 下载目录的数百文件,刻意不用作品列表的 Paging 机制。
 *
 * 收藏 = 系统收藏夹([Collection.FAVORITES_ID])的成员资格;爱心按钮是
 * 「切换该夹成员」的快捷方式,与自定义夹共用一条数据通路。
 */
@HiltViewModel
class SinglesViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val db: OneAsmrDatabase,
    private val singleFileDao: SingleFileDao,
    private val collectionDao: CollectionDao,
    private val enricher: SingleFileEnricher,
    playbackStateDao: PlaybackStateDao,
    rootRepository: ScanRootRepository,
) : ViewModel() {

    private val thumbStore =
        SingleThumbStore(File(context.filesDir, ScanLibraryWorker.SINGLE_THUMBS_DIR))

    private val order = MutableStateFlow(SinglesOrder.ADDED_DESC)

    /** 一次性提示文案(补全结果等);UI 消费后置 null。 */
    val message = MutableStateFlow<String?>(null)

    // Room 的 Flow 按「表」失效:playback_state 上任何一行写入(哪怕 trackKey
    // 属于作品 "local:" 前缀)都会让上面的前缀查询重跑,结果却逐元素相等。
    // 内层 distinctUntilChanged 丢掉这种无变化的续播重发,combine 便不会
    // 因无关进度写入而重跑 map+sort —— 作品后台每 5s 写进度时首页纹丝不动;
    // 本页文件的进度行真正变化时(结构相等不成立)仍照常刷新。
    val uiState: StateFlow<SinglesUiState> = combine(
        singleFileDao.getAllFlow(),
        collectionDao.collectionsWithCountFlow(),
        collectionDao.allItemsFlow(),
        playbackStateDao.getAllForWorkFlow("${KeySpec.SINGLE_SOURCE}:").distinctUntilChanged(),
        order,
    ) { files, collections, items, playback, order ->
        val favoriteIds = items
            .filter { it.collectionId == Collection.FAVORITES_ID }
            .map { it.fileId }
            .toSet()
        val progressByKey = playback.associateBy { it.trackKey }
        val rows = files.map { file ->
            val state = progressByKey[KeySpec.singleFileTrackKey(file.id)]
            SingleFileRow(
                file = file,
                isFavorite = file.id in favoriteIds,
                progressFraction = state
                    ?.takeIf { it.durationMs > 0 }
                    ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) },
            )
        }
        SinglesUiState(
            collections = collections,
            files = sort(rows, order),
            order = order,
            memberships = items.groupBy({ it.fileId }, { it.collectionId }).mapValues { it.value.toSet() },
            hasSingleRoot = rootRepository.entries.value.any {
                it.status == RootGrantStatus.AUTHORIZED && it.root.kind != ScanRootKind.WORKS
            },
        )
    }
        // 外层兜底:其余四路流(文件/收藏/夹项/排序)任一路重发相同值时同样跳过,
        // 状态只在新值真正不同时才前进,零变化期间零 map/sort 开销。
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SinglesUiState())

    private fun sort(rows: List<SingleFileRow>, order: SinglesOrder): List<SingleFileRow> =
        when (order) {
            SinglesOrder.ADDED_DESC -> rows.sortedWith(
                compareByDescending<SingleFileRow> { it.file.addedAt }.thenBy { it.file.id },
            )
            SinglesOrder.TITLE -> rows.sortedWith(
                compareBy<SingleFileRow> { it.file.titleSortKey }.thenBy { it.file.id },
            )
            SinglesOrder.DURATION_DESC -> rows.sortedWith(
                // 未知时长沉底(compareByDescending 会把 null 当最小,恰好符合)。
                compareByDescending<SingleFileRow> { it.file.durationMs ?: -1L }.thenBy { it.file.id },
            )
        }

    fun setOrder(newOrder: SinglesOrder) {
        order.value = newOrder
    }

    fun toggleFavorite(row: SingleFileRow) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            collectionDao.ensureFavorites(now)
            if (row.isFavorite) {
                collectionDao.removeItem(Collection.FAVORITES_ID, row.file.id)
            } else {
                addTo(Collection.FAVORITES_ID, row.file.id)
            }
        }
    }

    fun setMembership(fileId: Long, collectionId: Long, member: Boolean) {
        viewModelScope.launch {
            if (member) addTo(collectionId, fileId) else collectionDao.removeItem(collectionId, fileId)
        }
    }

    /** 建自定义夹;空白名忽略。@return 无(UI 乐观关闭对话框,流自会刷新)。 */
    fun createCollection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            collectionDao.ensureFavorites(now)
            collectionDao.insert(
                Collection(
                    name = trimmed,
                    isSystem = false,
                    sortIndex = collectionDao.maxSortIndex() + 1,
                    createdAt = now,
                ),
            )
        }
    }

    /** 手动移除条目(不删磁盘文件):行 + 成员 + 播放位置 + 缩略图。 */
    fun removeFile(fileId: Long) {
        viewModelScope.launch { removeSingleFile(db, thumbStore, fileId) }
    }

    /**
     * 批量移除全部失效条目(重扫后 missing=1 的行)。典型场景:根类型
     * 纠正后(如误当单文件库扫过的混合目录),作品内音轨在下次重扫时
     * 集体失效 —— 逐条删太费劲。仍是手动触发,绝不自动执行。
     */
    fun removeAllMissing() {
        viewModelScope.launch {
            val missing = singleFileDao.getAll().filter { it.missing }
            missing.forEach { removeSingleFile(db, thumbStore, it.id) }
            message.value = if (missing.isEmpty()) "没有失效条目" else "已移除 ${missing.size} 个失效条目"
        }
    }

    /** 在线补全(需行已有 youtubeId;无 ID 的行走 [bindLink])。 */
    fun enrich(fileId: Long) {
        viewModelScope.launch {
            message.value = when (enricher.enrich(fileId)) {
                EnrichOutcome.OK -> "已补全标题/频道/封面"
                EnrichOutcome.NO_VIDEO_ID -> "该文件没有视频 ID,请先关联视频链接"
                EnrichOutcome.NOT_FOUND -> "补全失败:视频不存在或网络异常,可重试"
                EnrichOutcome.ALREADY_RUNNING -> null
            }
        }
    }

    /**
     * 关联视频链接:解析用户粘贴的 URL/ID,绑定后顺势补全。
     * 解析失败只提示,不写任何东西。
     */
    fun bindLink(fileId: Long, input: String) {
        val videoId = YouTubeLinkParser.extractId(input)
        if (videoId == null) {
            message.value = "无法识别视频链接,请粘贴完整的 YouTube 链接或 11 位视频 ID"
            return
        }
        viewModelScope.launch {
            message.value = when (enricher.bindAndEnrich(fileId, videoId)) {
                EnrichOutcome.OK -> "已关联并补全"
                EnrichOutcome.NOT_FOUND -> "已关联,但补全失败(视频不存在或网络异常),可重试"
                EnrichOutcome.NO_VIDEO_ID, EnrichOutcome.ALREADY_RUNNING -> null
            }
        }
    }

    private suspend fun addTo(collectionId: Long, fileId: Long) {
        collectionDao.addItem(
            CollectionItem(
                collectionId = collectionId,
                fileId = fileId,
                sortIndex = collectionDao.maxItemSortIndex(collectionId) + 1,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }
}
