package com.omnidev.workspace.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Singleton DataStore instance scoped to the application context. */
private val Context.analyticsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omnidev_analytics"
)

// ── Data Classes ──────────────────────────────────────────────────────────────

data class ModelStats(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costUsd: Double = 0.0
)

data class AnalyticsStats(
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalCostUsd: Double = 0.0,
    val tokensByModel: Map<String, ModelStats> = emptyMap(),
    val toolUsageCount: Map<String, Int> = emptyMap(),
    val totalAgentRuns: Int = 0,
    val totalSwarmRuns: Int = 0
)

// ── Repository ────────────────────────────────────────────────────────────────

/**
 * Repository responsible for persisting and retrieving cumulative analytics data.
 * All data is stored as a single JSON string in DataStore under the key "analytics_v1".
 */
class AnalyticsRepository(private val context: Context) {

    private object Keys {
        val ANALYTICS_JSON = stringPreferencesKey("analytics_v1")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun recordTokenUsage(
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double
    ) {
        context.analyticsDataStore.edit { prefs ->
            val current = prefs.readStats()
            val modelStats = current.tokensByModel[modelId] ?: ModelStats()
            val updated = current.copy(
                totalInputTokens = current.totalInputTokens + inputTokens,
                totalOutputTokens = current.totalOutputTokens + outputTokens,
                totalCostUsd = current.totalCostUsd + costUsd,
                tokensByModel = current.tokensByModel + (modelId to ModelStats(
                    inputTokens = modelStats.inputTokens + inputTokens,
                    outputTokens = modelStats.outputTokens + outputTokens,
                    costUsd = modelStats.costUsd + costUsd
                ))
            )
            prefs[Keys.ANALYTICS_JSON] = updated.toJson()
        }
    }

    suspend fun recordToolUsage(toolName: String) {
        context.analyticsDataStore.edit { prefs ->
            val current = prefs.readStats()
            val count = current.toolUsageCount[toolName] ?: 0
            val updated = current.copy(
                toolUsageCount = current.toolUsageCount + (toolName to count + 1)
            )
            prefs[Keys.ANALYTICS_JSON] = updated.toJson()
        }
    }

    suspend fun recordAgentRun(isSwarm: Boolean) {
        context.analyticsDataStore.edit { prefs ->
            val current = prefs.readStats()
            val updated = if (isSwarm) {
                current.copy(totalSwarmRuns = current.totalSwarmRuns + 1)
            } else {
                current.copy(totalAgentRuns = current.totalAgentRuns + 1)
            }
            prefs[Keys.ANALYTICS_JSON] = updated.toJson()
        }
    }

    suspend fun getStats(): AnalyticsStats {
        return context.analyticsDataStore.data.first().readStats()
    }

    suspend fun clearStats() {
        context.analyticsDataStore.edit { prefs ->
            prefs[Keys.ANALYTICS_JSON] = AnalyticsStats().toJson()
        }
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    private fun Preferences.readStats(): AnalyticsStats {
        val json = this[Keys.ANALYTICS_JSON] ?: return AnalyticsStats()
        return try {
            json.toAnalyticsStats()
        } catch (_: Exception) {
            AnalyticsStats()
        }
    }

    private fun AnalyticsStats.toJson(): String {
        val root = JSONObject()
        root.put("total_input_tokens", totalInputTokens)
        root.put("total_output_tokens", totalOutputTokens)
        root.put("total_cost_usd", totalCostUsd)
        root.put("total_agent_runs", totalAgentRuns)
        root.put("total_swarm_runs", totalSwarmRuns)

        val byModel = JSONObject()
        tokensByModel.forEach { (modelId, stats) ->
            val modelObj = JSONObject()
            modelObj.put("input_tokens", stats.inputTokens)
            modelObj.put("output_tokens", stats.outputTokens)
            modelObj.put("cost_usd", stats.costUsd)
            byModel.put(modelId, modelObj)
        }
        root.put("tokens_by_model", byModel)

        val toolUsage = JSONObject()
        toolUsageCount.forEach { (toolName, count) ->
            toolUsage.put(toolName, count)
        }
        root.put("tool_usage_count", toolUsage)

        return root.toString()
    }

    private fun String.toAnalyticsStats(): AnalyticsStats {
        val root = JSONObject(this)

        val byModel = mutableMapOf<String, ModelStats>()
        val byModelObj = root.optJSONObject("tokens_by_model") ?: JSONObject()
        byModelObj.keys().forEach { modelId ->
            val obj = byModelObj.getJSONObject(modelId)
            byModel[modelId] = ModelStats(
                inputTokens = obj.optLong("input_tokens"),
                outputTokens = obj.optLong("output_tokens"),
                costUsd = obj.optDouble("cost_usd")
            )
        }

        val toolUsage = mutableMapOf<String, Int>()
        val toolObj = root.optJSONObject("tool_usage_count") ?: JSONObject()
        toolObj.keys().forEach { toolName ->
            toolUsage[toolName] = toolObj.optInt(toolName)
        }

        return AnalyticsStats(
            totalInputTokens = root.optLong("total_input_tokens"),
            totalOutputTokens = root.optLong("total_output_tokens"),
            totalCostUsd = root.optDouble("total_cost_usd"),
            totalAgentRuns = root.optInt("total_agent_runs"),
            totalSwarmRuns = root.optInt("total_swarm_runs"),
            tokensByModel = byModel,
            toolUsageCount = toolUsage
        )
    }
}
