package com.omnidev.workspace.data.repository

import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object DynamicPricingManager {
    // Cache map: modelId -> Pair(input_price_per_1M, output_price_per_1M)
    private val livePricingCache = mutableMapOf<String, Pair<Double, Double>>()
    private var lastFetchTime = 0L
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

    suspend fun fetchLivePricing() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS && livePricingCache.isNotEmpty()) {
            return@withContext
        }

        try {
            val url = URL("https://openrouter.ai/api/v1/models")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val model = data.getJSONObject(i)
                        val id = model.optString("id")
                        val pricing = model.optJSONObject("pricing")
                        if (id.isNotBlank() && pricing != null) {
                            val inputPrice = pricing.optDouble("prompt", 0.0) * 1_000_000
                            val outputPrice = pricing.optDouble("completion", 0.0) * 1_000_000
                            livePricingCache[id] = Pair(inputPrice, outputPrice)
                        }
                    }
                    lastFetchTime = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            // Silently fail and fallback to static models or volumetric
        }
    }

    suspend fun calculateCost(modelId: String, promptTokens: Int, completionTokens: Int): Double {
        // Force refresh if cache is empty
        if (livePricingCache.isEmpty()) {
            fetchLivePricing()
        }

        // Try dynamically fetched first
        livePricingCache[modelId]?.let { (inPrice, outPrice) ->
            return (inPrice * promptTokens / 1_000_000.0) + (outPrice * completionTokens / 1_000_000.0)
        }

        // Try static ModelRegistry as fallback
        val staticModel = ModelRegistry.findModelById(modelId)
        if (staticModel != null && staticModel.costPer1MInputTokens != null && staticModel.costPer1MOutputTokens != null) {
            return (staticModel.costPer1MInputTokens * promptTokens / 1_000_000.0) +
                   (staticModel.costPer1MOutputTokens * completionTokens / 1_000_000.0)
        }

        // Local / Offline / Unrecognized models cost 0.0
        return 0.0
    }
}
