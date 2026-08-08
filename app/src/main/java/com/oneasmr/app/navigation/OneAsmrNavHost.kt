package com.oneasmr.app.navigation

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.oneasmr.app.ui.settings.ServerLoginScreen
import com.oneasmr.app.ui.settings.SettingsScreen
import com.oneasmr.app.ui.work.ImageFileScreen
import com.oneasmr.app.ui.work.TextFileScreen
import com.oneasmr.app.ui.work.WorkDetailScreen

/** Route constants for the single-activity navigation graph. */
object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val WORK_DETAIL = "work/{workId}"
    const val PLAYER = "player/{workId}/{trackIndex}"
    const val VIDEO_PLAYER = "video_player/{workId}/{trackIndex}"
    const val TEXT_VIEWER = "text/{workId}/{documentUri}"
    const val IMAGE_VIEWER = "image/{workId}/{documentUri}"
    const val BROWSE_DIMENSION = "browse/{dimension}"
    const val BROWSE = "browse/{dimension}/{id}"
    const val REVIEWS = "reviews"
    const val SETTINGS = "settings"
    const val SERVER_LOGIN = "server_login"
    const val SCAN_ROOTS = "scan_roots"

    const val WORK_DETAIL_ARG = "workId"
    const val TRACK_INDEX_ARG = "trackIndex"
    const val TEXT_VIEWER_ARG_URI = "documentUri"
    const val BROWSE_ARG_DIMENSION = "dimension"
    const val BROWSE_ARG_ID = "id"

    fun workDetail(workId: String): String = "work/$workId"

    /** Audio player stub route (Task 17/21 wire the real player). */
    fun player(workId: String, trackIndex: Int): String = "player/$workId/$trackIndex"

    /** Video player stub route (Task 22 wires the real player). */
    fun videoPlayer(workId: String, trackIndex: Int): String = "video_player/$workId/$trackIndex"

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

/**
 * Single NavHost for the whole app, wrapped in a Material3 [Scaffold] with a
 * bottom-navigation skeleton (Library / Search / Settings).
 *
 * Deep links: every route also registers a literal `oneasmr://<route>` deep
 * link, and `work/{workId}` the parameterized `oneasmr://work/{workId}`
 * (e.g. `oneasmr://work/local%3ARJ123456` → workId = "local:RJ123456").
 * Navigation Compose 2.9 does not auto-process the launching VIEW intent, so
 * it is routed explicitly below; [NavHostController.handleDeepLink] ignores
 * URIs that match nothing (e.g. `oneasmr://nope`), which leaves the start
 * destination (library) visible — the required failure path.
 */
@Composable
fun OneAsmrNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            Column {
                // Global mini player bar (Task 21): visible on every screen
                // EXCEPT the full player page and the attached-video page
                // (Task 22: the video page owns the session — its exit path
                // must run through the back handler, not nav-bar navigation);
                // tap -> full player, swipe -> stop. Its visibility is driven
                // by the session connection (single source of truth).
                if (currentRoute != Routes.PLAYER && currentRoute != Routes.VIDEO_PLAYER) {
                    MiniPlayerBarHost(
                        onOpenPlayer = { workId, trackIndex ->
                            navController.navigate(Routes.player(workId, trackIndex))
                        },
                    )
                }
                if (currentRoute != Routes.VIDEO_PLAYER) {
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
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(
                route = Routes.LIBRARY,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://library" }),
            ) {
                LibraryScreen(
                    onOpenRootFolders = { navController.navigate(Routes.SCAN_ROOTS) },
                    onOpenWork = { workId -> navController.navigate(Routes.workDetail(workId)) },
                    onOpenReviews = { navController.navigate(Routes.reviews()) },
                    onOpenBrowse = { dimension ->
                        navController.navigate(Routes.browseDimension(dimension))
                    },
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
            ) {
                SearchScreen(
                    onOpenWork = { workId -> navController.navigate(Routes.workDetail(workId)) },
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
            ) {
                SettingsScreen(
                    onOpenRootFolders = { navController.navigate(Routes.SCAN_ROOTS) },
                    onOpenServerLogin = { navController.navigate(Routes.SERVER_LOGIN) },
                )
            }
            composable(
                route = Routes.SERVER_LOGIN,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://server_login" }),
            ) {
                ServerLoginScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.SCAN_ROOTS,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://scan_roots" }),
            ) {
                ScanRootsScreen()
            }
        }
    }

    val activity = LocalContext.current as? Activity
    val intent = activity?.intent
    LaunchedEffect(activity, intent) {
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
