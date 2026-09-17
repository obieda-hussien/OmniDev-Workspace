package com.omnidev.workspace.data.tools

internal data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
    val publishedAtEpochMs: Long? = null,
    val publishedLabel: String? = null,
    val isResearch: Boolean = false
)

internal data class WebSearchProviderBatch(
    val provider: String,
    val hits: List<WebSearchHit>
)

internal enum class WebSearchMode { QUICK, DEEP }
