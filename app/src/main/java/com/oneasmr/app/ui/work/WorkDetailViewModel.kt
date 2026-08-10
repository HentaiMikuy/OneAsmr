package com.oneasmr.app.ui.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.CircleDao
import com.oneasmr.app.data.local.AgeRating
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.PlaybackStateDao
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.local.ReviewDao
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.TagDao
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.VaDao
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.WorkTagDao
import com.oneasmr.app.data.local.WorkVaDao
import com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException
import com.oneasmr.app.data.repository.RootGrantStatus
import com.oneasmr.app.data.repository.ScanRootKind
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.repository.ScrapeOutcome
import com.oneasmr.app.data.repository.SingleWorkScraper
import com.oneasmr.app.data.scanner.IncrementalRescanner
import com.oneasmr.app.data.scanner.RefreshResult
import com.oneasmr.app.data.scanner.RoomScanPersister
import com.oneasmr.app.data.scanner.ScanRoot
import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackTreeBuilder
import com.oneasmr.app.data.scanner.TrackTreeResult
import com.oneasmr.app.data.scanner.WorkPathResolver
import com.oneasmr.app.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Track-tree section state (plan Task 14: live scan at request time). */
sealed interface TrackTreeUiState {
    data object Idle : TrackTreeUiState
    data object Loading : TrackTreeUiState

    /** [expanded] holds the relativePath of every EXPANDED folder ("", "CD1", ...). */
    data class Ready(val root: TrackNode, val expanded: Set<String>) : TrackTreeUiState

    data class Error(val message: String) : TrackTreeUiState
}

/**
 * One track's remembered playback position (Task 19 detail-page progress
 * bar). [fraction] is null while there is nothing meaningful to show (no
 * position, or the player never prepared the item).
 */
data class TrackProgress(val positionMs: Long, val durationMs: Long) {
    val fraction: Float?
        get() = if (durationMs > 0L && positionMs > 0L) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            null
        }
}

/** Why the detail page cannot show the work (plan: invalid state, never a blank page). */
sealed interface InvalidReason {
    /** The work id is not in the library at all. */
    data object NotFoundInLibrary : InvalidReason

    /** The work folder was absent from the last full rescan (Task 7 missing flag). */
    data object MissingWork : InvalidReason

    /** Folder lookup/tree read failed (moved, unreadable root...). */
    data class FolderUnavailable(val detail: String) : InvalidReason
}

data class WorkDetailUiState(
    val loading: Boolean = true,
    val work: Work? = null,
    val circleName: String? = null,
    val tags: List<Tag> = emptyList(),
    val vas: List<Va> = emptyList(),
    val tree: TrackTreeUiState = TrackTreeUiState.Idle,
    val scraping: Boolean = false,
    val scrapeMessage: String? = null,
    val refreshing: Boolean = false,
    val rescanMessage: String? = null,
    /** Live review row (Task 15); null = no review yet. */
    val review: Review? = null,
    /** Editable review-text draft (user-owned once initialized; see VM init). */
    val reviewText: String = "",
    /** The draft was seeded from the stored review — never clobber typing again. */
    val reviewInitialized: Boolean = false,
    /** Task 19: remembered playback positions by trackIndex (detail-page progress bars). */
    val trackProgress: Map<Int, TrackProgress> = emptyMap(),
) {
    /** Derived invalid state — the screen renders the rescan CTA instead of content. */
    val invalid: InvalidReason? get() = when {
        loading -> null
        work == null -> InvalidReason.NotFoundInLibrary
        work.missing -> InvalidReason.MissingWork
        tree is TrackTreeUiState.Error -> InvalidReason.FolderUnavailable((tree as TrackTreeUiState.Error).message)
        else -> null
    }
}

/** Raised when the work folder cannot be resolved on disk (message is user-facing). */
private class WorkFolderUnavailable(message: String) : Exception(message)

