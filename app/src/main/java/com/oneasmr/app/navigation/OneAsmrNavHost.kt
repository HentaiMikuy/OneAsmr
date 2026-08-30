package com.oneasmr.app.navigation

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.oneasmr.app.ui.browse.BrowseDimensionScreen
import com.oneasmr.app.ui.browse.DimensionWorksScreen
import com.oneasmr.app.ui.library.LibraryScreen
import com.oneasmr.app.ui.library.SearchScreen
import com.oneasmr.app.ui.player.MiniPlayerBarHost
import com.oneasmr.app.ui.player.PlayerScreen
import com.oneasmr.app.ui.player.VideoPlayerScreen
import com.oneasmr.app.ui.reviews.ReviewListScreen
import com.oneasmr.app.ui.settings.ScanRootsScreen
import com.oneasmr.app.ui.settings.SettingsScreen
import com.oneasmr.app.ui.singles.CollectionScreen
import com.oneasmr.app.ui.singles.SinglesScreen
import com.oneasmr.app.ui.work.ImageFileScreen
import com.oneasmr.app.ui.work.TextFileScreen
import com.oneasmr.app.ui.work.WorkDetailScreen

/** Route constants for the single-activity navigation graph. */
object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SINGLES = "singles"
    const val COLLECTION = "collection/{collectionId}"
    const val WORK_DETAIL = "work/{workId}"
    const val PLAYER = "player/{workId}/{trackIndex}"
    const val VIDEO_PLAYER = "video_player/{workId}/{trackIndex}"
    const val VIDEO_PLAYER_SINGLE = "video_player_single/{fileId}"
    const val TEXT_VIEWER = "text/{workId}/{documentUri}"
    const val IMAGE_VIEWER = "image/{workId}/{documentUri}"
    const val BROWSE_DIMENSION = "browse/{dimension}"
    const val BROWSE = "browse/{dimension}/{id}"
    const val REVIEWS = "reviews"
    const val SETTINGS = "settings"
    const val SCAN_ROOTS = "scan_roots"

    const val WORK_DETAIL_ARG = "workId"
    const val TRACK_INDEX_ARG = "trackIndex"
    const val TEXT_VIEWER_ARG_URI = "documentUri"
    const val BROWSE_ARG_DIMENSION = "dimension"
    const val BROWSE_ARG_ID = "id"
    const val COLLECTION_ARG = "collectionId"
    const val SINGLE_FILE_ARG = "fileId"

    fun workDetail(workId: String): String = "work/$workId"

    /** Audio player stub route (Task 17/21 wire the real player). */
    fun player(workId: String, trackIndex: Int): String = "player/$workId/$trackIndex"

    /** Video player stub route (Task 22 wires the real player). */
    fun videoPlayer(workId: String, trackIndex: Int): String = "video_player/$workId/$trackIndex"

    /** 单文件(音/视频皆走视频播放器页)播放路由。 */
    fun videoPlayerSingle(fileId: Long): String = "video_player_single/$fileId"

    /** 收藏夹详情。 */
    fun collection(collectionId: Long): String = "collection/$collectionId"

    /** Built-in text viewer; the SAF document uri is URL-encoded for safe routing. */
    fun textViewer(workId: String, documentUri: String): String =
        "text/$workId/${android.net.Uri.encode(documentUri)}"

    /** Built-in image viewer; the SAF document uri is URL-encoded for safe routing. */
    fun imageViewer(workId: String, documentUri: String): String =
        "image/$workId/${android.net.Uri.encode(documentUri)}"

    /** Task 16 dimension-works route: one circle/tag/CV's paged works. */
    fun browse(dimension: String, id: String): String =
        "browse/${android.net.Uri.encode(dimension)}/${android.net.Uri.encode(id)}"

    /** Task 16 dimension LIST route: one segment, e.g. browse/circle. */
    fun browseDimension(dimension: String): String =
        "browse/${android.net.Uri.encode(dimension)}"

    /** Task 15 "我标记的作品" review list. */
    fun reviews(): String = "reviews"
}

/** 底部簇松手吸附动画时长（毫秒）。 */
private const val BOTTOM_BAR_SETTLE_MS = 250

/**
 * 底部悬浮簇（mini player 胶囊 + 底部导航栏）的实测高度。覆盖层布局下
 * 内容不再从 Scaffold 获得底部 padding，三个根 Tab 的滚动容器改从这里
 * 读取高度作为底部留白，保证最后一项不被悬浮簇遮住。
 */
val LocalBottomClusterHeight = compositionLocalOf { 0.dp }

