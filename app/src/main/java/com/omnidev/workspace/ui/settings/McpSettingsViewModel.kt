package com.omnidev.workspace.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.mcp.McpConfigManager
import com.omnidev.workspace.data.mcp.McpConfigWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class McpSettingsViewModel(
    private val mcpConfigManager: McpConfigManager
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _jsonConfigState = MutableStateFlow("")
    val jsonConfigState: StateFlow<String> = _jsonConfigState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            try {
                val servers = mcpConfigManager.getServers()
                val wrapper = McpConfigWrapper(servers)
                _jsonConfigState.value = json.encodeToString(wrapper)
                _errorMessage.value = null
                _isSaved.value = false
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load config: ${e.message}"
            }
        }
    }

    fun updateJsonConfig(newJson: String) {
        _jsonConfigState.value = newJson
        _errorMessage.value = null
        _isSaved.value = false
    }

    fun saveConfig() {
        viewModelScope.launch {
            try {
                // Try parsing to validate
                val wrapper = json.decodeFromString<McpConfigWrapper>(_jsonConfigState.value)

                // If it succeeds, first clear existing servers
                val existingServers = mcpConfigManager.getServers().keys
                existingServers.forEach {
                    mcpConfigManager.removeServer(it)
                }

                // Then add new servers
                wrapper.mcpServers.forEach { (name, config) ->
                    mcpConfigManager.addServer(name, config)
                }

                // Format with pretty print after save
                _jsonConfigState.value = json.encodeToString(wrapper)

                _errorMessage.value = null
                _isSaved.value = true
            } catch (e: Exception) {
                _errorMessage.value = "Invalid JSON format: ${e.message}"
                _isSaved.value = false
            }
        }
    }
}
