package com.oneasmr.app.ui.work

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

@Composable
fun WorkDetailScreen(viewModel: WorkDetailViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(title = uiState.title, message = uiState.message)
}

/**
 * Skeleton ViewModel for the work detail route `work/{workId}`. The id
 * (format "local:RJ123456", see plan Task 4 key spec) arrives via
 * [SavedStateHandle]; the real detail page lands in Task 14.
 */
@HiltViewModel
class WorkDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val workId: String = checkNotNull(savedStateHandle[Routes.WORK_DETAIL_ARG])

    val uiState: StateFlow<PlaceholderUiState> = MutableStateFlow(
        PlaceholderUiState(
            title = "Work Detail",
            message = "作品详情占位页 — workId = $workId（任务 14 接入）",
        ),
    )
}
