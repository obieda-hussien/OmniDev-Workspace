package com.omnidev.workspace.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnidev.workspace.data.auth.GitHubDeviceFlowManager
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
        // Voice Mode
        val VOICE_MODE_ENABLED = booleanPreferencesKey("voice_mode_enabled")
        val VOICE_TTS_ENABLED = booleanPreferencesKey("voice_tts_enabled")
        // Platform Integrations
        val TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        val TELEGRAM_CHAT_ID = stringPreferencesKey("telegram_chat_id")
        val GITHUB_PAT = stringPreferencesKey("github_pat")
        val GITHUB_OAUTH_TOKEN = stringPreferencesKey("github_oauth_token")
        /** "copilot" or "models" — which GitHub sub-mode the user selected. */
        val GITHUB_SUB_MODE = stringPreferencesKey("github_sub_mode")
        val DISCORD_WEBHOOK_URL = stringPreferencesKey("discord_webhook_url")
        // Discord Bot (full bot token integration)
        val DISCORD_BOT_TOKEN = stringPreferencesKey("discord_bot_token")
        val DISCORD_LISTENER_CHANNEL_ID = stringPreferencesKey("discord_listener_channel_id")
        val DISCORD_LISTENER_ENABLED = booleanPreferencesKey("discord_listener_enabled")
        // WhatsApp Business Cloud API
        val WHATSAPP_PHONE_NUMBER_ID = stringPreferencesKey("whatsapp_phone_number_id")
        val WHATSAPP_ACCESS_TOKEN = stringPreferencesKey("whatsapp_access_token")
        // WhatsApp Baileys Bridge (self-hosted Node.js bridge using Baileys library)
        val WHATSAPP_BRIDGE_URL = stringPreferencesKey("whatsapp_bridge_url")
        val WHATSAPP_BRIDGE_PHONE = stringPreferencesKey("whatsapp_bridge_phone")
        val WHATSAPP_BRIDGE_ENABLED = booleanPreferencesKey("whatsapp_bridge_enabled")
        val NOTION_API_KEY = stringPreferencesKey("notion_api_key")
        val NOTION_DATABASE_ID = stringPreferencesKey("notion_database_id")
        // Slack Bot Token
        val SLACK_BOT_TOKEN = stringPreferencesKey("slack_bot_token")
        // SendGrid Email
        val SENDGRID_API_KEY = stringPreferencesKey("sendgrid_api_key")
        // n8n Automation
        val N8N_BASE_URL = stringPreferencesKey("n8n_base_url")
        val N8N_API_KEY = stringPreferencesKey("n8n_api_key")
        // Web search providers
        val SERP_API_KEY = stringPreferencesKey("serp_api_key")
        val GOOGLE_CSE_API_KEY = stringPreferencesKey("google_cse_api_key")
        val GOOGLE_CSE_CX = stringPreferencesKey("google_cse_cx")
        // Local Edge Model
        val LOCAL_MODEL_URI = stringPreferencesKey("local_model_uri")
        val LOCAL_MODEL_NAME = stringPreferencesKey("local_model_name")
        // Local Engine Selection (llama.cpp vs BitNet.cpp)
        val LOCAL_ENGINE_TYPE = stringPreferencesKey("local_engine_type")
        // User Profile
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_PERSONA = stringPreferencesKey("user_persona")
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
    //  Platform Integrations
    // ──────────────────────────────────────────────

    /** Observes the Telegram Bot Token. */
    fun observeTelegramBotToken(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.TELEGRAM_BOT_TOKEN] }

    suspend fun setTelegramBotToken(token: String?) {
        context.settingsDataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(Keys.TELEGRAM_BOT_TOKEN) else prefs[Keys.TELEGRAM_BOT_TOKEN] = token
        }
    }

    /** Observes the Telegram Chat ID. */
    fun observeTelegramChatId(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.TELEGRAM_CHAT_ID] }

    suspend fun setTelegramChatId(chatId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (chatId.isNullOrBlank()) prefs.remove(Keys.TELEGRAM_CHAT_ID) else prefs[Keys.TELEGRAM_CHAT_ID] = chatId
        }
    }

    /** Observes the GitHub Personal Access Token. */
    fun observeGitHubPat(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.GITHUB_PAT] }

    suspend fun setGitHubPat(pat: String?) {
        context.settingsDataStore.edit { prefs ->
            if (pat.isNullOrBlank()) prefs.remove(Keys.GITHUB_PAT) else prefs[Keys.GITHUB_PAT] = pat
        }
    }

    // GitHub OAuth Token (from OAuth flow, not manual PAT)
    fun observeGitHubOAuthToken(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.GITHUB_OAUTH_TOKEN] }

    suspend fun setGitHubOAuthToken(token: String?) {
        context.settingsDataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(Keys.GITHUB_OAUTH_TOKEN) else prefs[Keys.GITHUB_OAUTH_TOKEN] = token
        }
    }

    /** Observes the chosen GitHub sub-mode: "copilot" or "models". Defaults to "models". */
    fun observeGitHubSubMode(): Flow<String> =
        context.settingsDataStore.data.map { it[Keys.GITHUB_SUB_MODE] ?: GitHubDeviceFlowManager.SubMode.MODELS.serializedName }

    suspend fun setGitHubSubMode(mode: String) {
        context.settingsDataStore.edit { it[Keys.GITHUB_SUB_MODE] = mode }
    }

    // Discord
    fun observeDiscordWebhookUrl(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.DISCORD_WEBHOOK_URL] }

    suspend fun setDiscordWebhookUrl(url: String?) {
        context.settingsDataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(Keys.DISCORD_WEBHOOK_URL) else prefs[Keys.DISCORD_WEBHOOK_URL] = url
        }
    }

    // Discord Bot Token & Listener
    fun observeDiscordBotToken(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.DISCORD_BOT_TOKEN] }

    suspend fun setDiscordBotToken(token: String?) {
        context.settingsDataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(Keys.DISCORD_BOT_TOKEN) else prefs[Keys.DISCORD_BOT_TOKEN] = token
        }
    }

    fun observeDiscordListenerChannelId(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.DISCORD_LISTENER_CHANNEL_ID] }

    suspend fun setDiscordListenerChannelId(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(Keys.DISCORD_LISTENER_CHANNEL_ID)
            else prefs[Keys.DISCORD_LISTENER_CHANNEL_ID] = id
        }
    }

    fun observeDiscordListenerEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DISCORD_LISTENER_ENABLED] ?: false }

    suspend fun setDiscordListenerEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DISCORD_LISTENER_ENABLED] = enabled }
    }

    // WhatsApp Business Cloud API
    fun observeWhatsAppPhoneNumberId(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.WHATSAPP_PHONE_NUMBER_ID] }

    suspend fun setWhatsAppPhoneNumberId(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(Keys.WHATSAPP_PHONE_NUMBER_ID)
            else prefs[Keys.WHATSAPP_PHONE_NUMBER_ID] = id
        }
    }

    fun observeWhatsAppAccessToken(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.WHATSAPP_ACCESS_TOKEN] }

    suspend fun setWhatsAppAccessToken(token: String?) {
        context.settingsDataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(Keys.WHATSAPP_ACCESS_TOKEN)
            else prefs[Keys.WHATSAPP_ACCESS_TOKEN] = token
        }
    }

    // Notion
    // WhatsApp Baileys Bridge
    fun observeWhatsAppBridgeUrl(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.WHATSAPP_BRIDGE_URL] }

    suspend fun setWhatsAppBridgeUrl(url: String?) {
        context.settingsDataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(Keys.WHATSAPP_BRIDGE_URL)
            else prefs[Keys.WHATSAPP_BRIDGE_URL] = url
        }
    }

    fun observeWhatsAppBridgePhone(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.WHATSAPP_BRIDGE_PHONE] }

    suspend fun setWhatsAppBridgePhone(phone: String?) {
        context.settingsDataStore.edit { prefs ->
            if (phone.isNullOrBlank()) prefs.remove(Keys.WHATSAPP_BRIDGE_PHONE)
            else prefs[Keys.WHATSAPP_BRIDGE_PHONE] = phone
        }
    }

    fun observeWhatsAppBridgeEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.WHATSAPP_BRIDGE_ENABLED] ?: false }

    suspend fun setWhatsAppBridgeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WHATSAPP_BRIDGE_ENABLED] = enabled }
    }

    fun observeNotionApiKey(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.NOTION_API_KEY] }

    suspend fun setNotionApiKey(key: String?) {
        context.settingsDataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(Keys.NOTION_API_KEY) else prefs[Keys.NOTION_API_KEY] = key
        }
    }

    fun observeNotionDatabaseId(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.NOTION_DATABASE_ID] }

    suspend fun setNotionDatabaseId(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(Keys.NOTION_DATABASE_ID) else prefs[Keys.NOTION_DATABASE_ID] = id
        }
    }

    // Slack Bot Token
    fun observeSlackBotToken(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.SLACK_BOT_TOKEN] }

    suspend fun setSlackBotToken(token: String?) {
        context.settingsDataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(Keys.SLACK_BOT_TOKEN) else prefs[Keys.SLACK_BOT_TOKEN] = token
        }
    }

    // SendGrid Email
    fun observeSendGridApiKey(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.SENDGRID_API_KEY] }

    suspend fun setSendGridApiKey(key: String?) {
        context.settingsDataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(Keys.SENDGRID_API_KEY) else prefs[Keys.SENDGRID_API_KEY] = key
        }
    }

    // ──────────────────────────────────────────────
    //  n8n Automation
    // ──────────────────────────────────────────────

    fun observeN8nBaseUrl(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.N8N_BASE_URL] }

    suspend fun setN8nBaseUrl(url: String?) {
        context.settingsDataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(Keys.N8N_BASE_URL) else prefs[Keys.N8N_BASE_URL] = url
        }
    }

    fun observeN8nApiKey(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.N8N_API_KEY] }

    suspend fun setN8nApiKey(key: String?) {
        context.settingsDataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(Keys.N8N_API_KEY) else prefs[Keys.N8N_API_KEY] = key
        }
    }

    // ──────────────────────────────────────────────
    //  Web Search Providers
    // ──────────────────────────────────────────────

    fun observeSerpApiKey(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.SERP_API_KEY] }

    suspend fun setSerpApiKey(key: String?) {
        context.settingsDataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(Keys.SERP_API_KEY) else prefs[Keys.SERP_API_KEY] = key
        }
    }

    fun observeGoogleCseApiKey(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.GOOGLE_CSE_API_KEY] }

    suspend fun setGoogleCseApiKey(key: String?) {
        context.settingsDataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(Keys.GOOGLE_CSE_API_KEY) else prefs[Keys.GOOGLE_CSE_API_KEY] = key
        }
    }

    fun observeGoogleCseCx(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.GOOGLE_CSE_CX] }

    suspend fun setGoogleCseCx(cx: String?) {
        context.settingsDataStore.edit { prefs ->
            if (cx.isNullOrBlank()) prefs.remove(Keys.GOOGLE_CSE_CX) else prefs[Keys.GOOGLE_CSE_CX] = cx
        }
    }

    // ──────────────────────────────────────────────
    //  Local Edge Model
    // ──────────────────────────────────────────────

    fun observeLocalModelUri(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.LOCAL_MODEL_URI] }

    suspend fun setLocalModelUri(uriString: String?) {
        context.settingsDataStore.edit { prefs ->
            if (uriString.isNullOrBlank()) prefs.remove(Keys.LOCAL_MODEL_URI) else prefs[Keys.LOCAL_MODEL_URI] = uriString
        }
    }

    fun observeLocalModelName(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.LOCAL_MODEL_NAME] }

    suspend fun setLocalModelName(name: String?) {
        context.settingsDataStore.edit { prefs ->
            if (name.isNullOrBlank()) prefs.remove(Keys.LOCAL_MODEL_NAME) else prefs[Keys.LOCAL_MODEL_NAME] = name
        }
    }

    fun observeLocalEngineType(): Flow<String> =
        context.settingsDataStore.data.map { it[Keys.LOCAL_ENGINE_TYPE] ?: com.omnidev.workspace.data.localllm.LocalEngineType.LLAMA_CPP.name }

    suspend fun setLocalEngineType(engineType: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LOCAL_ENGINE_TYPE] = engineType
        }
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

    // ──────────────────────────────────────────────
    //  Voice Mode
    // ──────────────────────────────────────────────

    fun observeVoiceMode(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.VOICE_MODE_ENABLED] ?: false }

    suspend fun setVoiceMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.VOICE_MODE_ENABLED] = enabled }
    }

    fun observeTtsEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.VOICE_TTS_ENABLED] ?: true }

    suspend fun setTtsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.VOICE_TTS_ENABLED] = enabled }
    }

    // ──────────────────────────────────────────────
    //  User Profile
    // ──────────────────────────────────────────────

    /** The user's display name, shown in voice greetings and personalised prompts. */
    fun observeUserName(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.USER_NAME] }

    suspend fun setUserName(name: String?) {
        context.settingsDataStore.edit { prefs ->
            if (name.isNullOrBlank()) prefs.remove(Keys.USER_NAME) else prefs[Keys.USER_NAME] = name
        }
    }

    /**
     * A short free-text bio the user writes about themselves (e.g. "Senior Android developer,
     * prefers Kotlin, builds indie apps"). Injected into the system prompt so the AI can tailor
     * advice, style and code examples to this specific person.
     */
    fun observeUserPersona(): Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.USER_PERSONA] }

    suspend fun setUserPersona(persona: String?) {
        context.settingsDataStore.edit { prefs ->
            if (persona.isNullOrBlank()) prefs.remove(Keys.USER_PERSONA)
            else prefs[Keys.USER_PERSONA] = persona
        }
    }
}
