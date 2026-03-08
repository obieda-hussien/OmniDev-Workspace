package com.omnidev.workspace.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.debug.DebugEntry
import com.omnidev.workspace.data.debug.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DebugUiState(
    val entries: List<DebugEntry> = emptyList(),
    val deviceInfo: String = "",
    val isLoading: Boolean = false,
    val exportText: String? = null
)

class DebugViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    init {
        loadLogs()
    }

    fun loadLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = withContext(Dispatchers.IO) { DebugLogManager.readAll() }
            val deviceInfo = withContext(Dispatchers.IO) { DebugLogManager.deviceInfo() }
            _uiState.update {
                it.copy(entries = entries, deviceInfo = deviceInfo, isLoading = false)
            }
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { DebugLogManager.clearAll() }
            _uiState.update { it.copy(entries = emptyList()) }
        }
    }

    fun requestExport() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { DebugLogManager.exportAll() }
            _uiState.update { it.copy(exportText = text) }
        }
    }

    fun consumeExport() {
        _uiState.update { it.copy(exportText = null) }
    }
}
