package com.oneasmr.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.oneasmr.app.data.repository.ScanRootRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
    var removeCandidate by remember { mutableStateOf<ScanRootEntry?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> viewModel.onPicked(uri) }

    LaunchedEffect(Unit) { viewModel.refreshValidation() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("根文件夹管理", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "通过系统文件夹选择器授权 OneAsmr 读取作品根目录。授权仅用于读取，移除授权不会删除任何文件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { pickFolder.launch(null) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("添加根文件夹")
        }
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
                )
                HorizontalDivider()
            }
        }
    }

    removeCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { removeCandidate = null },
            title = { Text("移除根文件夹？") },
            text = { Text("将解除「${candidate.root.displayName}」的读取授权并移出列表，不会删除任何文件。") },
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
private fun RootFolderRow(
    entry: ScanRootEntry,
    onRemove: () -> Unit,
    onReselect: () -> Unit,
) {
    val revoked = entry.status == RootGrantStatus.REVOKED
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.root.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/** ViewModel for [ScanRootsScreen]; delegates all bookkeeping to [ScanRootRepository]. */
@HiltViewModel
class ScanRootsViewModel @Inject constructor(
    private val repository: ScanRootRepository,
) : ViewModel() {
    val entries: StateFlow<List<ScanRootEntry>> = repository.entries

    init {
        refreshValidation()
    }

    fun refreshValidation() {
        viewModelScope.launch { repository.refreshValidation() }
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            when (repository.addRoot(uri.toString(), null)) {
                AddRootResult.ALREADY_EXISTS -> Unit // duplicate pick of a stored root
                AddRootResult.PERMISSION_DENIED -> Unit // logged by the repository
                AddRootResult.OK -> Unit
            }
        }
    }

    fun remove(treeUri: String) {
        viewModelScope.launch { repository.removeRoot(treeUri) }
    }
}
