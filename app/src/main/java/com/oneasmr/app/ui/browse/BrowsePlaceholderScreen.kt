package com.oneasmr.app.ui.browse

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import com.oneasmr.app.navigation.Routes
import com.oneasmr.app.ui.common.PlaceholderScreen

/**
 * Task 16 stub destination for `browse/{dimension}/{id}`: the detail page's
 * circle/CV/tag chips navigate here with their dimension + id; the real
 * dimension-browsing UI lands in Task 16 (plan: "navigate to a
 * browse/{dimension}/{id} route that Task 16 will fill").
 */
@Composable
fun BrowsePlaceholderScreen(backStackEntry: NavBackStackEntry) {
    val dimension = backStackEntry.arguments?.getString(Routes.BROWSE_ARG_DIMENSION) ?: "?"
    val id = backStackEntry.arguments?.getString(Routes.BROWSE_ARG_ID) ?: "?"
    PlaceholderScreen(
        title = "浏览：$dimension",
        message = "维度浏览页占位 — $dimension / $id（任务 16 接入）",
    )
}
