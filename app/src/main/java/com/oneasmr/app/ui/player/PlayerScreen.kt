package com.oneasmr.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.navigation.Routes
import com.oneasmr.app.ui.common.PlaceholderScreen
import com.oneasmr.app.ui.common.PlaceholderUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio player stub (plan Task 14 wires the tree→player navigation contract:
 * workId + trackIndex, the Task 19 trackKey inputs). The real Media3 player
 * UI lands in Task 21; the args already arrive on the route.
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(title = uiState.title, message = uiState.message)
}

/** Skeleton ViewModel for the audio player; Media3 session UI lands in Task 21. */
@HiltViewModel
class PlayerViewModel @Inject constructor(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val workId: String? = savedStateHandle[Routes.WORK_DETAIL_ARG]
    private val trackIndex: Int? = savedStateHandle[Routes.TRACK_INDEX_ARG]

    val uiState: StateFlow<PlaceholderUiState> = MutableStateFlow(
        PlaceholderUiState(
            title = "Player",
            message = "播放器占位页 — workId=$workId trackIndex=$trackIndex（Media3 播放核心在任务 17-21 接入）",
        ),
    )
}
