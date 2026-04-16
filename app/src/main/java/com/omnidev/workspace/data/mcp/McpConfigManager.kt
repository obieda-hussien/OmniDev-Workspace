package com.omnidev.workspace.data.mcp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class McpConfigManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val sharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "mcp_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun addServer(name: String, config: McpServerConfig) = withContext(Dispatchers.IO) {
        val currentServers = getServersWrapper().mcpServers.toMutableMap()
        currentServers[name] = config
        saveConfig(McpConfigWrapper(currentServers))
    }

    suspend fun removeServer(name: String) = withContext(Dispatchers.IO) {
        val currentServers = getServersWrapper().mcpServers.toMutableMap()
        currentServers.remove(name)
        saveConfig(McpConfigWrapper(currentServers))
    }

    suspend fun updateServer(name: String, config: McpServerConfig) = withContext(Dispatchers.IO) {
        addServer(name, config)
    }

    suspend fun getServers(): Map<String, McpServerConfig> = withContext(Dispatchers.IO) {
        getServersWrapper().mcpServers
    }

    private fun getServersWrapper(): McpConfigWrapper {
        val jsonString = sharedPreferences.getString(KEY_CONFIG, null)
        return if (jsonString != null) {
            try {
                json.decodeFromString<McpConfigWrapper>(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
                McpConfigWrapper()
            }
        } else {
            McpConfigWrapper()
        }
    }

    private fun saveConfig(config: McpConfigWrapper) {
        val jsonString = json.encodeToString(config)
        sharedPreferences.edit().putString(KEY_CONFIG, jsonString).apply()
    }

    companion object {
        private const val KEY_CONFIG = "mcp_config_json"
    }
}
