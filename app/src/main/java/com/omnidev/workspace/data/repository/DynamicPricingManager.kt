package com.omnidev.workspace.data.repository

/**
 * Small, local price table used by analytics. Rates are USD per one million
 * tokens and intentionally live here so recording a request never needs a
 * network lookup. Unknown and explicitly free models remain zero-cost instead
 * of being presented as a fabricated price.
 */
class DynamicPricingManager {
    fun calculateCost(modelId: String?, promptTokens: Int, completionTokens: Int): Double {
        if (modelId.isNullOrBlank()) return 0.0
        val input = promptTokens.coerceAtLeast(0).toDouble()
        val output = completionTokens.coerceAtLeast(0).toDouble()
        if (input == 0.0 && output == 0.0) return 0.0

        val id = modelId.lowercase()
        val (inputRate, outputRate) = when {
            id.contains(":free") || id.contains("free-model") -> 0.0 to 0.0
            id.contains("gemini-2.5-flash-lite") -> 0.10 to 0.40
            id.contains("gemini-2.5-pro") -> 1.25 to 10.00
            id.contains("gemini-2.5-flash") -> 0.30 to 2.50
            id.contains("gemini-2.0-flash") -> 0.10 to 0.40
            id.contains("gemini-1.5-pro") -> 1.25 to 5.00
            id.contains("gemini-1.5-flash") -> 0.075 to 0.30
            id.contains("gemini") -> 0.30 to 2.50
            id.contains("gpt-4o-mini") -> 0.15 to 0.60
            id.contains("gpt-4o") -> 2.50 to 10.00
            id.contains("gpt-4") -> 30.00 to 60.00
            id.contains("gpt-3.5") -> 1.50 to 2.00
            id.contains("claude-3-opus") -> 15.00 to 75.00
            id.contains("claude-3-sonnet") -> 3.00 to 15.00
            id.contains("claude-3-haiku") -> 0.25 to 1.25
            else -> 0.0 to 0.0
        }
        return (input * inputRate + output * outputRate) / 1_000_000.0
    }
}
