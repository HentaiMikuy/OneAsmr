package com.oneasmr.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.settings.ResumeMode
import com.oneasmr.app.data.local.settings.ServerStore
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.local.settings.ThemeMode
import com.oneasmr.app.data.repository.RootGrantStatus
import com.oneasmr.app.data.repository.ScanRootEntry
import com.oneasmr.app.data.repository.ScanRootRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings first version (plan Task 5): theme switcher (system/light/dark),
 * the root-folder management entry and the in-app "authorized root folders"
 * list — the visible evidence channel for persisted SAF grants.
 */
@Composable
fun SettingsScreen(
    onOpenRootFolders: () -> Unit = {},
    onOpenServerLogin: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val rootEntries by viewModel.rootEntries.collectAsStateWithLifecycle()
    val resumeMode by viewModel.resumeMode.collectAsStateWithLifecycle()
    val activeServerName by viewModel.activeServerName.collectAsStateWithLifecycle()
    val serverCount by viewModel.serverCount.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("主题", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                ) {
                    Text(mode.displayLabel())
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // Task 19: playback-resume policy (default AUTO; ALWAYS_ASK dialog
        // lands with the Task 21 player screen).
        Text("续播策略", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ResumeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = resumeMode == mode,
                    onClick = { viewModel.setResumeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ResumeMode.entries.size),
                ) {
                    Text(mode.displayLabel())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "已听超过 95% 或不足 3% 的曲目将从头播放，其余从记忆位置续播。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRootFolders)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("根文件夹管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                if (rootEntries.isEmpty()) "未添加" else "${rootEntries.size} 个",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenServerLogin)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("服务器模式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                when {
                    serverCount == 0 -> "未配置"
                    activeServerName.isNotBlank() -> activeServerName
                    else -> "${serverCount} 个"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("已授权根文件夹", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (rootEntries.isEmpty()) {
            Text(
                "无 — 添加根文件夹后，这里会显示系统授权状态。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rootEntries.forEach { entry ->
                AuthorizedRootRow(entry)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AuthorizedRootRow(entry: ScanRootEntry) {
    val revoked = entry.status == RootGrantStatus.REVOKED
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.root.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (revoked) "已失效" else "已授权",
                style = MaterialTheme.typography.labelMedium,
                color = if (revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = entry.root.treeUri,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ThemeMode.displayLabel(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun ResumeMode.displayLabel(): String = when (this) {
    ResumeMode.AUTO -> "自动续播"
    ResumeMode.ALWAYS_ASK -> "总是询问"
}

/** ViewModel for [SettingsScreen]: theme mode from [SettingsStore], roots from [ScanRootRepository]. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val scanRootRepository: ScanRootRepository,
    serverStore: ServerStore,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = settingsStore.themeMode
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val rootEntries: StateFlow<List<ScanRootEntry>> = scanRootRepository.entries
    val resumeMode: StateFlow<ResumeMode> = settingsStore.resumeMode
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ResumeMode.AUTO)

    val activeServerName: StateFlow<String> = serverStore.activeServer
        .map { it?.name.orEmpty() }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "")

    val serverCount: StateFlow<Int> = serverStore.servers
        .map { it.size }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0)

    init {
        viewModelScope.launch { scanRootRepository.refreshValidation() }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    fun setResumeMode(mode: ResumeMode) {
        viewModelScope.launch { settingsStore.setResumeMode(mode) }
    }
}
