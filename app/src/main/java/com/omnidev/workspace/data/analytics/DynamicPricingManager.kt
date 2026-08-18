package com.omnidev.workspace.data.analytics

import com.omnidev.workspace.data.mcp.McpHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class DynamicPricingManager(
    private val httpClient: McpHttpClient = McpHttpClient()
) {
    private val cachedRates = mutableMapOf<String, ModelPrice>()
    private val okHttpClient = OkHttpClient()

    data class ModelPrice(
        val promptPricePerToken: Double,
        val completionPricePerToken: Double
    )

    suspend fun syncLatestModelPrices() = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://openrouter.ai/api/v1/models").build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext
                val bodyStr = response.body?.string() ?: return@withContext
                val json = JSONObject(bodyStr)
                val dataArray = json.optJSONArray("data") ?: return@withContext

                for (i in 0 until dataArray.length()) {
                    val modelObj = dataArray.getJSONObject(i)
                    val id = modelObj.getString("id").lowercase()
                    val pricing = modelObj.optJSONObject("pricing") ?: continue

                    val promptPrice = pricing.optString("prompt", "0.0").toDoubleOrNull() ?: 0.0
                    val completionPrice = pricing.optString("completion", "0.0").toDoubleOrNull() ?: 0.0

                    cachedRates[id] = ModelPrice(promptPrice, completionPrice)
                }
            }
        } catch (_: Exception) {}
    }

    fun calculateCost(modelId: String?, promptTokens: Int, completionTokens: Int, directApiCostUSD: Double? = null): Double {
        if (directApiCostUSD != null && directApiCostUSD > 0.0) {
            return directApiCostUSD
        }
        if (modelId == null) return 0.0
        val cleanModel = modelId.lowercase()

        val matchedPrice = cachedRates.entries.firstOrNull { cleanModel.contains(it.key) }?.value
        return if (matchedPrice != null) {
            (promptTokens * matchedPrice.promptPricePerToken) + (completionTokens * matchedPrice.completionPricePerToken)
        } else {
            0.0
        }
    }
}