/**
 * Single NavHost for the whole app, wrapped in a Material3 [Scaffold].
 *
 * 底部导航（库/搜索/设置）+ 全局 mini player 以「覆盖层」形式浮在内容之上，
 * 而不是 Scaffold 的 bottomBar：bottomBar 即使被平移隐藏也依然通过
 * innerPadding 占位，底部会留下一条不可用的死区；覆盖层让内容真正全高，
 * 滚动时整簇下移隐藏即可把空间还给列表。导航栏只在三个根 Tab 路由渲染，
 * 且在根路由上随下滚隐藏、上滚回显，松手吸附到较近端点。
 *
 * Deep links: every route also registers a literal `oneasmr://<route>` deep
 * link, and `work/{workId}` the parameterized `oneasmr://work/{workId}`
 * (e.g. `oneasmr://work/local%3ARJ123456` → workId = "local:RJ123456").
 * Navigation Compose 2.9 does not auto-process the launching VIEW intent, so
 * it is routed explicitly below; [NavHostController.handleDeepLink] ignores
 * URIs that match nothing (e.g. `oneasmr://nope`), which leaves the start
 * destination (library) visible — the required failure path.
 *
 * [deepLinkIntent] is snapshot state refreshed by [MainActivity.onNewIntent],
 * so a VIEW intent the system delivers to the running top instance (warm
 * deep link) re-runs [NavHostController.handleDeepLink] instead of being
 * silently dropped. Falls back to `activity.intent` when not provided.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OneAsmrNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
    deepLinkIntent: Intent? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 根 Tab 路由：只有它们渲染底部导航栏，也只有它们响应滚动隐藏。
    val isRootRoute = currentRoute == Routes.LIBRARY ||
        currentRoute == Routes.SEARCH ||
        currentRoute == Routes.SINGLES ||
        currentRoute == Routes.SETTINGS

    // 滚动隐藏状态：0f = 完全显示，clusterHeightPx = 完全隐藏（整簇向下平移）。
    // 位移用 offset lambda 应用（只动图形层，不触发每帧重组/重排）；
    // 簇高度由 onSizeChanged 实测，同时折算成 dp 供根 Tab 列表做底部留白。
    val clusterHeightPx = remember { mutableIntStateOf(0) }
    val clusterHeightDp = remember { mutableStateOf(0.dp) }
    val bottomBarOffsetPx = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settleJob = remember { mutableStateOf<Job?>(null) }
    val isRootRouteState = rememberUpdatedState(isRootRoute)

    // 离开根路由时清零位移，避免把"隐藏中"的状态带回根 Tab。
    LaunchedEffect(isRootRoute) {
        if (!isRootRoute) {
            settleJob.value?.cancel()
            bottomBarOffsetPx.floatValue = 0f
        }
    }

    // 松手吸附：动画到较近端点（全显 0f 或全隐 clusterHeightPx）。
    val snapBottomBar: () -> Unit = {
        val height = clusterHeightPx.intValue.toFloat()
        if (height > 0f) {
            val target = if (bottomBarOffsetPx.floatValue < height / 2f) 0f else height
            settleJob.value = scope.launch {
                animate(
                    initialValue = bottomBarOffsetPx.floatValue,
                    targetValue = target,
                    animationSpec = tween(BOTTOM_BAR_SETTLE_MS, easing = EaseOut),
                ) { value, _ -> bottomBarOffsetPx.floatValue = value }
            }
        }
    }

    // 只跟踪不消费：把列表的纵向滚动增量 1:1 转成底部簇的下滑位移，
    // 返回 Offset.Zero / Velocity.Zero，列表自身的滚动行为完全不变。
    val bottomBarScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isRootRouteState.value) {
                    val height = clusterHeightPx.intValue.toFloat()
                    if (height > 0f && available.y != 0f) {
                        settleJob.value?.cancel()
                        bottomBarOffsetPx.floatValue =
                            (bottomBarOffsetPx.floatValue - available.y).coerceIn(0f, height)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (isRootRouteState.value) snapBottomBar()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // onPreFling 触发的吸附会被随后的惯性滚动增量取消（onPreScroll
                // 里 cancel），所以惯性结束后再吸附一次，保证位移不停在半截。
                if (isRootRouteState.value) snapBottomBar()
                return Velocity.Zero
            }
        }
    }

    // 非根路由：底簇不自动隐藏，内容底边上移簇高以免列表末尾被迷你播放器
    // 遮住；根路由必须保持 0（全高），滚动隐藏底栏时才不留死区。
    // 静态留白（不做动画）：animateDpAsState 会让整棵 NavHost 在导航期间
    // 每帧重测量/重布局，与过渡动画叠加是切页掉帧的主因；簇高是实测值，
    // 跳变仅在会话起停时发生一次，可接受。
    val contentBottomPadding = if (isRootRoute) 0.dp else clusterHeightDp.value

    Scaffold(
        modifier = modifier,
        // Insets are handled per-screen (safeDrawingPadding) and by the bottom
        // bar itself; Scaffold's default contentWindowInsets would only add the
        // status bar height as plain padding WITHOUT consuming the inset, so
        // every screen padded it a second time (visible double-height blank
        // strip at the top). Zero it out here instead.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        // 内容 + 底部悬浮簇同层叠加：簇对齐 Box 底部、盖在内容之上，
        // 因此内容不再从 Scaffold 拿底部 padding（全高），根 Tab 列表的
        // 底部留白由 [LocalBottomClusterHeight] 提供。
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(bottomBarScrollConnection),
        ) {
        CompositionLocalProvider(LocalBottomClusterHeight provides clusterHeightDp.value) {
        // Wave E: SharedTransitionLayout enables the library cover → detail
        // hero shared element (see ui/common/Motion.kt). Transition defaults
        // below are the PUSH style (slide in from end + fade); the three tab
        // roots override them with a plain 220ms cross-fade. exit/popEnter
        // stay fade-only — no parallax hijacks.
        SharedTransitionLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = contentBottomPadding),
        ) {
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                slideInHorizontally(animationSpec = tween(220, easing = EaseOut)) { it } +
                    fadeIn(animationSpec = tween(220))
            },
            exitTransition = { fadeOut(animationSpec = tween(220)) },
            popEnterTransition = { fadeIn(animationSpec = tween(220)) },
            popExitTransition = {
                slideOutHorizontally(animationSpec = tween(220, easing = EaseOut)) { it } +
                    fadeOut(animationSpec = tween(220))
            },
        ) {
            composable(
                route = Routes.LIBRARY,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://library" }),
                enterTransition = { fadeIn(animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(220)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(220)) },
            ) {
                LibraryScreen(
                    onOpenRootFolders = { navController.navigate(Routes.SCAN_ROOTS) },
                    onOpenWork = { workId -> navController.navigate(Routes.workDetail(workId)) },
                    onOpenReviews = { navController.navigate(Routes.reviews()) },
                    onOpenBrowse = { dimension ->
                        navController.navigate(Routes.browseDimension(dimension))
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this,
                )
            }
            composable(
                route = Routes.REVIEWS,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://reviews" }),
            ) {
                ReviewListScreen(
                    onOpenWork = { workId -> navController.navigate(Routes.workDetail(workId)) },
                )
            }
            composable(
                route = Routes.SEARCH,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://search" }),
                enterTransition = { fadeIn(animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(220)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(220)) },
            ) {
                SearchScreen(
                    onOpenWork = { workId -> navController.navigate(Routes.workDetail(workId)) },
                )
            }
            composable(
                route = Routes.SINGLES,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://singles" }),
                enterTransition = { fadeIn(animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(220)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(220)) },
            ) {
                SinglesScreen(
                    onOpenFile = { fileId -> navController.navigate(Routes.videoPlayerSingle(fileId)) },
                    onOpenCollection = { collectionId ->
                        navController.navigate(Routes.collection(collectionId))
                    },
                    onOpenScanRoots = { navController.navigate(Routes.SCAN_ROOTS) },
                )
            }
            composable(
                route = Routes.COLLECTION,
                arguments = listOf(
                    navArgument(Routes.COLLECTION_ARG) { type = NavType.StringType },
                ),
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://collection/{collectionId}" }),
            ) {
                CollectionScreen(
                    onOpenFile = { fileId -> navController.navigate(Routes.videoPlayerSingle(fileId)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.WORK_DETAIL,
                arguments = listOf(
                    navArgument(Routes.WORK_DETAIL_ARG) { type = NavType.StringType },
                ),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "oneasmr://work/{workId}" },
                ),
            ) {
                WorkDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { workId, trackIndex ->
                        navController.navigate(Routes.player(workId, trackIndex))
                    },
                    onOpenVideoPlayer = { workId, trackIndex ->
                        navController.navigate(Routes.videoPlayer(workId, trackIndex))
                    },
                    onOpenText = { workId, documentUri ->
                        navController.navigate(Routes.textViewer(workId, documentUri))
                    },
                    onOpenImage = { workId, documentUri ->
                        navController.navigate(Routes.imageViewer(workId, documentUri))
                    },
                    onOpenBrowse = { dimension, id ->
                        navController.navigate(Routes.browse(dimension, id))
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this,
                )
            }
            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument(Routes.WORK_DETAIL_ARG) { type = NavType.StringType },
                    navArgument(Routes.TRACK_INDEX_ARG) { type = NavType.IntType },
                ),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "oneasmr://player/{workId}/{trackIndex}" },
                ),
            ) {
                PlayerScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.VIDEO_PLAYER,
                arguments = listOf(
                    navArgument(Routes.WORK_DETAIL_ARG) { type = NavType.StringType },
                    navArgument(Routes.TRACK_INDEX_ARG) { type = NavType.IntType },
                ),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "oneasmr://video_player/{workId}/{trackIndex}" },
                ),
            ) {
                VideoPlayerScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.VIDEO_PLAYER_SINGLE,
                arguments = listOf(
                    navArgument(Routes.SINGLE_FILE_ARG) { type = NavType.StringType },
                ),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "oneasmr://video_player_single/{fileId}" },
                ),
            ) {
                // 同一播放器页:ViewModel 按 fileId 参数走单档装载分支。
                VideoPlayerScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.TEXT_VIEWER,
                arguments = listOf(
                    navArgument(Routes.WORK_DETAIL_ARG) { type = NavType.StringType },
                    navArgument(Routes.TEXT_VIEWER_ARG_URI) { type = NavType.StringType },
                ),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "oneasmr://text/{workId}/{documentUri}" },
                ),
            ) {
                TextFileScreen()
            }
            composable(
                route = Routes.IMAGE_VIEWER,
                arguments = listOf(
                    navArgument(Routes.WORK_DETAIL_ARG) { type = NavType.StringType },
                    navArgument(Routes.TEXT_VIEWER_ARG_URI) { type = NavType.StringType },
                ),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "oneasmr://image/{workId}/{documentUri}" },
                ),
            ) {
                val entry = it
                val documentUri = entry.arguments?.getString(Routes.TEXT_VIEWER_ARG_URI).orEmpty()
                ImageFileScreen(documentUri = documentUri, onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.BROWSE_DIMENSION,
                arguments = listOf(
                    navArgument(Routes.BROWSE_ARG_DIMENSION) { type = NavType.StringType },
                ),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "oneasmr://browse/{dimension}" },
                ),
            ) {
                BrowseDimensionScreen(
                    onOpenDimension = { dimension, id ->
                        navController.navigate(Routes.browse(dimension, id))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.BROWSE,
                arguments = listOf(
                    navArgument(Routes.BROWSE_ARG_DIMENSION) { type = NavType.StringType },
                    navArgument(Routes.BROWSE_ARG_ID) { type = NavType.StringType },
                ),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "oneasmr://browse/{dimension}/{id}" },
                ),
            ) {
                DimensionWorksScreen(
                    onOpenWork = { workId -> navController.navigate(Routes.workDetail(workId)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.SETTINGS,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://settings" }),
                enterTransition = { fadeIn(animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(220)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(220)) },
            ) {
                SettingsScreen(
                    onOpenRootFolders = { navController.navigate(Routes.SCAN_ROOTS) },
                )
            }
            composable(
                route = Routes.SCAN_ROOTS,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://scan_roots" }),
            ) {
                ScanRootsScreen()
            }
        }
        }
        }

        // 底部悬浮簇：mini player 胶囊 + 底部导航栏，实测高度写入
        // [LocalBottomClusterHeight]，滚动隐藏位移用 offset lambda 应用。
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged {
                    clusterHeightPx.intValue = it.height
                    clusterHeightDp.value = with(density) { it.height.toDp() }
                }
                .offset { IntOffset(0, bottomBarOffsetPx.floatValue.roundToInt()) },
        ) {
            // Global mini player bar (Task 21): visible on every screen
            // EXCEPT the full player page and the attached-video page
            // (Task 22: the video page owns the session — its exit path
            // must run through the back handler, not nav-bar navigation);
            // tap -> full player, swipe -> stop. Its visibility is driven
            // by the session connection (single source of truth).
            if (currentRoute != Routes.PLAYER &&
                currentRoute != Routes.VIDEO_PLAYER &&
                currentRoute != Routes.VIDEO_PLAYER_SINGLE
            ) {
                // 非根路由没有导航栏兜底系统手势条高度，胶囊自己让开。
                Box(if (isRootRoute) Modifier else Modifier.navigationBarsPadding()) {
                    MiniPlayerBarHost(
                        onOpenPlayer = { workId, trackIndex ->
                            navController.navigate(Routes.player(workId, trackIndex))
                        },
                    )
                }
            }
            // 导航栏只在三个根 Tab 路由渲染；非根路由彻底不显示（原仅视频页隐藏）。
            if (isRootRoute) {
                OneAsmrBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
        }
    }

    val activity = LocalContext.current as? Activity
    LaunchedEffect(activity, deepLinkIntent) {
        val intent = deepLinkIntent ?: activity?.intent
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            navController.handleDeepLink(intent)
        }
    }
}

@Composable
private fun OneAsmrBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.LIBRARY,
            onClick = { onNavigate(Routes.LIBRARY) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Library") },
            label = { Text("Library") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SINGLES,
            onClick = { onNavigate(Routes.SINGLES) },
            icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = "Videos") },
            label = { Text("Videos") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SEARCH,
            onClick = { onNavigate(Routes.SEARCH) },
            icon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            label = { Text("Search") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SETTINGS,
            onClick = { onNavigate(Routes.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
        )
    }
}
