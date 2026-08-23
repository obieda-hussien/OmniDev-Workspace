package com.omnidev.workspace.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Singleton DataStore instance scoped to the application context. */
private val Context.analyticsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omnidev_analytics"
)

// ── Data Classes ──────────────────────────────────────────────────────────────

/**
 * Persistent per-model raw statistics.
 *
 * These are the low-level counters written every time a model is invoked. Richer
 * aggregates (provider-level roll-ups, top model per provider, etc.) are derived
 * on-the-fly by [AnalyticsStats.providerBreakdown] so we don't duplicate state.
 */

/**
 * Persistent per-tool execution statistics.
 */
data class ToolStats(
    val toolName: String = "",
    val executionCount: Long = 0L,
    val successCount: Long = 0L,
    val totalDurationMs: Long = 0L
) {
    val averageDurationMs: Double
        get() = if (executionCount <= 0L) 0.0 else totalDurationMs.toDouble() / executionCount.toDouble()
    val successRate: Double
        get() = if (executionCount <= 0L) 0.0 else successCount.toDouble() / executionCount.toDouble()
}

data class ModelStats(
    /** Provider the model belongs to — cached so UI doesn't have to look up the registry. */
    val provider: ModelProvider? = null,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costUsd: Double = 0.0,
    /** Number of completion API calls that used this model. */
    val requestCount: Long = 0L,
    /** Number of times the request ended in an error/failure. */
    val errorCount: Long = 0L,
    /** Sum of latencies in milliseconds — divide by [requestCount] to get the mean. */
    val totalLatencyMs: Long = 0L,
    /** Wall-clock time of first ever call to this model (epoch millis, 0 if never). */
    val firstUsedAt: Long = 0L,
    /** Wall-clock time of most recent call (epoch millis, 0 if never). */
    val lastUsedAt: Long = 0L
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val averageTokensPerRequest: Double
        get() = if (requestCount <= 0L) 0.0 else totalTokens.toDouble() / requestCount.toDouble()
    val averageLatencyMs: Double
        get() = if (requestCount <= 0L) 0.0 else totalLatencyMs.toDouble() / requestCount.toDouble()
    val errorRate: Double
        get() = if (requestCount <= 0L) 0.0 else errorCount.toDouble() / requestCount.toDouble()
}

/**
 * Aggregated per-provider roll-up computed from the underlying [ModelStats] map.
 *
 * All fields are derived — this class is never persisted directly, it's built lazily
 * by [AnalyticsStats.providerBreakdown].
 */
data class ProviderStats(
    val provider: ModelProvider,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val requestCount: Long,
    val errorCount: Long,
    val totalLatencyMs: Long,
    /** Flat list of (modelId, stats) pairs belonging to this provider, sorted descending by total tokens. */
    val models: List<Pair<String, ModelStats>>,
    val firstUsedAt: Long,
    val lastUsedAt: Long
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val modelCount: Int get() = models.size
    /** The most-used model (by total tokens) within this provider, or null if none. */
    val topModel: Pair<String, ModelStats>? get() = models.firstOrNull()
    val averageLatencyMs: Double
        get() = if (requestCount <= 0L) 0.0 else totalLatencyMs.toDouble() / requestCount.toDouble()
    val averageTokensPerRequest: Double
        get() = if (requestCount <= 0L) 0.0 else totalTokens.toDouble() / requestCount.toDouble()
    val errorRate: Double
        get() = if (requestCount <= 0L) 0.0 else errorCount.toDouble() / requestCount.toDouble()
}

/**
 * Daily usage bucket used to build time-series charts (last 30 days).
 *
 * [dateKey] is an ISO date in `yyyy-MM-dd` UTC format so buckets are deterministic
 * across timezones and easy to sort lexicographically.
 */
