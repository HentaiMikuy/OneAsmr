package com.oneasmr.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import java.io.File
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.RootGrantStatus
import com.oneasmr.app.data.repository.ScanRootEntry
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.navigation.LocalBottomClusterHeight
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Full settings page (plan Task 27): theme + dynamic color, cold-start
 * animation toggle, root-folder management (Task 5), scraping language +
 * batch-scrape entry status, cover cache (usage / one-tap confirmed clear /
 * cap), resume policy (Task 19), optional playback-progress clear, and About
 * (version + open-source licenses). NO server entry (kikoeru integration
 * cancelled).
 *
 * Layout: rows are grouped by [SettingsSection] into tonal segmented cards
 * (see SettingsComponents.kt), each led by a tinted icon badge. A keyword
 * search filters rows across every section (matching title, synonyms and
 * the section name); the category chips below it narrow the list down to a
 * single section instead of scrolling to it, so switching sections never
 * needs a manual scroll back to the top.
 */
@Composable
fun SettingsScreen(
    onOpenRootFolders: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val launchAnimationEnabled by viewModel.launchAnimationEnabled.collectAsStateWithLifecycle()
    val videoBackgroundPlayback by viewModel.videoBackgroundPlayback.collectAsStateWithLifecycle()
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
    val asmrOneFallbackEnabled by viewModel.asmrOneFallbackEnabled.collectAsStateWithLifecycle()
    val asmrOneBaseUrl by viewModel.asmrOneBaseUrl.collectAsStateWithLifecycle()
    val asmrOneMirrorDialogVisible by viewModel.asmrOneMirrorDialogVisible.collectAsStateWithLifecycle()
    val nsfwEnabled by viewModel.nsfwEnabled.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    var updateDialogVisible by rememberSaveable { mutableStateOf(false) }
    var installAfterPermission by rememberSaveable { mutableStateOf(false) }

    var licensesVisible by remember { mutableStateOf(false) }

    var query by rememberSaveable { mutableStateOf("") }
    // 分类筛选:null = 全部。点标签只展示该分类的内容,不再"跳转到某一节"。
    var selectedSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val appContext = LocalContext.current
    fun openUpdateInstaller(file: File) {
        try {
            if (!file.isFile) {
                updateViewModel.refreshDownloadState()
                updateDialogVisible = true
                Toast.makeText(appContext, "安装包已被清理，请重新下载", Toast.LENGTH_LONG).show()
                return
            }
            triggerApkInstall(appContext, file)
            updateDialogVisible = false
        } catch (e: Exception) {
            Toast.makeText(appContext, "无法打开安装器：${e.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show()
            updateDialogVisible = true
        }
    }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The result can arrive before a restored download has been read from disk.
        installAfterPermission = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        updateViewModel.refreshDownloadState()
    }
    LaunchedEffect(installAfterPermission, updateState) {
        if (installAfterPermission && updateState !is UpdateUiState.Restoring) {
            installAfterPermission = false
            val downloaded = updateState as? UpdateUiState.Downloaded
            if (downloaded != null && updateViewModel.canRequestInstallPermission()) {
                openUpdateInstaller(downloaded.file)
            } else {
                updateDialogVisible = true
            }
        }
    }
    // 「检查更新」结果反馈:UpToDate 以前直接回到 Idle,界面上什么都不会
    // 发生。现在补一次 Short Toast,再把状态复位。单次结果提示,不涉及
    // 玩家页那种快速连按的重复提示场景。
    LaunchedEffect(updateState) {
        if (updateState is UpdateUiState.UpToDate) {
            updateDialogVisible = false
            Toast.makeText(
                appContext,
                "已是最新版本（v${BuildConfig.VERSION_NAME}）",
                Toast.LENGTH_SHORT,
            ).show()
            updateViewModel.dismissUpToDate()
        }
    }

    fun onUpdateClick() {
        when (updateState) {
            UpdateUiState.Idle, UpdateUiState.UpToDate -> {
                updateDialogVisible = true
                updateViewModel.checkForUpdate()
            }
            is UpdateUiState.Failed -> {
                updateDialogVisible = true
                updateViewModel.retryAfterError()
            }
            is UpdateUiState.Available,
            is UpdateUiState.Downloading,
            is UpdateUiState.Downloaded -> updateDialogVisible = true
            UpdateUiState.Restoring,
            UpdateUiState.Checking -> Unit
        }
    }
    val updateBusy = updateState is UpdateUiState.Checking || updateState is UpdateUiState.Restoring
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val revokedRootCount = rootEntries.count { it.status == RootGrantStatus.REVOKED }

    // Every row, in display order. Each entry carries search keywords
    // (synonyms users might type) besides its title.
    val entries: List<SettingsEntry> = listOf(
        // ---- 外观 ----
        SettingsEntry(
            SettingsSection.APPEARANCE,
            "主题模式",
            "主题 深色 浅色 暗色 夜间 黑暗 跟随系统 dark light theme",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.DarkMode,
                title = "主题模式",
                shape = shape,
                accent = SettingsAccent.Tertiary,
                below = {
                    SettingsSegmentedChoice(
                        options = ThemeMode.entries,
                        selected = themeMode,
                        label = { it.displayLabel() },
                        onSelect = viewModel::setThemeMode,
                    )
                },
            )
        },
        SettingsEntry(
            SettingsSection.APPEARANCE,
            "动态取色",
            "动态 取色 壁纸 配色 颜色 莫奈 monet material you",
        ) { shape ->
            SettingsSwitchItem(
                icon = Icons.Outlined.Palette,
                title = "动态取色",
                subtitle = if (dynamicColorSupported) "使用系统壁纸配色" else "需要 Android 12 及以上",
                checked = dynamicColor && dynamicColorSupported,
                onCheckedChange = viewModel::setDynamicColor,
                enabled = dynamicColorSupported,
                shape = shape,
                accent = SettingsAccent.Tertiary,
            )
        },
        SettingsEntry(
            SettingsSection.APPEARANCE,
            "启动动画",
            "启动 动画 开屏 闪屏 冷启动 splash",
        ) { shape ->
            SettingsSwitchItem(
                icon = Icons.Outlined.AutoAwesome,
                title = "启动动画",
                subtitle = "冷启动时播放品牌动画；关闭后直接进入主界面（下次启动生效）",
                checked = launchAnimationEnabled,
                onCheckedChange = viewModel::setLaunchAnimationEnabled,
                shape = shape,
                accent = SettingsAccent.Tertiary,
            )
        },

        // ---- 播放 ----
        SettingsEntry(
            SettingsSection.PLAYBACK,
            "续播策略",
            "续播 断点 记忆 进度 恢复 询问 自动 resume",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.History,
                title = "续播策略",
                subtitle = "已听超过 95% 或不足 3% 的曲目从头播放，其余从记忆位置续播",
                shape = shape,
                below = {
                    SettingsSegmentedChoice(
                        options = ResumeMode.entries,
                        selected = resumeMode,
                        label = { it.displayLabel() },
                        onSelect = viewModel::setResumeMode,
                    )
                },
            )
        },
        SettingsEntry(
            SettingsSection.PLAYBACK,
            "视频后台续播",
            "视频 后台 锁屏 关屏 省电 音频 继续播放",
        ) { shape ->
            SettingsSwitchItem(
                icon = Icons.Outlined.OndemandVideo,
                title = "视频后台续播",
                subtitle = "关屏、切后台或退出视频页后继续播放（仅解码音频以省电）；关闭则离开时暂停",
                checked = videoBackgroundPlayback,
                onCheckedChange = viewModel::setVideoBackgroundPlayback,
                shape = shape,
            )
        },

        // ---- 媒体库 ----
        SettingsEntry(
            SettingsSection.LIBRARY,
            "根文件夹管理",
            "根文件夹 文件夹 目录 路径 授权 扫描 添加 folder",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.FolderOpen,
                title = "根文件夹管理",
                subtitle = when {
                    rootEntries.isEmpty() -> "尚未添加，点此授权音声文件夹"
                    revokedRootCount > 0 -> "${rootEntries.size} 个文件夹 · $revokedRootCount 个授权已失效"
                    else -> "${rootEntries.size} 个文件夹已授权"
                },
                subtitleColor = if (revokedRootCount > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                shape = shape,
                accent = SettingsAccent.Secondary,
                onClick = onOpenRootFolders,
                trailing = { SettingsChevron() },
                below = if (rootEntries.isEmpty()) {
                    null
                } else {
                    {
                        Column(
                            modifier = Modifier.padding(start = 56.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rootEntries.forEach { RootStatusLine(it) }
                        }
                    }
                },
            )
        },
        SettingsEntry(
            SettingsSection.LIBRARY,
            "NSFW 模式",
            "nsfw 安全模式 成人 r18 r15 分级 隐私 隐藏 封面 曲名",
        ) { shape ->
            SettingsSwitchItem(
                icon = Icons.Outlined.Shield,
                title = "NSFW 模式",
                subtitle = "关闭后进入安全模式：未分级/R15/R18 作品的封面与曲名将被隐藏",
                checked = nsfwEnabled,
                onCheckedChange = viewModel::setNsfwEnabled,
                shape = shape,
                accent = SettingsAccent.Secondary,
            )
        },

        // ---- 刮削 ----
        SettingsEntry(
            SettingsSection.SCRAPING,
            "刮削语言",
            "语言 中文 简体 日语 日本語 dlsite 元数据 language",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.Translate,
                title = "刮削语言",
                subtitle = "从 DLsite 获取作品信息时使用的语言",
                shape = shape,
                accent = SettingsAccent.Tertiary,
                below = {
                    SettingsSegmentedChoice(
                        options = ScrapingLanguage.entries,
                        selected = scrapingLanguage,
                        label = { it.displayLabel() },
                        onSelect = viewModel::setScrapingLanguage,
                    )
                },
            )
        },
        SettingsEntry(
            SettingsSection.SCRAPING,
            "asmr.one 备用源",
            "asmr.one 备用 后备 补全 数据源 fallback",
        ) { shape ->
            SettingsSwitchItem(
                icon = Icons.Outlined.CloudSync,
                title = "asmr.one 备用源",
                subtitle = "DLsite 刮削失败时使用 asmr.one 补全（仅 RJ 作品）",
                checked = asmrOneFallbackEnabled,
                onCheckedChange = viewModel::setAsmrOneFallbackEnabled,
                shape = shape,
                accent = SettingsAccent.Tertiary,
            )
        },
        SettingsEntry(
            SettingsSection.SCRAPING,
            "asmr.one 镜像地址",
            "asmr.one 镜像 地址 域名 api 代理 mirror url",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.Link,
                title = "asmr.one 镜像地址",
                subtitle = asmrOneBaseUrl.ifBlank { "默认" },
                shape = shape,
                accent = SettingsAccent.Tertiary,
                onClick = viewModel::showAsmrOneMirrorDialog,
                trailing = { SettingsChevron() },
            )
        },
        SettingsEntry(
            SettingsSection.SCRAPING,
            "批量刮削",
            "批量 刮削 未刮削 失败 状态 进度 入口",
        ) { shape ->
            SettingsItem(
                icon = Icons.AutoMirrored.Outlined.FactCheck,
                title = "批量刮削",
                subtitle = "在图书馆页右上角菜单中发起",
                shape = shape,
                accent = SettingsAccent.Tertiary,
                trailing = {
                    Text(
                        text = if (batchPending == 0 && batchFailed == 0) {
                            "无待刮削"
                        } else {
                            "未刮削 $batchPending\n失败 $batchFailed"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.End,
                        color = if (batchFailed > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        },

        // ---- 存储与数据 ----
        SettingsEntry(
            SettingsSection.STORAGE,
            "封面缓存",
            "封面 缓存 占用 空间 上限 容量 大小 cache",
        ) { shape ->
            val capBytes = cacheCapMb.toLong() * 1024L * 1024L
            SettingsItem(
                icon = Icons.Outlined.Image,
                title = "封面缓存",
                subtitle = if (cacheUsageRefreshing) {
                    "计算中…"
                } else {
                    "已用 ${formatCacheBytes(cacheUsageBytes)} / 上限 $cacheCapMb MB"
                },
                shape = shape,
                accent = SettingsAccent.Secondary,
                trailing = {
                    IconButton(onClick = viewModel::refreshCacheUsage, enabled = !cacheUsageRefreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新占用")
                    }
                },
                below = {
                    LinearProgressIndicator(
                        progress = {
                            if (capBytes > 0) (cacheUsageBytes.toFloat() / capBytes).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("缓存上限", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "超过上限时按最近使用顺序自动清理最早的文件（始终保留至少一个）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        CacheCapStepper(capMb = cacheCapMb, onStep = viewModel::stepCacheCap)
                    }
                },
            )
        },
        SettingsEntry(
            SettingsSection.STORAGE,
            "清除封面缓存",
            "清除 删除 清理 封面 缓存 释放 空间",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.DeleteSweep,
                title = if (clearingCoverCache) "清除中…" else "清除封面缓存",
                titleColor = MaterialTheme.colorScheme.error,
                subtitle = "删除全部封面文件，作品数据、评分与评语不受影响",
                shape = shape,
                accent = SettingsAccent.Error,
                enabled = !clearingCoverCache && !cacheUsageRefreshing,
                onClick = viewModel::requestClearCoverCache,
            )
        },
        SettingsEntry(
            SettingsSection.STORAGE,
            "清除播放进度",
            "清除 删除 重置 播放 进度 记忆 位置 数据",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.RestartAlt,
                title = if (clearingProgress) "清除中…" else "清除播放进度",
                titleColor = MaterialTheme.colorScheme.error,
                subtitle = "删除所有曲目的记忆播放位置（不影响评分与评语）",
                shape = shape,
                accent = SettingsAccent.Error,
                enabled = !clearingProgress,
                onClick = viewModel::requestClearPlaybackProgress,
            )
        },

        // ---- 关于 ----
        SettingsEntry(
            SettingsSection.ABOUT,
            "版本",
            "版本 版本号 oneasmr version",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.Info,
                title = "版本",
                subtitle = "OneAsmr v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                shape = shape,
            )
        },
        SettingsEntry(
            SettingsSection.ABOUT,
            "检查更新",
            "检查 更新 升级 新版本 下载 安装 github release",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.SystemUpdate,
                title = "检查更新",
                subtitle = updateSubtitle(updateState),
                subtitleColor = if (updateState is UpdateUiState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
                shape = shape,
                enabled = !updateBusy,
                onClick = ::onUpdateClick,
                trailing = {
                    if (updateBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            updateTrailing(updateState),
                            style = MaterialTheme.typography.labelLarge,
                            color = when (updateState) {
                                is UpdateUiState.Available,
                                is UpdateUiState.Downloading,
                                is UpdateUiState.Downloaded -> MaterialTheme.colorScheme.primary
                                is UpdateUiState.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
        SettingsEntry(
            SettingsSection.ABOUT,
            "开源许可",
            "开源 许可 协议 授权 第三方 license",
        ) { shape ->
            SettingsItem(
                icon = Icons.Outlined.Description,
                title = "开源许可",
                shape = shape,
                onClick = { licensesVisible = true },
                trailing = { SettingsChevron() },
            )
        },
    )

    val searchTerms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    // 分类筛选与搜索是两种互斥的"看什么":点标签只展示该分类;一旦输入关键字,
    // 搜索回到全库语义(忽略分类),清空关键字后恢复之前选中的分类。
    val sectionFilter = selectedSection.takeIf { searchTerms.isEmpty() }
    val visibleEntries = entries
        .filter { sectionFilter == null || it.section == sectionFilter }
        .filter { searchTerms.isEmpty() || it.matches(searchTerms) }
    val entriesBySection = visibleEntries.groupBy { it.section }
    // 第 0 颗「全部」= 不过滤,其余与 SettingsSection.entries 顺序一一对应。
    val categoryChips = listOf("全部" to Icons.Outlined.Apps) +
        SettingsSection.entries.map { it.chipLabel to it.icon }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            // 底部导航为覆盖层不占位，末尾补实测簇高度的留白，避免最后一项被遮住。
            .padding(bottom = LocalBottomClusterHeight.current),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = 16.dp, start = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsSearchField(query = query, onQueryChange = { query = it })

        if (searchTerms.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            SettingsCategoryChips(
                categories = categoryChips,
                selectedIndex = selectedSection?.let { it.ordinal + 1 } ?: 0,
                onSelect = { index ->
                    selectedSection = if (index == 0) null else SettingsSection.entries[index - 1]
                    // 切换分类后内容整体换掉:直接把滚动位置归零(不做动画,
                    // 免得列表替换和滚动动画叠在一起看着发飘)。
                    scope.launch { scrollState.scrollTo(0) }
                },
            )
        }

        // 有新版本/安装包就绪时在顶部给一个醒目入口，不必翻到「关于」。
        // 仅"全部 + 未搜索"时出现:筛选某一分类时横幅是噪音,「关于」里也有入口。
        if (searchTerms.isEmpty() && selectedSection == null) {
            when (val update = updateState) {
                is UpdateUiState.Available -> {
                    Spacer(Modifier.height(16.dp))
                    SettingsBanner(
                        icon = Icons.Outlined.NewReleases,
                        title = "发现新版本 v${update.info.versionName}",
                        subtitle = "当前 v${BuildConfig.VERSION_NAME}，点击查看更新日志",
                        action = "更新",
                        onClick = ::onUpdateClick,
                    )
                }
                is UpdateUiState.Downloaded -> {
                    Spacer(Modifier.height(16.dp))
                    SettingsBanner(
                        icon = Icons.Outlined.NewReleases,
                        title = "新版本已就绪",
                        subtitle = "v${update.info.versionName} 安装包已下载完成",
                        action = "安装",
                        onClick = ::onUpdateClick,
                    )
                }
                else -> Unit
            }
        }

        if (visibleEntries.isEmpty()) {
            SettingsEmptySearch(query = query.trim())
        } else {
            SettingsSection.entries.forEach { section ->
                val sectionEntries = entriesBySection[section]
                if (sectionEntries != null) {
                    SettingsSectionHeader(title = section.title)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        sectionEntries.forEachIndexed { index, entry ->
                            key(entry.title) {
                                entry.content(settingsSegmentShape(index, sectionEntries.size))
                            }
                        }
                    }
                }
            }
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

    if (updateDialogVisible) {
        when (val update = updateState) {
            is UpdateUiState.Available -> AlertDialog(
                onDismissRequest = { updateDialogVisible = false },
                title = { Text("发现新版本 v${update.info.versionName}") },
                text = {
                    Column {
                        Text(
                            "当前 v${BuildConfig.VERSION_NAME} → v${update.info.versionName}" +
                                if (update.info.sizeBytes > 0) "（${formatCacheBytes(update.info.sizeBytes)}）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "更新日志：\n" + update.info.changelog.ifEmpty { "（无说明）" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                // 高度限制作用于滚动视口，让内部文字按完整内容测量。
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { updateViewModel.download() }) { Text("立即下载") }
                },
                dismissButton = {
                    TextButton(onClick = { updateDialogVisible = false }) { Text("以后再说") }
                },
            )

            is UpdateUiState.Downloading -> AlertDialog(
                onDismissRequest = { updateDialogVisible = false },
                title = { Text("正在下载 v${update.info.versionName}") },
                text = {
                    Column {
                        if (update.total > 0) {
                            LinearProgressIndicator(
                                progress = { (update.downloaded.toFloat() / update.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (update.total > 0) {
                                "${formatCacheBytes(update.downloaded)} / ${formatCacheBytes(update.total)}" +
                                    "（${(update.downloaded * 100 / update.total).coerceIn(0, 100)}%）"
                            } else {
                                "已下载 ${formatCacheBytes(update.downloaded)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        update.message?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { updateDialogVisible = false }) { Text("后台继续") }
                },
            )

            is UpdateUiState.Downloaded -> AlertDialog(
                onDismissRequest = { updateDialogVisible = false },
                title = { Text("下载完成") },
                text = { Text("新版本 v${update.info.versionName} 已就绪，是否现在安装？") },
                confirmButton = {
                    TextButton(onClick = {
                        if (updateViewModel.canRequestInstallPermission()) {
                            openUpdateInstaller(update.file)
                        } else {
                            try {
                                installPermissionLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${appContext.packageName}"),
                                    ),
                                )
                                updateDialogVisible = false
                            } catch (e: Exception) {
                                Toast.makeText(appContext, "无法打开安装权限设置，请在系统设置中授权", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("安装") }
                },
                dismissButton = {
                    TextButton(onClick = { updateDialogVisible = false }) { Text("稍后安装") }
                },
            )

            is UpdateUiState.Failed -> AlertDialog(
                onDismissRequest = {
                    updateDialogVisible = false
                    updateViewModel.dismissError()
                },
                title = { Text("更新失败") },
                text = { Text(update.message) },
                confirmButton = {
                    TextButton(onClick = { updateViewModel.retryAfterError() }) { Text("重试") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        updateDialogVisible = false
                        updateViewModel.dismissError()
                    }) { Text("关闭") }
                },
            )

            else -> Unit
        }
    }

    if (asmrOneMirrorDialogVisible) {
        var mirrorInput by remember { mutableStateOf(asmrOneBaseUrl) }
        AlertDialog(
            onDismissRequest = { viewModel.cancelAsmrOneMirrorDialog() },
            title = { Text("asmr.one 镜像地址") },
            text = {
                OutlinedTextField(
                    value = mirrorInput,
                    onValueChange = { mirrorInput = it },
                    label = { Text("镜像地址") },
                    placeholder = { Text("https://api.asmr.one") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setAsmrOneBaseUrl(mirrorInput) }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelAsmrOneMirrorDialog() }) { Text("取消") }
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

/** Settings categories, in display order (also the quick-jump chips). */
private enum class SettingsSection(val title: String, val chipLabel: String, val icon: ImageVector) {
    APPEARANCE("外观", "外观", Icons.Outlined.Palette),
    PLAYBACK("播放", "播放", Icons.Outlined.PlayCircle),
    LIBRARY("媒体库", "媒体库", Icons.Outlined.LibraryMusic),
    SCRAPING("刮削", "刮削", Icons.Outlined.TravelExplore),
    STORAGE("存储与数据", "存储", Icons.Outlined.Storage),
    ABOUT("关于", "关于", Icons.Outlined.Info),
}

/**
 * One searchable settings row. [content] receives the segment shape, which
 * depends on the row's position among the currently *visible* rows of its
 * section (so filtered groups still get proper rounded ends).
 */
private class SettingsEntry(
    val section: SettingsSection,
    val title: String,
    keywords: String,
    val content: @Composable (Shape) -> Unit,
) {
    private val haystack = "$title $keywords ${section.title}".lowercase()

    /** True when every whitespace-separated search term occurs in title/keywords/section. */
    fun matches(terms: List<String>): Boolean = terms.all { haystack.contains(it) }
}

/** Compact per-root status line under 根文件夹管理 (full details live in ScanRootsScreen). */
@Composable
private fun RootStatusLine(entry: ScanRootEntry) {
    val revoked = entry.status == RootGrantStatus.REVOKED
    val statusColor = if (revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(statusColor, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.root.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (revoked) "已失效" else "已授权",
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
        )
    }
}

/** −/+ stepper for the cover-cache cap, [STEP_MB] per tap. */
@Composable
private fun CacheCapStepper(capMb: Int, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onStep(-STEP_MB) },
            enabled = capMb > 1,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Outlined.Remove, contentDescription = "减少 $STEP_MB MB")
        }
        Text(
            text = "$capMb MB",
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .widthIn(min = 72.dp)
                .padding(horizontal = 4.dp),
        )
        FilledTonalIconButton(
            onClick = { onStep(STEP_MB) },
            enabled = capMb < MAX_CAP_MB,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "增加 $STEP_MB MB")
        }
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

/** Subtitle under the 检查更新 row for each update UI state. */
private fun updateSubtitle(state: UpdateUiState): String = when (state) {
    UpdateUiState.Idle -> "通过 GitHub Releases 获取最新版本"
    UpdateUiState.Restoring -> "正在恢复下载状态…"
    UpdateUiState.UpToDate -> "已是最新版本（v${BuildConfig.VERSION_NAME}）"
    UpdateUiState.Checking -> "正在检查…"
    is UpdateUiState.Available -> {
        val size = if (state.info.sizeBytes > 0) "，${formatCacheBytes(state.info.sizeBytes)}" else ""
        "发现新版本 v${state.info.versionName}$size"
    }
    is UpdateUiState.Downloading -> state.message ?: "正在下载 v${state.info.versionName}…"
    is UpdateUiState.Downloaded -> "v${state.info.versionName} 安装包已就绪"
    is UpdateUiState.Failed -> state.message
}

/** Trailing action label for the 检查更新 row. */
private fun updateTrailing(state: UpdateUiState): String = when (state) {
    UpdateUiState.Idle -> "检查"
    UpdateUiState.Restoring -> "…"
    UpdateUiState.UpToDate -> "已是最新"
    UpdateUiState.Checking -> "…"
    is UpdateUiState.Available -> "去下载"
    is UpdateUiState.Downloading ->
        if (state.total > 0) "${state.downloaded * 100 / state.total}%" else "下载中"
    is UpdateUiState.Downloaded -> "安装"
    is UpdateUiState.Failed -> "重试"
}

/**
 * Hands the downloaded APK to the system package installer via FileProvider
 * (authority `${packageName}.fileprovider`, external-files-path `Download/update/`). The caller
 * must have pre-checked [UpdateViewModel.canRequestInstallPermission]; other
 * wise route the user to the unknown-app-sources settings first.
 */
private fun triggerApkInstall(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
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
    val launchAnimationEnabled: StateFlow<Boolean> = settingsStore.launchAnimationEnabled
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val videoBackgroundPlayback: StateFlow<Boolean> = settingsStore.videoBackgroundPlayback
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val rootEntries: StateFlow<List<ScanRootEntry>> = scanRootRepository.entries
    val resumeMode: StateFlow<ResumeMode> = settingsStore.resumeMode
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ResumeMode.AUTO)
    val scrapingLanguage: StateFlow<ScrapingLanguage> = settingsStore.scrapingLanguage
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ScrapingLanguage.ZH)
    val cacheCapMb: StateFlow<Int> = settingsStore.cacheSizeCapMb
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, SettingsStore.DEFAULT_CACHE_CAP_MB)
    val asmrOneFallbackEnabled: StateFlow<Boolean> = settingsStore.asmrOneFallbackEnabled
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val asmrOneBaseUrl: StateFlow<String> = settingsStore.asmrOneBaseUrl
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "")
    val nsfwEnabled: StateFlow<Boolean> = settingsStore.nsfwEnabled
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)

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

    private val _asmrOneMirrorDialogVisible = MutableStateFlow(false)
    val asmrOneMirrorDialogVisible: StateFlow<Boolean> = _asmrOneMirrorDialogVisible.asStateFlow()

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

    /** 「启动动画」开关；下一次冷启动生效（见 MainActivity 的动画武装）。 */
    fun setLaunchAnimationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setLaunchAnimationEnabled(enabled) }
    }

    fun setVideoBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setVideoBackgroundPlayback(enabled) }
    }

    fun setNsfwEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNsfwEnabled(enabled) }
    }

    fun setResumeMode(mode: ResumeMode) {
        viewModelScope.launch { settingsStore.setResumeMode(mode) }
    }

    fun setScrapingLanguage(language: ScrapingLanguage) {
        viewModelScope.launch { settingsStore.setScrapingLanguage(language) }
    }

    fun setAsmrOneFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAsmrOneFallbackEnabled(enabled) }
    }

    // ---- asmr.one mirror base-url: dialog-gated text input ----

    fun showAsmrOneMirrorDialog() {
        _asmrOneMirrorDialogVisible.value = true
    }

    fun cancelAsmrOneMirrorDialog() {
        _asmrOneMirrorDialogVisible.value = false
    }

    /** Confirms the dialog input; blank restores the default host. */
    fun setAsmrOneBaseUrl(url: String) {
        _asmrOneMirrorDialogVisible.value = false
        viewModelScope.launch { settingsStore.setAsmrOneBaseUrl(url) }
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
