package com.omnidev.workspace.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnidev.workspace.data.model.ModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.datastore.core.DataMigration

/**
 * Dedicated DataStore namespace for API keys, kept separate from settings to reduce
 * the risk of accidental logging or debug exports that include credentials.
 *
 * Keys are AES-GCM encrypted with a non-exportable Android Keystore key.
 * Existing plaintext preferences migrate atomically before the first read.
 */
private val Context.apiKeyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omnidev_api_keys",
    produceMigrations = { listOf(object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = currentData.asMap().any { (key, value) -> key.name.startsWith("api_key_") && value is String && !value.startsWith(ApiKeyCipher.PREFIX) }
        override suspend fun migrate(currentData: Preferences): Preferences = withContext(Dispatchers.IO) {
            currentData.toMutablePreferences().apply {
                currentData.asMap().forEach { (key, value) ->
                    if (key.name.startsWith("api_key_") && value is String && !value.startsWith(ApiKeyCipher.PREFIX))
                        this[stringPreferencesKey(key.name)] = ApiKeyCipher.encrypt(key.name, value)
                }
            }
        }
        override suspend fun cleanUp() = Unit
    }) }
)

/**
 * Repository for persisting and retrieving per-provider API keys.
 *
 * Each [ModelProvider] maps to exactly one stored key. The [AgentPipeline] reads
 * the active provider's key before constructing a [CompletionRequest].
 */
class ApiKeyRepository(private val context: Context) {

    // ──────────────────────────────────────────────
    //  Read
    // ──────────────────────────────────────────────

    /**
     * Observes the API key for [provider].
     * Emits null if no key has been saved yet.
     */
    fun observeApiKey(provider: ModelProvider): Flow<String?> =
        context.apiKeyDataStore.data.map { prefs -> prefs[keyFor(provider)]?.let { encrypted -> withContext(Dispatchers.IO) { ApiKeyCipher.decrypt(keyFor(provider).name, encrypted) } } }

    /**
     * Observes which providers currently have an API key stored.
     */
    fun observeConfiguredProviders(): Flow<Set<ModelProvider>> =
        context.apiKeyDataStore.data.map { prefs ->
            ModelProvider.entries.filter { prefs[keyFor(it)] != null }.toSet()
        }

    /**
     * Suspending point-read of a single provider's key.
     * Returns null if no key is stored.
     */
    suspend fun getApiKey(provider: ModelProvider): String? =
        context.apiKeyDataStore.data.first()[keyFor(provider)]?.let { withContext(Dispatchers.IO) { ApiKeyCipher.decrypt(keyFor(provider).name, it) } }

    // ──────────────────────────────────────────────
    //  Write
    // ──────────────────────────────────────────────

    /**
     * Saves or overwrites the API key for [provider].
     */
    suspend fun setApiKey(provider: ModelProvider, apiKey: String) {
        val encrypted = withContext(Dispatchers.IO) { ApiKeyCipher.encrypt(keyFor(provider).name, apiKey) }
        context.apiKeyDataStore.edit { prefs ->
            prefs[keyFor(provider)] = encrypted
        }
    }

    /**
     * Removes the stored API key for [provider].
     */
    suspend fun clearApiKey(provider: ModelProvider) {
        context.apiKeyDataStore.edit { prefs ->
            prefs.remove(keyFor(provider))
        }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun keyFor(provider: ModelProvider): Preferences.Key<String> =
        stringPreferencesKey("api_key_${provider.name.lowercase()}")
}
