package com.oneasmr.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.ListeningSessionDao
import com.oneasmr.app.data.stats.ListeningStatsCalculator
import com.oneasmr.app.data.stats.ListeningStatsReport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class StatsUiState(
    val loaded: Boolean = false,
    val report: ListeningStatsReport = ListeningStatsReport.EMPTY,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    listeningSessionDao: ListeningSessionDao,
) : ViewModel() {
    private val refreshTick = MutableStateFlow(0)

    val uiState: StateFlow<StatsUiState> = combine(
        listeningSessionDao.observeAll(),
        refreshTick,
    ) { sessions, _ ->
        StatsUiState(
            loaded = true,
            report = ListeningStatsCalculator.calculate(
                sessions = sessions,
                nowMs = System.currentTimeMillis(),
                zone = ZoneId.systemDefault(),
            ),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    /** Re-evaluates the current day/hour when returning to the tab. */
    fun refresh() {
        refreshTick.update { it + 1 }
    }
}
