package com.omnidev.workspace.ui.brain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.brain.SmartLearningBridge
import com.omnidev.workspace.data.brain.ToolExecutionJournal
import com.omnidev.workspace.data.brain.ToolAwarenessEngine
import com.omnidev.workspace.data.db.entities.ToolExecutionEntry
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel  Agent Brain Dashboard
 */
class AgentBrainViewModel(
    private val bridge: SmartLearningBridge,
    private val journal: ToolExecutionJournal,
    private val awarenessEngine: ToolAwarenessEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentBrainUiState())
    val uiState: StateFlow<AgentBrainUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
        observeRealtimeData()
    }

    private fun loadData(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) kotlinx.coroutines.delay(250)
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val report = bridge.generatePerformanceReport()
                val awarenessStats = awarenessEngine.getStats()

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        totalExecutions = report.totalToolExecutions,
                        successRate = report.overallSuccessRate,
                        bestTool = report.bestTool ?: "—",
                        worstTool = report.worstTool ?: "—",
                        mostUsedTool = report.mostUsedTool ?: "—",
                        problematicTools = report.problematicTools,
                        totalKnowledge = report.totalKnowledgeEntries,
                        environmentStatus = report.environmentStatus,
                        sessionToolCount = report.sessionToolCount,
                        awarenessStats = awarenessStats
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun observeRealtimeData() {
        viewModelScope.launch {
            journal.observeRecentExecutions().collect { entries ->
                _uiState.update { state ->
                    state.copy(recentExecutions = entries)
                }
                loadData(debounce = true)
            }
        }

        viewModelScope.launch {
            awarenessEngine.observeKnowledge().collect { entries ->
                _uiState.update { state ->
                    state.copy(recentKnowledge = entries)
                }
                loadData(debounce = true)
            }
        }
    }

    fun refresh() = loadData()

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun deleteExecution(entryId: Long) {
        viewModelScope.launch {
            journal.deleteExecutionById(entryId)
        }
    }

    fun deleteKnowledge(entryId: Long) {
        viewModelScope.launch {
            awarenessEngine.invalidateKnowledgeById(entryId)
        }
    }

    fun updateKnowledge(
        entryId: Long,
        subject: String,
        content: String,
        confidence: Float
    ) {
        viewModelScope.launch {
            awarenessEngine.updateKnowledgeEntry(
                id = entryId,
                subject = subject,
                content = content,
                confidence = confidence
            )
        }
    }

    companion object {
        fun factory(
            bridge: SmartLearningBridge,
            journal: ToolExecutionJournal,
            awarenessEngine: ToolAwarenessEngine
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AgentBrainViewModel(bridge, journal, awarenessEngine) as T
        }
    }
}

data class AgentBrainUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalExecutions: Int = 0,
    val successRate: Float = 0f,
    val bestTool: String = "—",
    val worstTool: String = "—",
    val mostUsedTool: String = "—",
    val problematicTools: List<String> = emptyList(),
    val totalKnowledge: Int = 0,
    val environmentStatus: String = "",
    val sessionToolCount: Int = 0,
    val recentExecutions: List<ToolExecutionEntry> = emptyList(),
    val recentKnowledge: List<SystemKnowledgeEntry> = emptyList(),
    val awarenessStats: ToolAwarenessEngine.AwarenessStats? = null
)
