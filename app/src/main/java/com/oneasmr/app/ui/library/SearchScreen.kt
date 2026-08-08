package com.oneasmr.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.WorkListItem
import com.oneasmr.app.data.local.WorkOrder
import com.oneasmr.app.data.local.WorkPagingSourceFactory
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.repository.CoverType
import com.oneasmr.app.ui.common.CoverImage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Task 13 search: type-to-search with a 300ms debounce (blank clears
 * instantly), hit rules per plan — a pure (RJ|BJ|VJ)?\d{6,8} is a direct code
 * lookup (with FTS fallback on a miss, e.g. 7-digit input), everything else
 * runs the FTS5 trigram search over title/circle/tag/va (Task 4's four-table
 * UNION). Results are paged with the SHARED library sort (order + direction
 * persisted in [SettingsStore]), so sort+search composition is a single
 * DAO-level query (order field + keyword), never in-memory filtering.
 *
 * Debounce runs on the ViewModel scope's dispatcher, which unit tests
 * replace with a StandardTestDispatcher — the 300ms is virtual-clock time,
 * no real sleeps (repo flake convention). DAO queries execute on Room's
 * query executor (suspend DAO contract), never the main thread.
 *
 * FTS5 trigram UX note: a query shorter than 3 characters can never match
 * (SQLite trigram tokenizer emits no tokens for 1-2 char input) — such
 * queries short-circuit to a hint phase instead of an empty page.
 */
sealed interface SearchPhase {
    data object Idle : SearchPhase
    data object ShortQuery : SearchPhase
    data object Loading : SearchPhase
    data object Results : SearchPhase
}

data class SearchUiState(
    val phase: SearchPhase,
    val query: String,
    val directHit: WorkListItem?,
) {
    companion object {
        val EMPTY = SearchUiState(SearchPhase.Idle, "", null)
    }
}

