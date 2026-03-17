package com.omnidev.workspace.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.AnalyticsStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AnalyticsDashboardViewModel(
    private val repository: AnalyticsRepository
) : ViewModel() {

    private val _stats = MutableStateFlow<AnalyticsStats?>(null)
    val stats: StateFlow<AnalyticsStats?> = _stats

    init {
        loadStats()
    }

    fun clearStats() {
        viewModelScope.launch {
            repository.clearStats()
            loadStats()
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            _stats.value = repository.getStats()
        }
    }
}
