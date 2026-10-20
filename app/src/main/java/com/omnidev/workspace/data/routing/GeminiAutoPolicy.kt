package com.omnidev.workspace.data.routing

/**
 * Gemini /models advertises generateContent, not whether OpenAI-compatible
 * function calling works. Use a conservative text-model allowlist for Auto.
 * New Gemini model families must be explicitly validated before enabled.
 */
object GeminiAutoPolicy {
    private val tools = setOf(
        "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro",
        "gemini-3-flash-preview", "gemini-3.1-flash-lite",
        "gemini-3.1-pro-preview"
    )
    private val freeTier = setOf(
        "gemini-2.5-flash", "gemini-2.5-flash-lite",
        "gemini-3.1-flash-lite"
    )
    fun supportsTools(id: String): Boolean = id.substringAfter("::")
        .removePrefix("models/").lowercase() in tools

    /** A listed free tier is NOT evidence that the user's project is unbilled. */
    fun hasPublishedFreeTier(id: String): Boolean =
        id.substringAfter("::").removePrefix("models/").lowercase() in freeTier
}
