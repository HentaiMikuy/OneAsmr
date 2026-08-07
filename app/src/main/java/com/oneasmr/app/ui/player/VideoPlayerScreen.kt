package com.oneasmr.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.ui.common.PlaceholderScreen
import com.oneasmr.app.ui.common.PlaceholderUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun VideoPlayerScreen(viewModel: VideoPlayerViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(title = uiState.title, message = uiState.message)
}

/** Skeleton ViewModel for the attached-video player page; real page in Task 22. */
@HiltViewModel
class VideoPlayerViewModel @Inject constructor() : ViewModel() {
    val uiState: StateFlow<PlaceholderUiState> = MutableStateFlow(
        PlaceholderUiState(
            title = "Video Player",
            message = "视频播放占位页 — 作品附带视频播放页在任务 22 接入",
        ),
    )
}
