package com.oneasmr.app.navigation

import android.app.Activity
import android.content.Intent
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
import com.oneasmr.app.ui.library.LibraryScreen
import com.oneasmr.app.ui.library.SearchScreen
import com.oneasmr.app.ui.player.PlayerScreen
import com.oneasmr.app.ui.player.VideoPlayerScreen
import com.oneasmr.app.ui.settings.ScanRootsScreen
import com.oneasmr.app.ui.settings.ServerLoginScreen
import com.oneasmr.app.ui.settings.SettingsScreen
import com.oneasmr.app.ui.work.WorkDetailScreen

/** Route constants for the single-activity navigation graph. */
object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val WORK_DETAIL = "work/{workId}"
    const val PLAYER = "player"
    const val VIDEO_PLAYER = "video_player"
    const val SETTINGS = "settings"
    const val SERVER_LOGIN = "server_login"
    const val SCAN_ROOTS = "scan_roots"

    const val WORK_DETAIL_ARG = "workId"

    fun workDetail(workId: String): String = "work/$workId"
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
                WorkDetailScreen()
            }
            composable(
                route = Routes.PLAYER,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://player" }),
            ) {
                PlayerScreen()
            }
            composable(
                route = Routes.VIDEO_PLAYER,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://video_player" }),
            ) {
                VideoPlayerScreen()
            }
            composable(
                route = Routes.SETTINGS,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://settings" }),
            ) {
                SettingsScreen(onOpenRootFolders = { navController.navigate(Routes.SCAN_ROOTS) })
            }
            composable(
                route = Routes.SERVER_LOGIN,
                deepLinks = listOf(navDeepLink { uriPattern = "oneasmr://server_login" }),
            ) {
                ServerLoginScreen()
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
