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

/**
 * Dedicated DataStore namespace for API keys, kept separate from settings to reduce
 * the risk of accidental logging or debug exports that include credentials.
 *
 * NOTE: Keys are stored in the app's private data directory (inaccessible to other
 * apps without root). For production-grade hardening, wrap with Android Keystore
 * encryption using `EncryptedSharedPreferences` from `androidx.security.crypto`.
 */
private val Context.apiKeyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omnidev_api_keys"
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
        context.apiKeyDataStore.data.map { prefs -> prefs[keyFor(provider)] }

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
        context.apiKeyDataStore.data.first()[keyFor(provider)]

    // ──────────────────────────────────────────────
    //  Write
    // ──────────────────────────────────────────────

    /**
     * Saves or overwrites the API key for [provider].
     */
    suspend fun setApiKey(provider: ModelProvider, apiKey: String) {
        context.apiKeyDataStore.edit { prefs ->
            prefs[keyFor(provider)] = apiKey
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