/**
 * Task 14 work detail page state.
 *
 * - Work row: live Room flow (metadata refreshes when scrape/rescan writes it).
 * - Track tree: built ON DEMAND from the live filesystem via [TrackTreeBuilder]
 *   (tracks are never stored in the DB — plan Task 6). Default expansion
 *   shows the root and first-level folders, deep folders collapsed.
 * - Missing/invalid: [WorkDetailUiState.invalid] drives the invalid-state UI;
 *   [refreshWork] re-runs IncrementalRescanner.refreshWork (Task 7 API) — the
 *   plan's "folder moved → invalid state + rescan entry, not a white screen".
 * - Scrape: delegates to [SingleWorkScraper] (Task 11 ScrapeRepository) — never
 *   reimplemented here.
 *
 * All fs walking runs on [ioDispatcher] (injectable so tests use the virtual
 * scheduler — repo flake convention, no real-time waits).
 */
@HiltViewModel
class WorkDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workDao: WorkDao,
    private val db: OneAsmrDatabase,
    private val circleDao: CircleDao,
    private val tagDao: TagDao,
    private val vaDao: VaDao,
    private val workTagDao: WorkTagDao,
    private val workVaDao: WorkVaDao,
    private val reviewDao: ReviewDao,
    private val playbackStateDao: PlaybackStateDao,
    private val rootRepository: ScanRootRepository,
    private val scraper: SingleWorkScraper,
    private val fsFactory: DocumentFsFactory,
) : ViewModel() {

    /** Test-only: full constructor with an injectable dispatcher/clock (unit tests only). */
    internal constructor(
        savedStateHandle: SavedStateHandle,
        workDao: WorkDao,
        db: OneAsmrDatabase,
        circleDao: CircleDao,
        tagDao: TagDao,
        vaDao: VaDao,
        workTagDao: WorkTagDao,
        workVaDao: WorkVaDao,
        reviewDao: ReviewDao,
        playbackStateDao: PlaybackStateDao,
        rootRepository: ScanRootRepository,
        scraper: SingleWorkScraper,
        fsFactory: DocumentFsFactory,
        ioDispatcher: CoroutineDispatcher,
        clock: () -> Long,
    ) : this(savedStateHandle, workDao, db, circleDao, tagDao, vaDao, workTagDao, workVaDao, reviewDao, playbackStateDao, rootRepository, scraper, fsFactory) {
        this.ioDispatcher = ioDispatcher
        this.clock = clock
    }

    // NOT Dagger-injected: Dagger cannot bind Kotlin function types from
    // @Inject constructors (Function0 wildcard mismatch — repo learning), and
    // defaults are ignored anyway. Production values below; the test-only
    // constructor above overrides them with the virtual scheduler.
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var clock: () -> Long = { System.currentTimeMillis() }

    private val workId: String = checkNotNull(savedStateHandle[Routes.WORK_DETAIL_ARG])

    private val _uiState = MutableStateFlow(WorkDetailUiState())
    val uiState: StateFlow<WorkDetailUiState> = _uiState.asStateFlow()

    /** Guard: rebuild the tree only when root/relativeDir/missing actually changed. */
    private var builtTreeFor: String? = null

    init {
        viewModelScope.launch {
            workDao.getByIdFlow(workId).collect { work ->
                if (work == null) {
                    _uiState.update { it.copy(loading = false, work = null) }
                    return@collect
                }
                _uiState.update { it.copy(loading = false, work = work) }
                loadMetadata(work)
                maybeLoadTree(work)
            }
        }
        viewModelScope.launch {
            reviewDao.getByWorkIdFlow(workId).collect { review ->
                _uiState.update { state ->
                    if (!state.reviewInitialized) {
                        state.copy(
                            review = review,
                            reviewText = review?.reviewText ?: "",
                            reviewInitialized = true,
                        )
                    } else {
                        state.copy(review = review)
                    }
                }
            }
        }
        // Task 19: live playback positions for THIS work's tracks (Room
        // invalidation re-emits when PlaybackService writes playback_state).
        viewModelScope.launch {
            playbackStateDao.getAllForWorkFlow("$workId:").collect { rows ->
                val byIndex = rows.mapNotNull { row ->
                    KeySpec.parseTrackKey(row.trackKey)?.trackIndex
                        ?.let { it to TrackProgress(row.positionMs, row.durationMs) }
                }.toMap()
                _uiState.update { it.copy(trackProgress = byIndex) }
            }
        }
    }

    /**
     * Task 15 review persistence: rating / progress / review text are ONE
     * review row upserted through the validated [ReviewDao] (the DAO is the
     * single enforcement point for the 1-5 rating range — the stars UI only
     * ever produces 1-5). The draft text always rides along, so tapping a
     * star can never drop an unsaved comment. updatedAt = injectable clock.
     */
    fun setRating(rating: Int?) {
        val state = _uiState.value
        persistReview(rating = rating, progress = state.review?.progress ?: ProgressState.none)
    }

    fun setProgress(progress: ProgressState) {
        val state = _uiState.value
        persistReview(rating = state.review?.rating, progress = progress)
    }

    fun onReviewTextChange(text: String) {
        _uiState.update { it.copy(reviewText = text) }
    }

    fun saveReviewText() {
        val state = _uiState.value
        persistReview(rating = state.review?.rating, progress = state.review?.progress ?: ProgressState.none)
    }

    /** Deletes the review row (missing works included — plan failure path). */
    fun clearReview() {
        viewModelScope.launch {
            reviewDao.deleteByWorkId(workId)
            _uiState.update { it.copy(review = null, reviewText = "", reviewInitialized = true) }
        }
    }

    private fun persistReview(rating: Int?, progress: ProgressState) {
        viewModelScope.launch {
            reviewDao.upsert(Review(workId = workId, rating = rating, reviewText = _uiState.value.reviewText, progress = progress, updatedAt = clock()))
        }
    }

    /**
     * 手动年龄分级:写入 work.ageRating;null 清除。行流(getByIdFlow)自动
     * 重发,uiState.work 随之刷新,无需手动更新状态。
     */
    fun setAgeRating(rating: AgeRating?) {
        val work = _uiState.value.work ?: return
        viewModelScope.launch { workDao.updateAgeRating(work.id, rating, clock()) }
    }

    fun toggleFolder(relativePath: String) {
        val current = _uiState.value.tree
        if (current !is TrackTreeUiState.Ready) return
        val expanded = if (relativePath in current.expanded) {
            current.expanded - relativePath
        } else {
            current.expanded + relativePath
        }
        _uiState.update { it.copy(tree = current.copy(expanded = expanded)) }
    }

    /**
     * Single-work rescan entry (the invalid-state CTA): re-verifies the work
     * folder through [IncrementalRescanner.refreshWork] — Refreshed/Unchanged
     * clears the missing flag and the work flow reloads the tree; MarkedMissing
     * keeps the invalid state; Failed shows the reason. Always rebuilds the
     * tree afterwards (the folder may have been temporarily unreadable).
     */
    fun refreshWork() {
        val work = _uiState.value.work ?: return
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true, rescanMessage = null) }
        viewModelScope.launch {
            // 只喂含作品的根(作品库/混合库):纯单文件根里不存在 RJ 作品
            // 文件夹,白走一遍还可能把「所有根都完整枚举但没找到」误判成
            // 失效。
            val roots = rootRepository.entries.value
                .filter { it.status == RootGrantStatus.AUTHORIZED && it.root.kind != ScanRootKind.SINGLE_FILES }
                .map { ScanRoot(treeUri = it.root.treeUri, displayName = it.root.displayName) }
            val result = if (roots.isEmpty()) {
                RefreshResult.Failed("没有已授权的根文件夹，请先在设置中添加")
            } else {
                val rescanner = IncrementalRescanner(
                    fsFactory = { treeUri -> fsFactory.create(treeUri) },
                    persister = RoomScanPersister(db),
                    workDao = workDao,
                )
                withContext(ioDispatcher) {
                    rescanner.refreshWork(work.rjCode(), roots, clock())
                }
            }
            builtTreeFor = null
            _uiState.update {
                it.copy(
                    refreshing = false,
                    rescanMessage = when (result) {
                        is RefreshResult.Refreshed -> "已重新找到该作品并刷新"
                        is RefreshResult.Unchanged -> "作品位置未变化"
                        is RefreshResult.MarkedMissing -> "未找到该作品文件夹，已标记为失效"
                        is RefreshResult.Failed -> "重扫失败：${result.message}"
                    },
                )
            }
            // Unchanged/Refreshed commit no row change (or one whose emission races
            // us): a stale Error tree must rebuild RIGHT NOW, not wait for a Room
            // emission that may never arrive (Unchanged writes nothing).
            val workNow = _uiState.value.work ?: return@launch
            if (result is RefreshResult.Unchanged || result is RefreshResult.Refreshed) {
                maybeLoadTree(workNow)
            }
        }
    }

    /** Single-work scrape (Task 11 pattern): delegates to [SingleWorkScraper]. */
    fun scrape() {
        val work = _uiState.value.work ?: return
        if (_uiState.value.scraping) return
        _uiState.update { it.copy(scraping = true, scrapeMessage = null) }
        viewModelScope.launch {
            val outcome = scraper.scrapeOne(work.id)
            _uiState.update {
                it.copy(
                    scraping = false,
                    scrapeMessage = when (outcome) {
                        is ScrapeOutcome.Success -> "刮削成功：${outcome.rjCode}"
                        is ScrapeOutcome.Failed -> "刮削失败：${failureLabel(outcome.kind)}"
                    },
                )
            }
        }
    }

    fun consumeScrapeMessage() {
        _uiState.update { it.copy(scrapeMessage = null) }
    }

    fun consumeRescanMessage() {
        _uiState.update { it.copy(rescanMessage = null) }
    }

    private fun maybeLoadTree(work: Work) {
        if (work.missing) {
            builtTreeFor = null
            return
        }
        val key = workTreeKey(work)
        if (builtTreeFor == key) return
        builtTreeFor = key
        _uiState.update { it.copy(tree = TrackTreeUiState.Loading) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(ioDispatcher) {
                    val fs = fsFactory.create(work.rootFolderUri)
                    when (val resolved = WorkPathResolver(fs).resolve(work.relativeDir)) {
                        is WorkPathResolver.Result.NotFound -> throw WorkFolderUnavailable(resolved.message)
                        is WorkPathResolver.Result.Found ->
                            TrackTreeBuilder(fs).build(resolved.path, resolved.displayName, resolved.documentUri)
                    }
                }
            }
            outcome
                .onSuccess { treeResult -> onTreeReady(treeResult) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    val detail = (e as? WorkFolderUnavailable)?.message ?: (e.message ?: "读取作品文件夹失败")
                    _uiState.update { it.copy(tree = TrackTreeUiState.Error(detail)) }
                }
        }
    }

    private fun onTreeReady(treeResult: TrackTreeResult) {
        _uiState.update {
            it.copy(tree = TrackTreeUiState.Ready(treeResult.root, initialExpansion(treeResult.root)))
        }
    }

    /** Root + first-level folders expanded; deeper folders collapsed (plan: 默认折叠深层目录). */
    private fun initialExpansion(root: TrackNode): Set<String> {
        val expanded = mutableSetOf("")
        fun collect(node: TrackNode, depth: Int) {
            if (node.isFolder && depth <= 1) expanded += node.relativePath
            node.children.forEach { collect(it, depth + 1) }
        }
        root.children.forEach { collect(it, 1) }
        return expanded
    }

    private fun loadMetadata(work: Work) {
        viewModelScope.launch {
            val circleName = work.circleId?.let { circleDao.getById(it)?.name }
            val tags = workTagDao.getTagIdsByWork(work.id).mapNotNull { tagDao.getById(it) }
            val vas = workVaDao.getVaIdsByWork(work.id).mapNotNull { vaDao.getById(it) }
            _uiState.update { it.copy(circleName = circleName, tags = tags, vas = vas) }
        }
    }

    private fun workTreeKey(work: Work): String =
        "${work.rootFolderUri}|${work.relativeDir}|${work.missing}"

    private fun Work.rjCode(): String = KeySpec.parseWorkId(id)?.rjCode ?: id

    private fun failureLabel(kind: DlsiteScrapeException.Kind?): String = when (kind) {
        DlsiteScrapeException.Kind.NETWORK -> "网络错误"
        DlsiteScrapeException.Kind.NOT_FOUND -> "作品不存在(404)"
        DlsiteScrapeException.Kind.BLOCKED -> "被反爬拦截(403)"
        DlsiteScrapeException.Kind.PARSE_ERROR -> "页面解析失败"
        null -> "未知错误"
    }
}
