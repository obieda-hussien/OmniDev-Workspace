package com.omnidev.workspace.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Singleton DataStore instance scoped to the application context. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omnidev_settings"
)

/**
 * Repository responsible for persisting and retrieving the user's granular model routing
 * preferences. Each [ModelRole] maps to a selected AI model ID, enabling role-based routing
 * (e.g., a fast model for chat, a powerful model for orchestration).
 *
 * Also manages global feature flags such as Deep Thinking mode and the active target context
 * scope for file operations.
 */
class SettingsRepository(private val context: Context) {

    // ──────────────────────────────────────────────
    //  Preference Keys
    // ──────────────────────────────────────────────

    private object Keys {
        val CHAT_MODEL_ID = stringPreferencesKey("chat_model_id")
        val AGENT_MODEL_ID = stringPreferencesKey("agent_model_id")
        val SWARM_ORCHESTRATOR_MODEL_ID = stringPreferencesKey("swarm_orchestrator_model_id")
        val SWARM_WORKER_MODEL_ID = stringPreferencesKey("swarm_worker_model_id")
        val DEEP_THINKING_ENABLED = booleanPreferencesKey("deep_thinking_enabled")
        /** Legacy string path — used as a fallback display path. */
        val TARGET_CONTEXT_PATH = stringPreferencesKey("target_context_path")
        /** SAF content:// URI string for the user's chosen directory (persistable permission). */
        val TARGET_CONTEXT_URI = stringPreferencesKey("target_context_uri")
        // Custom system prompts (per-mode)
        val CUSTOM_CHAT_PROMPT = stringPreferencesKey("custom_chat_prompt")
        val CUSTOM_AGENT_PROMPT = stringPreferencesKey("custom_agent_prompt")
        val CUSTOM_ORCHESTRATOR_PROMPT = stringPreferencesKey("custom_orchestrator_prompt")
        // Advanced engine settings
        /**
         * Temperature is stored as a String rather than a Float to avoid IEEE 754
         * precision issues when serialising/deserialising via DataStore's StringPreferencesKey.
         */
        val TEMPERATURE = stringPreferencesKey("engine_temperature")
        val MAX_TOKENS = stringPreferencesKey("engine_max_tokens")
        // God Mode
        val GOD_MODE_ENABLED = booleanPreferencesKey("god_mode_enabled")
    }

    // ──────────────────────────────────────────────
    //  Model Routing — Read
    // ──────────────────────────────────────────────

    /**
     * Observes the selected model ID for a given [role].
     * Emits the default model ID if no selection has been persisted yet.
     */
    fun observeModelIdForRole(role: ModelRole): Flow<String> {
        val key = keyForRole(role)
        val defaultId = ModelRegistry.getDefaultModelForRole(role).id
        return context.settingsDataStore.data.map { preferences ->
            preferences[key] ?: defaultId
        }
    }

    /**
     * Observes a complete map of all role → model ID assignments.
     */
    fun observeAllModelAssignments(): Flow<Map<ModelRole, String>> {
        return context.settingsDataStore.data.map { preferences ->
            ModelRole.entries.associateWith { role ->
                val key = keyForRole(role)
                preferences[key] ?: ModelRegistry.getDefaultModelForRole(role).id
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Model Routing — Write
    // ──────────────────────────────────────────────

    /**
     * Persists the selected [modelId] for a given [role].
     * Validates the model ID against the [ModelRegistry] before saving.
     *
     * @throws IllegalArgumentException if [modelId] is not in the registry.
     */
    suspend fun setModelForRole(role: ModelRole, modelId: String) {
        // Validate that the model exists in the registry
        ModelRegistry.getModelById(modelId)
        val key = keyForRole(role)
        context.settingsDataStore.edit { preferences ->
            preferences[key] = modelId
        }
    }

    // ──────────────────────────────────────────────
    //  Deep Thinking Mode
    // ──────────────────────────────────────────────

    /** Observes whether Deep Thinking mode is enabled. */
    fun observeDeepThinking(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DEEP_THINKING_ENABLED] ?: false }

    /** Toggles Deep Thinking mode on or off. */
    suspend fun setDeepThinking(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DEEP_THINKING_ENABLED] = enabled }
    }

    // ──────────────────────────────────────────────
    //  Target Context Scope
    // ──────────────────────────────────────────────

