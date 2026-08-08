package com.oneasmr.app.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.oneasmr.app.data.local.CircleDao
import com.oneasmr.app.data.local.DimensionListItem
import com.oneasmr.app.data.local.DimensionWorksPagingSourceFactory
import com.oneasmr.app.data.local.TagDao
import com.oneasmr.app.data.local.VaDao
import com.oneasmr.app.data.local.WorkListItem
import com.oneasmr.app.navigation.Routes
import com.oneasmr.app.ui.common.WorkListRow
import com.oneasmr.app.ui.library.rememberCoverStore
import com.oneasmr.app.ui.library.safeItemKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Browse dimension identifiers shared by navigation, chips and the paging factory. */
object BrowseDimensions {
    const val CIRCLE = "circle"
    const val TAG = "tag"
    const val VA = "va"

    fun label(dimension: String): String = when (dimension) {
        CIRCLE -> "社团"
        TAG -> "标签"
        VA -> "CV"
        else -> "维度"
    }
}

/**
 * Task 16 dimension browse list (`browse/{dimension}`): every circle / tag /
 * CV with its library work count, work count DESC (DAO-level GROUP BY — never
 * in-memory). Live Room flow: a scan or scrape that adds works reorders the
 * list instantly. Unknown dimensions stream an empty list.
 */
@HiltViewModel
class BrowseDimensionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    circleDao: CircleDao,
    tagDao: TagDao,
    vaDao: VaDao,
) : ViewModel() {
    val dimension: String = checkNotNull(savedStateHandle[Routes.BROWSE_ARG_DIMENSION])

    val items: StateFlow<List<DimensionListItem>> = when (dimension) {
        BrowseDimensions.CIRCLE -> circleDao.getAllWithCountsFlow()
        BrowseDimensions.TAG -> tagDao.getAllWithCountsFlow()
        BrowseDimensions.VA -> vaDao.getAllWithCountsFlow()
        else -> MutableStateFlow(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Task 16 dimension works (`browse/{dimension}/{id}`): the paged [WorkListItem]
 * list of one circle / tag / CV (Task 12 row shape — the browse page reuses
 * the library list row verbatim). The dimension name comes from the DAO so the
 * header reads e.g. "社团：社团甲"; a dimension row that no longer exists
 * resolves to a null name and an empty page (empty state, never a crash).
 */
@HiltViewModel
class DimensionWorksViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    pagingSourceFactory: DimensionWorksPagingSourceFactory,
    circleDao: CircleDao,
    tagDao: TagDao,
    vaDao: VaDao,
) : ViewModel() {
    val dimension: String = checkNotNull(savedStateHandle[Routes.BROWSE_ARG_DIMENSION])
    val id: String = checkNotNull(savedStateHandle[Routes.BROWSE_ARG_ID])

    private val _dimensionName = MutableStateFlow<String?>(null)
    val dimensionName: StateFlow<String?> = _dimensionName.asStateFlow()

    val pagingDataFlow: Flow<PagingData<WorkListItem>> =
        Pager(
            config = PagingConfig(pageSize = 30),
            pagingSourceFactory = { pagingSourceFactory.create(dimension, id) },
        ).flow.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            _dimensionName.value = when (dimension) {
                BrowseDimensions.CIRCLE -> circleDao.getById(id)?.name
                BrowseDimensions.TAG -> tagDao.getById(id)?.name
                BrowseDimensions.VA -> vaDao.getById(id)?.name
                else -> null
            }
        }
    }
}

/** Task 16 dimension list page: circle / tag / CV rows with work counts. */
@Composable
fun BrowseDimensionScreen(
    onOpenDimension: (dimension: String, id: String) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowseDimensionViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val dimensionLabel = BrowseDimensions.label(viewModel.dimension)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        BrowseHeader(title = "${dimensionLabel}列表", onBack = onBack)
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有${dimensionLabel}数据",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(count = items.size, key = { items[it].id }) { index ->
                    val item = items[index]
                    DimensionRow(
                        item = item,
                        onClick = { onOpenDimension(viewModel.dimension, item.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Task 16 dimension works page: paged works of one circle / tag / CV. */
@Composable
fun DimensionWorksScreen(
    onOpenWork: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: DimensionWorksViewModel = hiltViewModel(),
) {
    val dimensionName by viewModel.dimensionName.collectAsStateWithLifecycle()
    val items = viewModel.pagingDataFlow.collectAsLazyPagingItems()
    val coverStore = rememberCoverStore()
    val dimensionLabel = BrowseDimensions.label(viewModel.dimension)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        BrowseHeader(
            title = "$dimensionLabel：${dimensionName ?: viewModel.id}",
            onBack = onBack,
        )
        val refresh = items.loadState.refresh
        when {
            refresh is LoadState.Loading && items.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            refresh is LoadState.Error && items.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "加载失败：${refresh.error.message ?: "未知错误"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "该${dimensionLabel}暂无作品",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        count = items.itemCount,
                        key = items.safeItemKey { it.id },
                    ) { index ->
                        val item = items[index]
                        if (item != null) {
                            WorkListRow(
                                item = item,
                                coverStore = coverStore,
                                onClick = { onOpenWork(item.id) },
                                showActions = false,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DimensionRow(item: DimensionListItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${item.workCount} 部作品",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
