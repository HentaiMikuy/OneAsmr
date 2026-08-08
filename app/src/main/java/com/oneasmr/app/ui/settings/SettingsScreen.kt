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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.oneasmr.app.BuildConfig
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.settings.ResumeMode
import com.oneasmr.app.data.local.settings.ScrapingLanguage
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.local.settings.ThemeMode
import com.oneasmr.app.data.local.PlaybackStateDao
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.RootGrantStatus
import com.oneasmr.app.data.repository.ScanRootEntry
import com.oneasmr.app.data.repository.ScanRootRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Full settings page (plan Task 27): theme + dynamic color, root-folder
 * management (Task 5), scraping language + batch-scrape entry status,
 * cover cache (usage / one-tap confirmed clear / cap), resume policy
 * (Task 19), optional playback-progress clear, and About (version +
 * open-source licenses). NO server entry (kikoeru integration cancelled).
 */
@Composable
fun SettingsScreen(
    onOpenRootFolders: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val rootEntries by viewModel.rootEntries.collectAsStateWithLifecycle()
    val resumeMode by viewModel.resumeMode.collectAsStateWithLifecycle()
    val scrapingLanguage by viewModel.scrapingLanguage.collectAsStateWithLifecycle()
    val cacheCapMb by viewModel.cacheCapMb.collectAsStateWithLifecycle()
    val cacheUsageBytes by viewModel.cacheUsageBytes.collectAsStateWithLifecycle()
    val cacheUsageRefreshing by viewModel.cacheUsageRefreshing.collectAsStateWithLifecycle()
    val clearingCoverCache by viewModel.clearingCoverCache.collectAsStateWithLifecycle()
    val clearingProgress by viewModel.clearingPlaybackProgress.collectAsStateWithLifecycle()
    val batchPending by viewModel.batchPendingCount.collectAsStateWithLifecycle()
    val batchFailed by viewModel.batchFailedCount.collectAsStateWithLifecycle()
    val clearCacheDialogVisible by viewModel.clearCoverCacheDialogVisible.collectAsStateWithLifecycle()
    val clearProgressDialogVisible by viewModel.clearPlaybackProgressDialogVisible.collectAsStateWithLifecycle()

    var licensesVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // ---- theme ----
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
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setDynamicColor(!dynamicColor) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("动态取色", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "使用系统壁纸配色（Android 12+）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = dynamicColor,
                onCheckedChange = { viewModel.setDynamicColor(it) },
            )
        }
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        // ---- scraping ----
        Text("刮削", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ScrapingLanguage.entries.forEachIndexed { index, language ->
                SegmentedButton(
                    selected = scrapingLanguage == language,
                    onClick = { viewModel.setScrapingLanguage(language) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ScrapingLanguage.entries.size),
                ) {
                    Text(language.displayLabel())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("批量刮削入口", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (batchPending == 0 && batchFailed == 0) "无待刮削作品" else "未刮削 $batchPending · 失败 $batchFailed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "批量刮削在图书馆页右上角菜单中发起。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        // ---- root folders ----
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
        Spacer(Modifier.height(24.dp))

        // ---- cover cache ----
        Text("封面缓存", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当前占用", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (cacheUsageRefreshing) "计算中…" else formatCacheBytes(cacheUsageBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { viewModel.refreshCacheUsage() }, enabled = !cacheUsageRefreshing) {
                Text("刷新")
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("缓存上限", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { viewModel.stepCacheCap(-STEP_MB) },
                enabled = cacheCapMb > 1,
                modifier = Modifier.height(36.dp),
            ) { Text("−100") }
            Text(
                " ${cacheCapMb} MB ",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            OutlinedButton(
                onClick = { viewModel.stepCacheCap(STEP_MB) },
                enabled = cacheCapMb < MAX_CAP_MB,
                modifier = Modifier.height(36.dp),
            ) { Text("+100") }
        }
        Text(
            "超过上限时按最近使用顺序自动清理最早的文件（始终保留至少一个）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.requestClearCoverCache() },
            enabled = !clearingCoverCache && !cacheUsageRefreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (clearingCoverCache) "清除中…" else "清除封面缓存")
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        // ---- resume policy ----
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
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        // ---- data management ----
        Text("数据管理", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.requestClearPlaybackProgress() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("清除播放进度", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "删除所有曲目的记忆播放位置（不影响评分与评语）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (clearingProgress) "清除中…" else "清除",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        // ---- about ----
        Text("关于", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("版本", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "OneAsmr v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { licensesVisible = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("开源许可", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("查看", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (clearCacheDialogVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelClearCoverCache() },
            title = { Text("清除封面缓存？") },
            text = {
                Text(
                    "将删除 covers/ 目录下的全部封面文件。作品数据、评分与评语不受影响。" +
                        (if (cacheUsageBytes > 0) "（释放约 ${formatCacheBytes(cacheUsageBytes)}）" else ""),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmClearCoverCache() }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelClearCoverCache() }) { Text("取消") }
            },
        )
    }

    if (clearProgressDialogVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelClearPlaybackProgress() },
            title = { Text("清除播放进度？") },
            text = {
                Text("将删除所有曲目的记忆播放位置，下次播放从头开始。评分与评语保留。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmClearPlaybackProgress() }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelClearPlaybackProgress() }) { Text("取消") }
            },
        )
    }

    if (licensesVisible) {
        AlertDialog(
            onDismissRequest = { licensesVisible = false },
            title = { Text("开源许可") },
            text = { Text(LICENSES_TEXT) },
            confirmButton = {
                TextButton(onClick = { licensesVisible = false }) { Text("关闭") }
            },
        )
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

private fun ScrapingLanguage.displayLabel(): String = when (this) {
    ScrapingLanguage.ZH -> "简体中文"
    ScrapingLanguage.JA -> "日本語"
}

/** Human-readable byte count for the cache usage row (Task 27). */
internal fun formatCacheBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return "${bytes / 1024L} KB"
    return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Static curated open-source attribution list (no third-party license plugin). */
private val LICENSES_TEXT: String = listOf(
    "AndroidX / Jetpack（Activity、Compose、Lifecycle、Navigation、Room、DataStore、WorkManager、Paging）— Apache-2.0",
    "Material Components / Material3 — Apache-2.0",
    "Kotlin、kotlinx-coroutines、kotlinx-serialization — Apache-2.0",
    "Media3 — Apache-2.0",
    "Coil — Apache-2.0",
    "OkHttp — Apache-2.0",
    "Hilt / Dagger — Apache-2.0",
    "juniversalchardet — MPL-1.1 / GPL / LGPL 三许可",
    "kikoeru-express（GPL-3.0）— 本项目仅沿用其目录与命名惯例以保持互通，不复制其代码",
).joinToString("\n\n")

private const val STEP_MB = 100
private const val MAX_CAP_MB = 100_000

/** ViewModel for [SettingsScreen] (plan Task 27 full settings page). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val scanRootRepository: ScanRootRepository,
    private val coverStore: CoverStore,
    private val playbackStateDao: PlaybackStateDao,
    private val workDao: WorkDao,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = settingsStore.themeMode
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val dynamicColor: StateFlow<Boolean> = settingsStore.dynamicColor
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val rootEntries: StateFlow<List<ScanRootEntry>> = scanRootRepository.entries
    val resumeMode: StateFlow<ResumeMode> = settingsStore.resumeMode
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ResumeMode.AUTO)
    val scrapingLanguage: StateFlow<ScrapingLanguage> = settingsStore.scrapingLanguage
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ScrapingLanguage.ZH)
    val cacheCapMb: StateFlow<Int> = settingsStore.cacheSizeCapMb
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, SettingsStore.DEFAULT_CACHE_CAP_MB)

    private val _cacheUsageBytes = MutableStateFlow(0L)
    val cacheUsageBytes: StateFlow<Long> = _cacheUsageBytes.asStateFlow()

    private val _cacheUsageRefreshing = MutableStateFlow(false)
    val cacheUsageRefreshing: StateFlow<Boolean> = _cacheUsageRefreshing.asStateFlow()

    private val _clearingCoverCache = MutableStateFlow(false)
    val clearingCoverCache: StateFlow<Boolean> = _clearingCoverCache.asStateFlow()

    private val _clearCoverCacheDialogVisible = MutableStateFlow(false)
    val clearCoverCacheDialogVisible: StateFlow<Boolean> = _clearCoverCacheDialogVisible.asStateFlow()

    private val _clearingPlaybackProgress = MutableStateFlow(false)
    val clearingPlaybackProgress: StateFlow<Boolean> = _clearingPlaybackProgress.asStateFlow()

    private val _clearPlaybackProgressDialogVisible = MutableStateFlow(false)
    val clearPlaybackProgressDialogVisible: StateFlow<Boolean> = _clearPlaybackProgressDialogVisible.asStateFlow()

    private val _batchPendingCount = MutableStateFlow(0)
    val batchPendingCount: StateFlow<Int> = _batchPendingCount.asStateFlow()

    private val _batchFailedCount = MutableStateFlow(0)
    val batchFailedCount: StateFlow<Int> = _batchFailedCount.asStateFlow()

    init {
        viewModelScope.launch { scanRootRepository.refreshValidation() }
        refreshCacheUsage()
        viewModelScope.launch {
            _batchPendingCount.value = workDao.countByScrapeStatus(ScrapeStatus.NOT_SCRAPED)
            _batchFailedCount.value = workDao.countByScrapeStatus(ScrapeStatus.FAILED)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDynamicColor(enabled) }
    }

    fun setResumeMode(mode: ResumeMode) {
        viewModelScope.launch { settingsStore.setResumeMode(mode) }
    }

    fun setScrapingLanguage(language: ScrapingLanguage) {
        viewModelScope.launch { settingsStore.setScrapingLanguage(language) }
    }

    /** Steps the cache cap by [delta] MB; the store clamps to [1, 100_000]. */
    fun stepCacheCap(delta: Int) {
        viewModelScope.launch {
            settingsStore.setCacheSizeCapMb(cacheCapMb.value + delta)
        }
    }

    /** Re-reads covers/ usage from disk (also after every clear). */
    fun refreshCacheUsage() {
        if (_cacheUsageRefreshing.value) return
        _cacheUsageRefreshing.value = true
        viewModelScope.launch {
            _cacheUsageBytes.value = coverStore.totalSizeBytes()
            _cacheUsageRefreshing.value = false
        }
    }

    // ---- cover-cache clear: confirm-gated, covers ONLY ----

    fun requestClearCoverCache() {
        _clearCoverCacheDialogVisible.value = true
    }

    fun cancelClearCoverCache() {
        _clearCoverCacheDialogVisible.value = false
    }

    fun confirmClearCoverCache() {
        _clearCoverCacheDialogVisible.value = false
        if (_clearingCoverCache.value) return
        _clearingCoverCache.value = true
        viewModelScope.launch {
            coverStore.clearAll()
            _clearingCoverCache.value = false
            refreshCacheUsage()
        }
    }

    // ---- playback-progress clear: separate optional action, confirm-gated ----

    fun requestClearPlaybackProgress() {
        _clearPlaybackProgressDialogVisible.value = true
    }

    fun cancelClearPlaybackProgress() {
        _clearPlaybackProgressDialogVisible.value = false
    }

    fun confirmClearPlaybackProgress() {
        _clearPlaybackProgressDialogVisible.value = false
        if (_clearingPlaybackProgress.value) return
        _clearingPlaybackProgress.value = true
        viewModelScope.launch {
            playbackStateDao.clearAll()
            _clearingPlaybackProgress.value = false
        }
    }
}
