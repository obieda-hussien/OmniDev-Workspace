package com.omnidev.workspace.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.mcp.McpConfigManager
import com.omnidev.workspace.data.mcp.McpConfigWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class McpSettingsViewModel(private val mcpConfigManager: McpConfigManager) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val draft = MutableStateFlow("")
    val jsonConfigState = draft.asStateFlow()
    private val saved = MutableStateFlow<String?>(null)
    val savedJson = saved.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    val errorMessage = error.asStateFlow()
    private val success = MutableStateFlow(false)
    val isSaved = success.asStateFlow()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    init { loadConfig() }

    fun loadConfig() {
        if (working.value) return
        working.value = true; error.value = null
        viewModelScope.launch {
            try {
                val encoded = json.encodeToString(McpConfigWrapper(mcpConfigManager.getServers()))
                draft.value = encoded; saved.value = encoded; success.value = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = "Could not load your configuration. Tap Reload to try again." }
            finally { working.value = false }
        }
    }

    fun updateJsonConfig(value: String) {
        if (working.value) return
        draft.value = value; error.value = null; success.value = false
    }

    fun saveConfig() {
        if (working.value || saved.value == null) return
        val snapshot = draft.value
        working.value = true; error.value = null; success.value = false
        viewModelScope.launch {
            try {
                val wrapper = try { json.decodeFromString<McpConfigWrapper>(snapshot) }
                catch (failure: Exception) {
                    error.value = "Invalid JSON. Each server needs a type and URL inside mcpServers. Your saved configuration is unchanged."
                    return@launch
                }
                mcpConfigManager.replaceServers(wrapper.mcpServers)
                val encoded = json.encodeToString(wrapper)
                draft.value = encoded; saved.value = encoded; success.value = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = "Could not save the configuration. Please try again." }
            finally { working.value = false }
        }
    }
}
