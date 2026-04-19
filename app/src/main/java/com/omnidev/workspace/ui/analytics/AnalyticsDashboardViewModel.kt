package com.omnidev.workspace.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.AnalyticsStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exposes the analytics state as a live [StateFlow] so the Dashboard updates in
 * real-time whenever the agent pipeline records a new token usage / tool call /
 * agent run event.
 */
class AnalyticsDashboardViewModel(
    private val repository: AnalyticsRepository
) : ViewModel() {

    /**
     * Hot [StateFlow] fed by the underlying DataStore.  Starts empty (null),
     * then emits the persisted [AnalyticsStats] as soon as DataStore publishes
     * its first value and every mutation thereafter.
     */
    val stats: StateFlow<AnalyticsStats?> = repository.statsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = null
        )

    fun clearStats() {
        viewModelScope.launch {
            repository.clearStats()
        }
    }
}