    /** Observes the active target context path for scoped file operations. */
    fun observeTargetContext(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.TARGET_CONTEXT_PATH] }

    /** Sets the active target context directory path. */
    suspend fun setTargetContext(path: String?) {
        context.settingsDataStore.edit { preferences ->
            if (path != null) {
                preferences[Keys.TARGET_CONTEXT_PATH] = path
            } else {
                preferences.remove(Keys.TARGET_CONTEXT_PATH)
            }
        }
    }

    /**
     * Observes the persisted SAF content:// URI string for the target context directory.
     * This URI has persistable read/write permissions taken via [ContentResolver].
     */
    fun observeTargetContextUri(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.TARGET_CONTEXT_URI] }

    /**
     * Stores the SAF [uriString] for the user-selected directory.
     * Also updates the path key for FileToolManager scope validation.
     *
     * @param uriString The `content://` URI string returned by ACTION_OPEN_DOCUMENT_TREE.
     * @param derivedPath The equivalent filesystem path (e.g. `/storage/emulated/0/MyProjects`),
     *        used by FileToolManager for scope-path validation.
     */
    suspend fun setTargetContextUri(uriString: String?, derivedPath: String?) {
        context.settingsDataStore.edit { preferences ->
            if (uriString != null) {
                preferences[Keys.TARGET_CONTEXT_URI] = uriString
            } else {
                preferences.remove(Keys.TARGET_CONTEXT_URI)
            }
            if (derivedPath != null) {
                preferences[Keys.TARGET_CONTEXT_PATH] = derivedPath
            } else {
                preferences.remove(Keys.TARGET_CONTEXT_PATH)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Custom System Prompts
    // ──────────────────────────────────────────────

    enum class PromptRole { CHAT, AGENT, ORCHESTRATOR }

    fun observeCustomPrompt(role: PromptRole): Flow<String?> {
        val key = when (role) {
            PromptRole.CHAT -> Keys.CUSTOM_CHAT_PROMPT
            PromptRole.AGENT -> Keys.CUSTOM_AGENT_PROMPT
            PromptRole.ORCHESTRATOR -> Keys.CUSTOM_ORCHESTRATOR_PROMPT
        }
        return context.settingsDataStore.data.map { it[key] }
    }

    suspend fun setCustomPrompt(role: PromptRole, prompt: String?) {
        val key = when (role) {
            PromptRole.CHAT -> Keys.CUSTOM_CHAT_PROMPT
            PromptRole.AGENT -> Keys.CUSTOM_AGENT_PROMPT
            PromptRole.ORCHESTRATOR -> Keys.CUSTOM_ORCHESTRATOR_PROMPT
        }
        context.settingsDataStore.edit { prefs ->
            if (prompt.isNullOrBlank()) prefs.remove(key) else prefs[key] = prompt
        }
    }

    // ──────────────────────────────────────────────
    //  Advanced Engine Settings
    // ──────────────────────────────────────────────

    /** Observes the generation temperature (0.0–2.0). Default 0.7. */
    fun observeTemperature(): Flow<Float> =
        context.settingsDataStore.data.map { (it[Keys.TEMPERATURE]?.toFloatOrNull()) ?: 0.7f }

    suspend fun setTemperature(value: Float) {
        context.settingsDataStore.edit { it[Keys.TEMPERATURE] = value.toString() }
    }

    /** Observes the max output tokens override (null = use model default). */
    fun observeMaxTokens(): Flow<Int?> =
        context.settingsDataStore.data.map { it[Keys.MAX_TOKENS]?.toIntOrNull() }

    suspend fun setMaxTokens(value: Int?) {
        context.settingsDataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.MAX_TOKENS) else prefs[Keys.MAX_TOKENS] = value.toString()
        }
    }

    // ──────────────────────────────────────────────
    //  God Mode
    // ──────────────────────────────────────────────

    fun observeGodMode(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.GOD_MODE_ENABLED] ?: false }

    suspend fun setGodMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GOD_MODE_ENABLED] = enabled }
    }

    // ──────────────────────────────────────────────
    //  Internal Helpers
    // ──────────────────────────────────────────────

    private fun keyForRole(role: ModelRole): Preferences.Key<String> = when (role) {
        ModelRole.CHAT -> Keys.CHAT_MODEL_ID
        ModelRole.AGENT -> Keys.AGENT_MODEL_ID
        ModelRole.SWARM_ORCHESTRATOR -> Keys.SWARM_ORCHESTRATOR_MODEL_ID
        ModelRole.SWARM_WORKER -> Keys.SWARM_WORKER_MODEL_ID
    }
}
