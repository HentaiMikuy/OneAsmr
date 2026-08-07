package com.oneasmr.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.oneasmr.app.ui.common.PlaceholderScreen
import com.oneasmr.app.ui.common.PlaceholderUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun LibraryScreen(viewModel: LibraryViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(title = uiState.title, message = uiState.message)
}

/** Skeleton ViewModel for the library home; real paging data lands in Task 12. */
@HiltViewModel
class LibraryViewModel @Inject constructor() : ViewModel() {
    val uiState: StateFlow<PlaceholderUiState> = MutableStateFlow(
        PlaceholderUiState(
            title = "Library",
            message = "作品库占位页 — 分页网格与封面墙在任务 12 接入",
        ),
    )
}
