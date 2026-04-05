package com.omnidev.workspace.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repository.ApiKeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A configured provider entry shown in the providers list.
 *
 * @property provider The [ModelProvider] enum value.
 * @property maskedKey The API key with all but the first/last 4 characters hidden.
 */
data class ProviderEntry(
    val provider: ModelProvider,
    val maskedKey: String
)

/**
 * UI state for the Providers / API Key management screen.
 *
 * @property configuredProviders Currently saved providers, sorted by display name.
 * @property showAddDialog Whether the "Add AI Provider" dialog is visible.
 * @property dialogProvider The provider currently selected in the dialog dropdown.
 * @property dialogApiKey The API key text being entered in the dialog.
 * @property dialogKeyVisible Whether the API key text field is shown in plain text.
 * @property isSaving Whether a save operation is in progress.
 * @property snackbarMessage One-shot message to display in a Snackbar (null = none pending).
 */
data class ProvidersUiState(
    val configuredProviders: List<ProviderEntry> = emptyList(),
    val showAddDialog: Boolean = false,
    val dialogProvider: ModelProvider = ModelProvider.ANTHROPIC,
    val dialogApiKey: String = "",
    val dialogKeyVisible: Boolean = false,
    val isSaving: Boolean = false,
    val snackbarMessage: String? = null
)

/**
 * ViewModel for the API key management screen.
 *
 * Observes [ApiKeyRepository] to show currently saved providers, and exposes
 * add/remove actions that the UI can invoke.
 */
class ProvidersViewModel(
    private val apiKeyRepository: ApiKeyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    init {
        // Reactively rebuild the list whenever a key is saved or removed
        apiKeyRepository.observeConfiguredProviders()
            .onEach { providers ->
                val entries = providers.map { provider ->
                    val key = apiKeyRepository.getApiKey(provider) ?: ""
                    ProviderEntry(provider, maskKey(key))
                }.sortedBy { it.provider.displayName }
                _uiState.update { it.copy(configuredProviders = entries) }
            }
            .launchIn(viewModelScope)
    }

    // ──────────────────────────────────────────────
    //  Dialog control
    // ──────────────────────────────────────────────

    fun showAddDialog() {
        _uiState.update {
            it.copy(
                showAddDialog = true,
                dialogApiKey = "",
                dialogProvider = ModelProvider.ANTHROPIC,
                dialogKeyVisible = false
            )
        }
    }

    fun dismissAddDialog() {
        _uiState.update { it.copy(showAddDialog = false) }
    }

    fun onDialogProviderSelected(provider: ModelProvider) {
        _uiState.update { it.copy(dialogProvider = provider) }
    }

    fun onDialogApiKeyChanged(key: String) {
        _uiState.update { it.copy(dialogApiKey = key) }
    }

    fun toggleKeyVisibility() {
        _uiState.update { it.copy(dialogKeyVisible = !it.dialogKeyVisible) }
    }

    // ──────────────────────────────────────────────
    //  Persistence actions
    // ──────────────────────────────────────────────

    /**
     * Validates and saves the current dialog's API key for the selected provider.
     */
    fun saveApiKey() {
        val state = _uiState.value
        val key = state.dialogApiKey.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(snackbarMessage = "API key cannot be empty.") }
            return
        }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            apiKeyRepository.setApiKey(state.dialogProvider, key)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    showAddDialog = false,
                    snackbarMessage = "${state.dialogProvider.displayName} key saved."
                )
            }
        }
    }

    /**
     * Removes the stored API key for [provider].
     */
    fun removeApiKey(provider: ModelProvider) {
        viewModelScope.launch {
            apiKeyRepository.clearApiKey(provider)
            _uiState.update {
                it.copy(snackbarMessage = "${provider.displayName} key removed.")
            }
        }
    }

    /** Clears the pending Snackbar message after it has been shown. */
    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun maskKey(key: String): String = when {
        key.length <= 8 -> "••••••••"
        else -> "${key.take(4)}••••••••${key.takeLast(4)}"
    }
}
