package com.omnidev.workspace.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.network.ProviderModelFetcher
import com.omnidev.workspace.data.repository.ApiKeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.network.ProviderEndpoint
import com.omnidev.workspace.registry.ModelRegistry

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
 * Cached result of a provider-catalogue fetch. The map is keyed by [ModelProvider]
 * so multiple providers can be inspected independently in the UI.
 */
data class ProviderModelCatalog(
    val isFetching: Boolean = false,
    val models: List<AIModel> = emptyList(),
    val error: String? = null,
    val lastFetchedAt: Long? = null
)

/**
 * UI state for the Providers / API Key management screen.
 */
data class ProvidersUiState(
    val configuredProviders: List<ProviderEntry> = emptyList(),
    val showAddDialog: Boolean = false,
    val dialogProvider: ModelProvider = ModelProvider.ANTHROPIC,
    val dialogApiKey: String = "",
    val customBaseUrl: String = "",
    val customModelId: String = "",
    val customDisplayName: String = "",
    val dialogKeyVisible: Boolean = false,
    val isSaving: Boolean = false,
    val snackbarMessage: String? = null,
    /** Per-provider cached catalogues fetched from the provider's live API. */
    val catalogs: Map<ModelProvider, ProviderModelCatalog> = emptyMap(),
    /** Which provider's catalogue is currently expanded in the list (null = none). */
    val expandedProvider: ModelProvider? = null
)

/**
 * ViewModel for the API key management screen.
 *
 * In addition to CRUD over [ApiKeyRepository], this view-model exposes a
 * **dynamic-model-discovery** API: given a configured provider, it calls the
 * provider's public REST endpoint (via [ProviderModelFetcher]) and surfaces the
 * list of model names together with their context window and pricing metadata.
 */
class ProvidersViewModel(
    private val apiKeyRepository: ApiKeyRepository,
    private val settingsRepository: SettingsRepository,
    private val modelFetcher: ProviderModelFetcher = ProviderModelFetcher()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(
                customBaseUrl = settingsRepository.observeCustomOpenAiBaseUrl().first().orEmpty(),
                customModelId = settingsRepository.observeCustomOpenAiModelId().first().orEmpty(),
                customDisplayName = settingsRepository.observeCustomOpenAiDisplayName().first().orEmpty()) }
            registerCustomModel(_uiState.value)
        }
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

    fun onCustomBaseUrlChanged(value: String) { _uiState.update { it.copy(customBaseUrl = value) } }
    fun onCustomModelIdChanged(value: String) { _uiState.update { it.copy(customModelId = value) } }
    fun onCustomDisplayNameChanged(value: String) { _uiState.update { it.copy(customDisplayName = value) } }

    private fun registerCustomModel(state: ProvidersUiState) {
        if (state.customModelId.isBlank()) return
        ModelRegistry.setProviderModels(ModelProvider.CUSTOM_OPENAI, listOf(AIModel(
            id = "CUSTOM_OPENAI::${state.customModelId.trim()}",
            displayName = state.customDisplayName.ifBlank { state.customModelId },
            provider = ModelProvider.CUSTOM_OPENAI, contextWindow = 32_768, supportsThinking = true)))
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

    fun saveApiKey() {
        val state = _uiState.value
        val key = state.dialogApiKey.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(snackbarMessage = "API key cannot be empty.") }
            return
        }
        if (state.dialogProvider == ModelProvider.CUSTOM_OPENAI) {
            val validation = runCatching {
                ProviderEndpoint.normalize(state.customBaseUrl)
                require(state.customModelId.isNotBlank()) { "Enter the model ID accepted by your endpoint." }
            }
            if (validation.isFailure) {
                _uiState.update { it.copy(snackbarMessage = validation.exceptionOrNull()?.message) }
                return
            }
        }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            if (state.dialogProvider == ModelProvider.CUSTOM_OPENAI) {
                settingsRepository.setCustomOpenAiBaseUrl(ProviderEndpoint.normalize(state.customBaseUrl))
                settingsRepository.setCustomOpenAiModelId(state.customModelId.trim())
                settingsRepository.setCustomOpenAiDisplayName(state.customDisplayName.trim())
                registerCustomModel(state)
            }
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

    fun removeApiKey(provider: ModelProvider) {
        viewModelScope.launch {
            apiKeyRepository.clearApiKey(provider)
            _uiState.update { state ->
                state.copy(
                    snackbarMessage = "${provider.displayName} key removed.",
                    catalogs = state.catalogs - provider,
                    expandedProvider = if (state.expandedProvider == provider) null else state.expandedProvider
                )
            }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ──────────────────────────────────────────────
    //  Model-catalogue discovery (per-provider API)
    // ──────────────────────────────────────────────

    /**
     * Expands or collapses a provider card to show its fetched model catalogue.
     * The first time a provider is expanded, its model list is fetched from the
     * provider's live API via [ProviderModelFetcher].
     */
    fun toggleExpanded(provider: ModelProvider) {
        val current = _uiState.value.expandedProvider
        if (current == provider) {
            _uiState.update { it.copy(expandedProvider = null) }
            return
        }
        _uiState.update { it.copy(expandedProvider = provider) }
        val cached = _uiState.value.catalogs[provider]
        if (cached == null || (cached.models.isEmpty() && cached.error == null)) {
            refreshModels(provider)
        }
    }

    /**
     * Forces a fresh fetch of the model catalogue for [provider], bypassing any
     * cached entry. Errors are surfaced both as a catalog entry and as a snackbar.
     */
    fun refreshModels(provider: ModelProvider) {
        _uiState.update { state ->
            state.copy(
                catalogs = state.catalogs + (provider to ProviderModelCatalog(
                    isFetching = true,
                    models = state.catalogs[provider]?.models.orEmpty(),
                    error = null,
                    lastFetchedAt = state.catalogs[provider]?.lastFetchedAt
                ))
            )
        }
        viewModelScope.launch {
            val apiKey = apiKeyRepository.getApiKey(provider)
            val result = modelFetcher.fetchModels(provider, apiKey)
            _uiState.update { state ->
                val entry = result.fold(
                    onSuccess = { models ->
                        if (provider != ModelProvider.CUSTOM_OPENAI) ModelRegistry.setProviderModels(provider,
                            models.map { it.copy(id = if ("::" in it.id) it.id else "${provider.name}::${it.id}") })
                        ProviderModelCatalog(
                            isFetching = false,
                            models = models.sortedBy { it.id },
                            error = null,
                            lastFetchedAt = System.currentTimeMillis()
                        )
                    },
                    onFailure = { err ->
                        ProviderModelCatalog(
                            isFetching = false,
                            models = emptyList(),
                            error = err.message ?: "Fetch failed",
                            lastFetchedAt = System.currentTimeMillis()
                        )
                    }
                )
                state.copy(catalogs = state.catalogs + (provider to entry))
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun maskKey(key: String): String = when {
        key.length <= 8 -> "••••••••"
        else -> "${key.take(4)}••••••••${key.takeLast(4)}"
    }
}