/** Internal search pipeline step: what the settled query resolves to. */
private sealed interface SearchMeta {
    data object Idle : SearchMeta
    data class ShortQuery(val term: String) : SearchMeta
    data class DirectHit(val term: String, val work: WorkListItem) : SearchMeta
    data class Searching(val term: String) : SearchMeta
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val workDao: WorkDao,
    private val settingsStore: SettingsStore,
    pagingSourceFactory: WorkPagingSourceFactory,
) : ViewModel() {

    /** Session-stable random seed (plan: 随机排序同一会话内稳定); the DAO clamps it nonzero. */
    private val randomSeed: Long = Random.nextLong()

    private val queryInput = MutableStateFlow("")

    /** Raw text field value (what the user typed, including trailing spaces). */
    val queryText: StateFlow<String> = queryInput.asStateFlow()

    /** Settled, trimmed query after the 300ms debounce (0ms for blank = instant clear). */
    private val settledQuery: Flow<String> = queryInput
        .debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }
        .distinctUntilChanged()
        .map { it.trim() }

    /** Resolves the settled query: direct code probe (with FTS fallback on miss) or FTS. */
    private val searchMeta: Flow<SearchMeta> = settledQuery.flatMapLatest { term ->
        when {
            term.isEmpty() -> flowOf(SearchMeta.Idle)
            term.length < MIN_FTS_CHARS -> flowOf(SearchMeta.ShortQuery(term))
            else -> flow {
                val intent = SearchIntentClassifier.classify(term)
                when (intent) {
                    is SearchIntentClassifier.Intent.DirectCode -> {
                        val hit = probeDirectCode(intent.codes)
                        if (hit != null) emit(SearchMeta.DirectHit(term, hit))
                        else emit(SearchMeta.Searching(term))
                    }
                    is SearchIntentClassifier.Intent.FtsQuery -> emit(SearchMeta.Searching(term))
                }
            }
        }
    }

    /**
     * Paged result stream tagged with the term it belongs to, ordered by the
     * shared library sort. Restarts (flatMapLatest) whenever the settled
     * query OR the sort selection changes — sort + search composition at the
     * DAO level.
     */
    private val paged: Flow<Pair<String, PagingData<WorkListItem>>> = combine(
        searchMeta,
        settingsStore.librarySortOrder,
        settingsStore.librarySortDescending,
    ) { meta, order, descending -> Triple(meta, order, descending) }
        .flatMapLatest { (meta, order, descending) ->
            if (meta is SearchMeta.Searching) {
                Pager(
                    config = PagingConfig(pageSize = PAGE_SIZE),
                    pagingSourceFactory = {
                        pagingSourceFactory.create(order, descending, meta.term, randomSeed, filter = null)
                    },
                ).flow.map { meta.term to it }
            } else {
                flowOf("" to PagingData.empty())
            }
        }

    /**
     * Combined UI state: the meta phase (idle / short-query / loading /
     * results with direct hit). The RESULTS phase is only entered once the
     * paged stream carries the SAME term, so stale results from the previous
     * query never render under the new one. PagingData itself deliberately
     * does NOT ride in this state — a PagingData instance is single-shot and
     * must be collected through the shared [pagingDataFlow] instead.
     */
    val uiState: StateFlow<SearchUiState> = combine(searchMeta, paged) { meta, (pagedTerm, _) ->
        when (meta) {
            SearchMeta.Idle -> SearchUiState(SearchPhase.Idle, "", null)
            is SearchMeta.ShortQuery -> SearchUiState(SearchPhase.ShortQuery, meta.term, null)
            is SearchMeta.DirectHit -> SearchUiState(SearchPhase.Results, meta.term, meta.work)
            is SearchMeta.Searching ->
                if (pagedTerm == meta.term) SearchUiState(SearchPhase.Results, meta.term, null)
                else SearchUiState(SearchPhase.Loading, meta.term, null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.EMPTY)

    /**
     * Paged results for [collectAsLazyPagingItems]. Shared via cachedIn so
     * every consumer (UI + tests) collects the PagingData stream through one
     * collector — a PagingData instance cannot be collected twice, and
     * StateFlow embedding would replay the same instance on re-subscription.
     */
    val pagingDataFlow: Flow<PagingData<WorkListItem>> =
        paged.map { it.second }.cachedIn(viewModelScope)

    val sortOrder: StateFlow<WorkOrder> =
        settingsStore.librarySortOrder.stateIn(viewModelScope, SharingStarted.Eagerly, WorkOrder.ID)

    val sortDescending: StateFlow<Boolean> =
        settingsStore.librarySortDescending.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val recentSearches: StateFlow<List<String>> =
        settingsStore.recentSearches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Persist every settled non-blank search term (>= 3 chars), newest
        // first, capped by SettingsStore; clearable from the UI.
        viewModelScope.launch {
            settledQuery.collect { term ->
                if (term.length >= MIN_FTS_CHARS) settingsStore.addRecentSearch(term)
            }
        }
    }

    fun onQueryChange(value: String) {
        queryInput.value = value
    }

    fun setSort(order: WorkOrder, descending: Boolean) {
        viewModelScope.launch { settingsStore.setLibrarySort(order, descending) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { settingsStore.clearRecentSearches() }
    }

    /** Exact id probe over the candidate codes ("local:" + canonical key). */
    private suspend fun probeDirectCode(codes: List<String>): WorkListItem? {
        for (code in codes) {
            val hit = workDao.getListItemById(KeySpec.workId(KeySpec.LOCAL_SOURCE, code))
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        const val MIN_FTS_CHARS = 3
        const val PAGE_SIZE = 30
    }
}

@Composable
fun SearchScreen(
    onOpenWork: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val recent by viewModel.recentSearches.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()
    val lazyItems = viewModel.pagingDataFlow.collectAsLazyPagingItems()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Text(
            "搜索",
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索标题 / 社团 / 标签 / CV / 编号") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                ),
            )
            SortMenu(
                order = sortOrder,
                descending = sortDescending,
                onSelect = viewModel::setSort,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        when (state.phase) {
            SearchPhase.Idle -> RecentSearches(
                terms = recent,
                onPick = viewModel::onQueryChange,
                onClear = viewModel::clearRecentSearches,
            )
            SearchPhase.ShortQuery -> CenteredMessage(
                title = "关键词太短",
                message = "FTS trigram 索引要求至少 3 个字符（1-2 个字符的查询按 SQLite 设计不会命中）。",
            )
            SearchPhase.Loading -> CenteredMessage(title = null, message = null, loading = true)
            SearchPhase.Results -> {
                val direct = state.directHit
                if (direct != null) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "编号直查命中",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        WorkSearchRow(direct, onClick = { onOpenWork(direct.id) })
                    }
                } else {
                    val itemCount = lazyItems.itemCount
                    when {
                        lazyItems.loadState.refresh is LoadState.Loading -> CenteredMessage(
                            title = null, message = null, loading = true,
                        )
                        itemCount == 0 -> CenteredMessage(
                            title = "没有找到与「${state.query}」相关的作品",
                            message = "试试更短的关键词，或直接输入 RJ 编号。",
                        )
                        else -> {
                            Text(
                                "搜索结果（按当前排序）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(
                                    count = itemCount,
                                    key = lazyItems.safeItemKey { it.id },
                                ) { index ->
                                    val item = lazyItems[index]
                                    if (item != null) {
                                        WorkSearchRow(item, onClick = { onOpenWork(item.id) })
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(terms: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (terms.isEmpty()) {
            Text(
                "输入关键词搜索作品：支持编号直查（如 RJ123456）、以及按标题 / 社团 / 标签 / CV 名称搜索。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近搜索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("清除") }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                terms.forEach { term ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onPick(term) },
                    ) {
                        Text(
                            term,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(title: String?, message: String?, loading: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Result row (also used for the direct-code hit): Wave B row anatomy. */
@Composable
private fun WorkSearchRow(item: WorkListItem, onClick: () -> Unit) {
    val coverStore = rememberCoverStore()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            coverStore = coverStore,
            rjCode = item.rjCode,
            type = CoverType.THUMB_240,
            rootFolderUri = item.rootFolderUri,
            relativeDir = item.relativeDir,
            modifier = Modifier
                .size(width = 56.dp, height = 56.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(item.rjCode) }
                    item.circleName?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.rateAverage2dp?.let {
            Text(
                "★ ${String.format(java.util.Locale.US, "%.2f", it)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
