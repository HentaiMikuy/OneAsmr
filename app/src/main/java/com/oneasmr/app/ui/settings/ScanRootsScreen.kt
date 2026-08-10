package com.oneasmr.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.repository.AddRootResult
import com.oneasmr.app.data.repository.RootGrantStatus
import com.oneasmr.app.data.repository.ScanRootEntry
import com.oneasmr.app.data.repository.ScanRootKind
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.scanner.RescanSummary
import com.oneasmr.app.data.scanner.ScanBookkeepingStore
import com.oneasmr.app.data.scanner.ScanPhase
import com.oneasmr.app.data.scanner.ScanProgress
import com.oneasmr.app.data.scanner.ScanProgressStore
import com.oneasmr.app.ui.common.formatScanSummary
import com.oneasmr.app.worker.ScanController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * SAF root-folder management (plan Task 5): the system folder picker via
 * [ActivityResultContracts.OpenDocumentTree], the in-app "authorized root
 * folders" list (this list + the OneAsmrScanRoots logcat dumps are the
 * acceptance evidence channel — persisted grants cannot be read via adb),
 * remove-with-confirm, and an explicit REVOKED state for roots whose grant
 * was revoked in system settings (never silently dropped).
 */
@Composable
fun ScanRootsScreen(viewModel: ScanRootsViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val lastSummary by viewModel.lastSummary.collectAsStateWithLifecycle(initialValue = null)
    var removeCandidate by remember { mutableStateOf<ScanRootEntry?>(null) }
    var addMenuOpen by remember { mutableStateOf(false) }
    // 选中的根类型要活过「拉起系统选择器 → 回调」的间隙(含进程重建),
    // 存 enum 名字符串(rememberSaveable 不认自定义 enum)。
    var pendingKindName by rememberSaveable { mutableStateOf(ScanRootKind.WORKS.name) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> viewModel.onPicked(uri, ScanRootKind.valueOf(pendingKindName)) }

    LaunchedEffect(Unit) { viewModel.refreshValidation() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("根文件夹管理", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "通过系统文件夹选择器授权 OneAsmr 读取。作品库按 RJ 文件夹收录作品;" +
                "单文件库平铺收录散音视频(如 YouTube 下载)。授权仅用于读取,移除授权不会删除任何文件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Box {
            Button(onClick = { addMenuOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("添加根文件夹")
            }
            DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("作品库（RJ 作品文件夹）") },
                    onClick = {
                        addMenuOpen = false
                        pendingKindName = ScanRootKind.WORKS.name
                        pickFolder.launch(null)
                    },
                )
                DropdownMenuItem(
                    text = { Text("单文件库（散音视频文件）") },
                    onClick = {
                        addMenuOpen = false
                        pendingKindName = ScanRootKind.SINGLE_FILES.name
                        pickFolder.launch(null)
                    },
                )
                DropdownMenuItem(
                    text = { Text("混合库（作品 + 散音视频）") },
                    onClick = {
                        addMenuOpen = false
                        pendingKindName = ScanRootKind.MIXED.name
                        pickFolder.launch(null)
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        ScanControls(
            progress = progress,
            lastSummary = lastSummary,
            hasAuthorizedRoot = entries.any { it.status == RootGrantStatus.AUTHORIZED },
            onScan = viewModel::startScan,
            onCancel = viewModel::cancelScan,
        )
        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Text(
                "尚未添加根文件夹",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { entry ->
                RootFolderRow(
                    entry = entry,
                    onRemove = { removeCandidate = entry },
                    onReselect = { pickFolder.launch(null) },
                    onSetKind = { kind -> viewModel.setKind(entry.root.treeUri, kind) },
                )
                HorizontalDivider()
            }
        }
    }

    removeCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { removeCandidate = null },
            title = { Text("移除根文件夹？") },
            text = { Text("仅解除授权，不删除文件。将解除「${candidate.root.displayName}」的读取授权并移出列表。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(candidate.root.treeUri)
                        removeCandidate = null
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { removeCandidate = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScanControls(
    progress: ScanProgress,
    lastSummary: RescanSummary?,
    hasAuthorizedRoot: Boolean,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    if (progress.phase == ScanPhase.SCANNING) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("正在扫描…", style = MaterialTheme.typography.titleMedium)
            val dir = if (progress.currentDir.isEmpty()) "（根目录）" else progress.currentDir
            Text(
                "${progress.rootDisplayName ?: ""} / $dir",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "已发现 ${progress.worksFound} 个作品 · 根文件夹 ${progress.rootsTotal} 个" +
                    if (progress.warningCount > 0) " · 警告 ${progress.warningCount}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCancel) { Text("取消扫描") }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onScan, enabled = hasAuthorizedRoot) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("扫描作品库")
        }
        if (!hasAuthorizedRoot) {
            Spacer(Modifier.width(8.dp))
            Text(
                "请先添加根文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (lastSummary != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "上次扫描：${formatScanSummary(lastSummary)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RootFolderRow(
    entry: ScanRootEntry,
    onRemove: () -> Unit,
    onReselect: () -> Unit,
    onSetKind: (ScanRootKind) -> Unit,
) {
    val revoked = entry.status == RootGrantStatus.REVOKED
    var kindMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.root.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 类型徽章可点:就地切换该根的用途(作品/单文件/混合)——
            // 「同一目录两种库都要」切成混合库即可,无需重复添加授权。
            Box {
                AssistChip(
                    onClick = { kindMenu = true },
                    label = {
                        Text(entry.root.kind.displayLabel(), style = MaterialTheme.typography.labelMedium)
                    },
                )
                DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                    ScanRootKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (kind == entry.root.kind) {
                                        "●  ${kind.displayLabel()}（当前）"
                                    } else {
                                        kind.displayLabel()
                                    },
                                )
                            },
                            onClick = {
                                kindMenu = false
                                if (kind != entry.root.kind) onSetKind(kind)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        if (revoked) "授权已失效" else "已授权",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    containerColor = if (revoked) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "移除 ${entry.root.displayName}")
            }
        }
        Text(
            text = entry.root.treeUri,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (revoked) {
            Text(
                "系统设置中已撤销此文件夹的授权。请重新选择该文件夹以恢复访问，或移除本项。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onReselect) { Text("重新授权") }
        }
    }
}

/** 根类型的展示名(添加菜单/类型徽章/切换菜单共用)。 */
private fun ScanRootKind.displayLabel(): String = when (this) {
    ScanRootKind.WORKS -> "作品库"
    ScanRootKind.SINGLE_FILES -> "单文件库"
    ScanRootKind.MIXED -> "混合库"
}

/** ViewModel for [ScanRootsScreen]; delegates all bookkeeping to [ScanRootRepository]. */
@HiltViewModel
class ScanRootsViewModel @Inject constructor(
    private val repository: ScanRootRepository,
    private val bookkeeping: ScanBookkeepingStore,
    private val scanController: ScanController,
) : ViewModel() {
    val entries: StateFlow<List<ScanRootEntry>> = repository.entries

    /** Live scan progress (Task 8); process-lifetime singleton, see [ScanProgressStore]. */
    val progress: StateFlow<ScanProgress> = ScanProgressStore.state

    /** Outcome of the most recent completed scan, durable across restarts. */
    val lastSummary: Flow<RescanSummary?> = bookkeeping.lastSummary

    init {
        refreshValidation()
    }

    fun refreshValidation() {
        viewModelScope.launch { repository.refreshValidation() }
    }

    fun onPicked(uri: Uri?, kind: ScanRootKind) {
        if (uri == null) return
        viewModelScope.launch {
            when (repository.addRoot(uri.toString(), null, kind)) {
                AddRootResult.ALREADY_EXISTS -> Unit // duplicate pick of a stored root
                AddRootResult.PERMISSION_DENIED -> Unit // logged by the repository
                AddRootResult.OK -> Unit
            }
        }
    }

    /** 就地切换根类型(作品/单文件/混合);授权与条目不动。 */
    fun setKind(treeUri: String, kind: ScanRootKind) {
        viewModelScope.launch { repository.setKind(treeUri, kind) }
    }

    fun remove(treeUri: String) {
        viewModelScope.launch { repository.removeRoot(treeUri) }
    }

    /** Enqueues a fresh full scan; no-op (returns 0) when no authorized root exists. */
    fun startScan() {
        scanController.startScan()
    }

    /** Cancels the running scan; committed works remain in the database. */
    fun cancelScan() {
        scanController.cancelScan()
    }
}
