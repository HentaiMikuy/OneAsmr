package com.oneasmr.app.ui.singles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.data.local.Collection
import com.oneasmr.app.data.local.CollectionDao
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.PlaybackStateDao
import com.oneasmr.app.data.local.SingleFile
import com.oneasmr.app.navigation.LocalBottomClusterHeight
import com.oneasmr.app.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CollectionUiState(
    val collection: Collection? = null,
    val files: List<SingleFileRow> = emptyList(),
)

/** 收藏夹详情:夹信息 + 按夹内序排列的成员文件。 */
@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collectionDao: CollectionDao,
    playbackStateDao: PlaybackStateDao,
) : ViewModel() {

    private val collectionId: Long =
        checkNotNull(savedStateHandle.get<String>(Routes.COLLECTION_ARG)).toLong()

    // Room 的 Flow 按「表」失效:playback_state 上任何一行写入(哪怕 trackKey
    // 属于作品 "local:" 前缀)都会让上面的前缀查询重跑,结果却逐元素相等。
    // 内层 distinctUntilChanged 丢掉这种无变化的续播重发,combine 便不会因
    // 无关进度写入而重跑 map —— 作品后台每 5s 写进度时本页纹丝不动;
    // 本页文件的进度行真正变化时(结构相等不成立)仍照常刷新。
    val uiState: StateFlow<CollectionUiState> = combine(
        collectionDao.getByIdFlow(collectionId),
        collectionDao.filesInCollectionFlow(collectionId),
        collectionDao.allItemsFlow(),
        playbackStateDao.getAllForWorkFlow("${KeySpec.SINGLE_SOURCE}:").distinctUntilChanged(),
    ) { collection, files, items, playback ->
        val favoriteIds = items
            .filter { it.collectionId == Collection.FAVORITES_ID }
            .map { it.fileId }
            .toSet()
        val progressByKey = playback.associateBy { it.trackKey }
        CollectionUiState(
            collection = collection,
            files = files.map { file ->
                val state = progressByKey[KeySpec.singleFileTrackKey(file.id)]
                SingleFileRow(
                    file = file,
                    isFavorite = file.id in favoriteIds,
                    progressFraction = state
                        ?.takeIf { it.durationMs > 0 }
                        ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) },
                )
            },
        )
    }
        // 外层兜底:其余三路流(夹/夹内文件/夹项)任一路重发相同值时同样跳过。
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState())

    fun removeFromCollection(file: SingleFile) {
        viewModelScope.launch { collectionDao.removeItem(collectionId, file.id) }
    }

    /** 夹内行的爱心与首页同语义:切换系统「收藏」夹成员资格。 */
    fun toggleFavorite(row: SingleFileRow) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            collectionDao.ensureFavorites(now)
            if (row.isFavorite) {
                collectionDao.removeItem(Collection.FAVORITES_ID, row.file.id)
            } else {
                collectionDao.addItem(
                    com.oneasmr.app.data.local.CollectionItem(
                        collectionId = Collection.FAVORITES_ID,
                        fileId = row.file.id,
                        sortIndex = collectionDao.maxItemSortIndex(Collection.FAVORITES_ID) + 1,
                        addedAt = now,
                    ),
                )
            }
        }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { collectionDao.rename(collectionId, trimmed) }
    }

    /** 删除自定义夹(DAO 的 isSystem=0 守卫兜底系统夹)。调用方负责返航。 */
    fun delete() {
        viewModelScope.launch { collectionDao.delete(collectionId) }
    }
}

/**
 * 收藏夹详情页:成员列表按夹内序;行内操作为「从本夹移除」(移出夹不
 * 影响库与其它夹 —— 播放列表语义)。自定义夹可改名/删除,系统「收藏」
 * 夹两者皆不可。
 */
@Composable
fun CollectionScreen(
    onOpenFile: (fileId: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val collection = state.collection

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    collection?.name ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${state.files.size} 个文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (collection != null && !collection.isSystem) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "收藏夹操作")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                menu = false
                                renameDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除收藏夹") },
                            onClick = {
                                menu = false
                                deleteDialog = true
                            },
                        )
                    }
                }
            }
        }

        if (state.files.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                Text("这个收藏夹还是空的", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "在单档列表里用爱心或「加入收藏夹…」把文件收进来。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalBottomClusterHeight.current),
            ) {
                items(state.files, key = { it.file.id }) { row ->
                    SingleFileListRow(
                        row = row,
                        onClick = { onOpenFile(row.file.id) },
                        onToggleFavorite = { viewModel.toggleFavorite(row) },
                        overflowActions = listOf(
                            RowAction("从本夹移除") { viewModel.removeFromCollection(row.file) },
                        ),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (renameDialog && collection != null) {
        var name by remember { mutableStateOf(collection.name) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名收藏夹") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(name)
                        renameDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } },
        )
    }
    if (deleteDialog && collection != null) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("删除收藏夹？") },
            text = { Text("仅删除「${collection.name}」这个夹子,夹内文件仍留在单档库。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDialog = false
                        viewModel.delete()
                        onBack()
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } },
        )
    }
}