data class DailyUsage(
    val dateKey: String,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costUsd: Double = 0.0,
    val requestCount: Long = 0L
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * Root analytics state held in DataStore.
 *
 * The shape is kept intentionally flat and JSON-friendly. Derived roll-ups are
 * exposed as cached lazy properties ([providerBreakdown], [topModelsByTokens], …)
 * so the UI layer can consume rich insights without touching the repository.
 */
data class AnalyticsStats(
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalCostUsd: Double = 0.0,
    val totalCostEgp: Double = 0.0,
    val totalRequests: Long = 0L,
    val totalErrors: Long = 0L,
    val tokensByModel: Map<String, ModelStats> = emptyMap(),
    val toolUsageCount: Map<String, ToolStats> = emptyMap(),
    val totalAgentRuns: Int = 0,
    val totalSwarmRuns: Int = 0,
    /** Daily buckets keyed by ISO date (yyyy-MM-dd UTC). Capped at 90 entries by the repo. */
    val dailyUsage: Map<String, DailyUsage> = emptyMap(),
    /** Wall-clock of first ever recorded event, 0 if none. */
    val firstRecordedAt: Long = 0L,
    /** Wall-clock of most recent recorded event, 0 if none. */
    val lastRecordedAt: Long = 0L
) {
    val totalTokens: Long get() = totalInputTokens + totalOutputTokens

    val averageTokensPerRequest: Double
        get() = if (totalRequests <= 0L) 0.0 else totalTokens.toDouble() / totalRequests.toDouble()

    val errorRate: Double
        get() = if (totalRequests <= 0L) 0.0 else totalErrors.toDouble() / totalRequests.toDouble()

    /** Per-provider roll-up, sorted descending by total tokens (heaviest user first). */
    val providerBreakdown: List<ProviderStats> by lazy { buildProviderBreakdown() }

    /** Top N models globally ranked by total tokens consumed. */
    val topModelsByTokens: List<Pair<String, ModelStats>> by lazy {
        tokensByModel.entries
            .sortedByDescending { it.value.totalTokens }
            .map { it.key to it.value }
    }

    /** Top N models globally ranked by total USD cost. */
    val topModelsByCost: List<Pair<String, ModelStats>> by lazy {
        tokensByModel.entries
            .sortedByDescending { it.value.costUsd }
            .map { it.key to it.value }
    }

    /** Top N models globally ranked by raw request count. */
    val topModelsByRequests: List<Pair<String, ModelStats>> by lazy {
        tokensByModel.entries
            .sortedByDescending { it.value.requestCount }
            .map { it.key to it.value }
    }

    /** Daily usage timeline ordered chronologically (oldest first), ready for charts. */
    val dailyTimeline: List<DailyUsage> by lazy {
        dailyUsage.values.sortedBy { it.dateKey }
    }

    private fun buildProviderBreakdown(): List<ProviderStats> {
        if (tokensByModel.isEmpty()) return emptyList()

        // Group by provider (fallback to inference if the persisted entry doesn't carry it,
        // e.g. old data written before provider caching was introduced).
        val grouped = tokensByModel.entries.groupBy { (modelId, stats) ->
            stats.provider ?: inferProvider(modelId)
        }

        return grouped.map { (provider, entries) ->
            val models = entries
                .map { it.key to it.value }
                .sortedByDescending { it.second.totalTokens }

            val sumInput = entries.sumOf { it.value.inputTokens }
            val sumOutput = entries.sumOf { it.value.outputTokens }
            val sumCost = entries.sumOf { it.value.costUsd }
            val sumReq = entries.sumOf { it.value.requestCount }
            val sumErr = entries.sumOf { it.value.errorCount }
            val sumLat = entries.sumOf { it.value.totalLatencyMs }
            val firstUsed = entries.mapNotNull { it.value.firstUsedAt.takeIf { v -> v > 0 } }.minOrNull() ?: 0L
            val lastUsed = entries.mapNotNull { it.value.lastUsedAt.takeIf { v -> v > 0 } }.maxOrNull() ?: 0L

            ProviderStats(
                provider = provider,
                inputTokens = sumInput,
                outputTokens = sumOutput,
                costUsd = sumCost,
                requestCount = sumReq,
                errorCount = sumErr,
                totalLatencyMs = sumLat,
                models = models,
                firstUsedAt = firstUsed,
                lastUsedAt = lastUsed
            )
        }.sortedByDescending { it.totalTokens }
    }
}

// ── Repository ────────────────────────────────────────────────────────────────

/**
 * Repository responsible for persisting and retrieving cumulative analytics data.
 *
 * All data is stored as a single JSON blob in DataStore under the key "analytics_v1".
 * Writes are atomic (`DataStore.edit { … }`) so concurrent emissions from the agent
 * pipeline are safely merged.
 */
class AnalyticsRepository(private val context: Context) {

    private object Keys {
        val ANALYTICS_JSON = stringPreferencesKey("analytics_v1")
    }

    companion object {
        /** How many days of history we keep in [AnalyticsStats.dailyUsage]. */
        const val MAX_DAILY_BUCKETS = 90

        private val utcDateFormat: SimpleDateFormat
            get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        /** Current UTC date key, e.g. `2026-04-19`. */
        fun todayKey(epochMillis: Long = System.currentTimeMillis()): String =
            utcDateFormat.format(Date(epochMillis))
    }

    /** Reactive stream of the full analytics state — the UI should collect this. */
    val statsFlow: Flow<AnalyticsStats> =
        context.analyticsDataStore.data.map { it.readStats() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a single completion API call.
     *
     * @param modelId model identifier (e.g. `claude-sonnet-4-6`)
     * @param provider resolved provider for the model; pass null to auto-detect
     * @param inputTokens prompt tokens consumed
     * @param outputTokens completion tokens produced
     * @param costUsd resolved USD cost for this call (may be 0 for free/unknown)
     * @param latencyMs wall-clock duration of the API call, or 0 if unknown
     * @param isError true if the call ultimately failed and should contribute to the error rate
     */
    suspend fun recordTokenUsage(
        modelId: String,
        provider: ModelProvider? = null,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double,
        latencyMs: Long = 0L,
        isError: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val resolvedProvider = provider ?: inferProvider(modelId)
        val dateKey = todayKey(now)

        context.analyticsDataStore.edit { prefs ->
            val current = prefs.readStats()
            val existing = current.tokensByModel[modelId] ?: ModelStats()
            val mergedModel = existing.copy(
                provider = resolvedProvider,
                inputTokens = existing.inputTokens + inputTokens,
                outputTokens = existing.outputTokens + outputTokens,
                costUsd = existing.costUsd + costUsd,
                requestCount = existing.requestCount + 1,
                errorCount = existing.errorCount + (if (isError) 1L else 0L),
                totalLatencyMs = existing.totalLatencyMs + latencyMs.coerceAtLeast(0L),
                firstUsedAt = if (existing.firstUsedAt == 0L) now else existing.firstUsedAt,
                lastUsedAt = now
            )

            // Daily bucket (keeps at most MAX_DAILY_BUCKETS most recent days).
            val existingDaily = current.dailyUsage[dateKey] ?: DailyUsage(dateKey = dateKey)
            val mergedDaily = existingDaily.copy(
                inputTokens = existingDaily.inputTokens + inputTokens,
                outputTokens = existingDaily.outputTokens + outputTokens,
                costUsd = existingDaily.costUsd + costUsd,
                requestCount = existingDaily.requestCount + 1
            )
            val updatedDaily = (current.dailyUsage + (dateKey to mergedDaily))
                .entries
                .sortedByDescending { it.key }
                .take(MAX_DAILY_BUCKETS)
                .associate { it.key to it.value }

            val updated = current.copy(
                totalInputTokens = current.totalInputTokens + inputTokens,
                totalOutputTokens = current.totalOutputTokens + outputTokens,
                totalCostUsd = current.totalCostUsd + costUsd,
                totalCostEgp = current.totalCostEgp + (costUsd * 50.0),
                totalRequests = current.totalRequests + 1,
                totalErrors = current.totalErrors + (if (isError) 1L else 0L),
                tokensByModel = current.tokensByModel + (modelId to mergedModel),
                dailyUsage = updatedDaily,
                firstRecordedAt = if (current.firstRecordedAt == 0L) now else current.firstRecordedAt,
                lastRecordedAt = now
            )
            prefs[Keys.ANALYTICS_JSON] = updated.toJson()
        }
    }suspend fun recordToolUsage(toolName: String, success: Boolean, durationMs: Long) {
        context.analyticsDataStore.edit { prefs ->
            val current = prefs.readStats()
            val stats = current.toolUsageCount[toolName] ?: ToolStats(toolName)
            val now = System.currentTimeMillis()

            val newStats = stats.copy(
                executionCount = stats.executionCount + 1,
                successCount = stats.successCount + if (success) 1L else 0L,
                totalDurationMs = stats.totalDurationMs + durationMs
            )

            val updated = current.copy(
                toolUsageCount = current.toolUsageCount + (toolName to newStats),
                firstRecordedAt = if (current.firstRecordedAt == 0L) now else current.firstRecordedAt,
                lastRecordedAt = now
            )
            prefs[Keys.ANALYTICS_JSON] = updated.toJson()
        }
    }

    suspend fun recordAgentRun(isSwarm: Boolean) {
        context.analyticsDataStore.edit { prefs ->
            val current = prefs.readStats()
            val now = System.currentTimeMillis()
            val updated = (if (isSwarm) {
                current.copy(totalSwarmRuns = current.totalSwarmRuns + 1)
            } else {
                current.copy(totalAgentRuns = current.totalAgentRuns + 1)
            }).copy(
                firstRecordedAt = if (current.firstRecordedAt == 0L) now else current.firstRecordedAt,
                lastRecordedAt = now
            )
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
        root.put("total_cost_egp", totalCostEgp)
        root.put("total_requests", totalRequests)
        root.put("total_errors", totalErrors)
        root.put("total_agent_runs", totalAgentRuns)
        root.put("total_swarm_runs", totalSwarmRuns)
        root.put("first_recorded_at", firstRecordedAt)
        root.put("last_recorded_at", lastRecordedAt)

        val byModel = JSONObject()
        tokensByModel.forEach { (modelId, stats) ->
            val modelObj = JSONObject()
            modelObj.put("input_tokens", stats.inputTokens)
            modelObj.put("output_tokens", stats.outputTokens)
            modelObj.put("cost_usd", stats.costUsd)
            modelObj.put("request_count", stats.requestCount)
            modelObj.put("error_count", stats.errorCount)
            modelObj.put("total_latency_ms", stats.totalLatencyMs)
            modelObj.put("first_used_at", stats.firstUsedAt)
            modelObj.put("last_used_at", stats.lastUsedAt)
            stats.provider?.let { modelObj.put("provider", it.name) }
            byModel.put(modelId, modelObj)
        }
        root.put("tokens_by_model", byModel)

        val toolUsage = JSONObject()
        toolUsageCount.forEach { (toolName, count) ->
            toolUsage.put(toolName, count)
        }
        root.put("tool_usage_count", toolUsage)

        val daily = JSONObject()
        dailyUsage.forEach { (dateKey, bucket) ->
            val dObj = JSONObject()
            dObj.put("input_tokens", bucket.inputTokens)
            dObj.put("output_tokens", bucket.outputTokens)
            dObj.put("cost_usd", bucket.costUsd)
            dObj.put("request_count", bucket.requestCount)
            daily.put(dateKey, dObj)
        }
        root.put("daily_usage", daily)

        return root.toString()
    }

    private fun String.toAnalyticsStats(): AnalyticsStats {
        val root = JSONObject(this)

        val byModel = mutableMapOf<String, ModelStats>()
        val byModelObj = root.optJSONObject("tokens_by_model") ?: JSONObject()
        byModelObj.keys().forEach { modelId ->
            val obj = byModelObj.getJSONObject(modelId)
            val providerName = obj.optString("provider", "")
            val provider = providerName
                .takeIf { it.isNotBlank() }
                ?.let { name -> runCatching { ModelProvider.valueOf(name) }.getOrNull() }
                ?: inferProvider(modelId)

            byModel[modelId] = ModelStats(
                provider = provider,
                inputTokens = obj.optLong("input_tokens"),
                outputTokens = obj.optLong("output_tokens"),
                costUsd = obj.optDouble("cost_usd"),
                requestCount = obj.optLong("request_count"),
                errorCount = obj.optLong("error_count"),
                totalLatencyMs = obj.optLong("total_latency_ms"),
                firstUsedAt = obj.optLong("first_used_at"),
                lastUsedAt = obj.optLong("last_used_at")
            )
        }
        val toolUsage = mutableMapOf<String, ToolStats>()
        val toolObj = root.optJSONObject("tool_usage_count") ?: JSONObject()
        toolObj.keys().forEach { toolName ->
            val tObj = toolObj.optJSONObject(toolName)
            if (tObj != null) {
                toolUsage[toolName] = ToolStats(
                    toolName = toolName,
                    executionCount = tObj.optLong("executionCount", 0L),
                    successCount = tObj.optLong("successCount", 0L),
                    totalDurationMs = tObj.optLong("totalDurationMs", 0L)
                )
            } else {
                // Backwards compat for old flat count format
                toolUsage[toolName] = ToolStats(
                    toolName = toolName,
                    executionCount = toolObj.optLong(toolName, 0L),
                    successCount = toolObj.optLong(toolName, 0L),
                    totalDurationMs = 0L
                )
            }
        }


        val daily = mutableMapOf<String, DailyUsage>()
        val dailyObj = root.optJSONObject("daily_usage") ?: JSONObject()
        dailyObj.keys().forEach { dateKey ->
            val dObj = dailyObj.getJSONObject(dateKey)
            daily[dateKey] = DailyUsage(
                dateKey = dateKey,
                inputTokens = dObj.optLong("input_tokens"),
                outputTokens = dObj.optLong("output_tokens"),
                costUsd = dObj.optDouble("cost_usd"),
                requestCount = dObj.optLong("request_count")
            )
        }

        return AnalyticsStats(
            totalInputTokens = root.optLong("total_input_tokens"),
            totalOutputTokens = root.optLong("total_output_tokens"),
            totalCostUsd = root.optDouble("total_cost_usd"),
            totalCostEgp = root.optDouble("total_cost_egp"),
            totalRequests = root.optLong("total_requests"),
            totalErrors = root.optLong("total_errors"),
            totalAgentRuns = root.optInt("total_agent_runs"),
            totalSwarmRuns = root.optInt("total_swarm_runs"),
            firstRecordedAt = root.optLong("first_recorded_at"),
            lastRecordedAt = root.optLong("last_recorded_at"),
            tokensByModel = byModel,
            toolUsageCount = toolUsage,
            dailyUsage = daily
        )
    }
}

// ── Provider inference ────────────────────────────────────────────────────────

/**
 * Resolve the [ModelProvider] for a model id.
 *
 * First consults the canonical [ModelRegistry]; if the model is unknown (e.g. a
 * dynamically-fetched GitHub Copilot/OpenRouter model), falls back to keyword
 * heuristics on the id string so the dashboard can still group sensibly.
 */
internal fun inferProvider(modelId: String): ModelProvider {
    ModelRegistry.findModelById(modelId)?.let { return it.provider }

    val id = modelId.lowercase(Locale.US)
    return when {
        "claude" in id || "anthropic" in id -> ModelProvider.ANTHROPIC
        "gpt" in id || "openai" in id || id.startsWith("o1") ||
            id.startsWith("o3") || id.startsWith("o4") -> ModelProvider.OPENAI
        "gemini" in id || "google" in id -> ModelProvider.GEMINI
        "grok" in id || "xai" in id -> ModelProvider.XAI
        "deepseek" in id -> ModelProvider.DEEPSEEK
        "mistral" in id || "mixtral" in id -> ModelProvider.MISTRAL
        "groq" in id -> ModelProvider.GROQ
        "cerebras" in id -> ModelProvider.CEREBRAS
        "cohere" in id || "command" in id -> ModelProvider.COHERE
        "fireworks" in id -> ModelProvider.FIREWORKS
        "together" in id -> ModelProvider.TOGETHER
        "perplexity" in id || "sonar" in id -> ModelProvider.PERPLEXITY
        "nvidia" in id || "nemotron" in id -> ModelProvider.NVIDIA
        "copilot" in id -> ModelProvider.GITHUB_COPILOT
        "openrouter" in id || "/" in id -> ModelProvider.OPEN_ROUTER
        "llama" in id -> ModelProvider.TOGETHER // community default
        else -> ModelProvider.LOCAL_EDGE
    }
}
