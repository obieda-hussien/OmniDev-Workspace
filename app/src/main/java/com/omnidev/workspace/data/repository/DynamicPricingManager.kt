package com.omnidev.workspace.data.repository

class DynamicPricingManager {
    fun calculateCost(modelId: String?, promptTokens: Int, completionTokens: Int): Double {
        if (modelId == null) return 0.0
        val modelLower = modelId.lowercase()
        return when {
            modelLower.contains("gpt-4") -> (promptTokens * 0.03 + completionTokens * 0.06) / 1000.0
            modelLower.contains("gpt-3.5") -> (promptTokens * 0.0015 + completionTokens * 0.002) / 1000.0
            modelLower.contains("claude-3-opus") -> (promptTokens * 0.015 + completionTokens * 0.075) / 1000.0
            modelLower.contains("claude-3-sonnet") -> (promptTokens * 0.003 + completionTokens * 0.015) / 1000.0
            modelLower.contains("claude-3-haiku") -> (promptTokens * 0.00025 + completionTokens * 0.00125) / 1000.0
            else -> 0.0
        }
    }
}
