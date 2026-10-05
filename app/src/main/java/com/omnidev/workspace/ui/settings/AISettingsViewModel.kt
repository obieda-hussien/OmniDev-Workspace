package com.omnidev.workspace.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * UI state for the AI Settings screen.
 * Holds the current model assignment for each role, deep thinking toggle,
 * and dropdown expansion states.
 */
data class AISettingsUiState(
    /** Current model ID selected for each role. */
    val modelAssignments: Map<ModelRole, String> = ModelRole.entries.associateWith {
        ModelRegistry.getDefaultModelForRole(it).id
    },
    val localModelConfigured: Boolean = false,
    /** Whether Deep Thinking mode is enabled globally. */
    val deepThinkingEnabled: Boolean = false,
    /** Whether God Mode (unrestricted file system access) is enabled. */
    val godModeEnabled: Boolean = false,
    /** Which role's dropdown is currently expanded (null = all collapsed). */
    val expandedDropdownRole: ModelRole? = null,
    /** Whether a save operation is in progress. */
    val isSaving: Boolean = false,
    /** Kept inside the model picker so a dialog cannot obscure the save failure. */
    val modelSaveError: String? = null,
    /** Transient status message shown after save or error. */
    val statusMessage: String? = null
) {
    /** Resolves the full [AIModel] for a given [role] from the current assignments. */
    fun modelForRole(role: ModelRole): AIModel? =
        modelAssignments[role]?.let { ModelRegistry.findModelById(it) }
}

/**
 * ViewModel for the AI Settings screen, managing granular model routing preferences.
 */
class AISettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AISettingsUiState())
    val uiState: StateFlow<AISettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Loads persisted settings from DataStore and updates the UI state.
     */
    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsRepository.observeAllModelAssignments(),
                settingsRepository.observeDeepThinking(),
                settingsRepository.observeGodMode(),
                settingsRepository.observeLocalModelUri()
            ) { assignments, deepThinking, godMode, localUri ->
                AISettingsUiState(
                    modelAssignments = assignments,
                    localModelConfigured = !localUri.isNullOrBlank(),
                    deepThinkingEnabled = deepThinking,
                    godModeEnabled = godMode
                )
            }.collect { state ->
                _uiState.update { it.copy(modelAssignments = state.modelAssignments,
                    localModelConfigured = state.localModelConfigured,
                    deepThinkingEnabled = state.deepThinkingEnabled, godModeEnabled = state.godModeEnabled) }
            }
        }
    }

    /**
     * Updates the selected model for a specific [role].
     */
    fun selectModelForRole(role: ModelRole, modelId: String) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, modelSaveError = null) }
        viewModelScope.launch {
            try {
                settingsRepository.setModelForRole(role, modelId)
                _uiState.update {
                    it.copy(
                        modelAssignments = it.modelAssignments + (role to modelId),
                        expandedDropdownRole = null,
                        statusMessage = "✅ ${role.displayName} updated"
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Could not save model selection.",
                        modelSaveError = "Could not save this model. Please try again.")
                }
            } finally { _uiState.update { it.copy(isSaving = false) } }
        }
    }

    /**
     * Toggles Deep Thinking mode on/off.
     */
    fun toggleDeepThinking(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDeepThinking(enabled)
            _uiState.update { it.copy(deepThinkingEnabled = enabled) }
        }
    }

    /**
     * Toggles God Mode on/off.
     */
    fun toggleGodMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setGodMode(enabled)
            _uiState.update { it.copy(godModeEnabled = enabled) }
        }
    }

    /**
     * Expands or collapses the dropdown for a specific [role].
     */
    fun toggleDropdown(role: ModelRole) {
        _uiState.update {
            it.copy(
                expandedDropdownRole = if (it.expandedDropdownRole == role) null else role,
                modelSaveError = null
            )
        }
    }

    /**
     * Dismisses the dropdown for a specific role.
     */
    fun dismissDropdown() {
        _uiState.update { it.copy(expandedDropdownRole = null) }
    }

    /** Surface a recoverable settings action failure. */
    fun showStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    /** Clears the transient status message. */
    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
