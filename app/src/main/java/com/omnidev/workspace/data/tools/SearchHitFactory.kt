package com.omnidev.workspace.data.tools

internal object SearchHitFactory {
    fun create(
        title: String,
        url: String,
        snippet: String,
        source: String,
        publishedLabel: String? = null,
        research: Boolean = false
    ): WebSearchHit? {
        val cleanTitle = title.replace(Regex("\\s+"), " ").trim().take(180)
        val cleanUrl = url.trim()
        val cleanSnippet = snippet.replace(Regex("\\s+"), " ").trim().take(1200)
        if (cleanTitle.isBlank() || !KeylessSearchHttp.isSafePublicUrl(cleanUrl)) return null
        val date = SearchSemantics.parseDate(
            listOfNotNull(publishedLabel, cleanTitle, cleanSnippet, cleanUrl).joinToString(" ")
        )
        return WebSearchHit(
            title = cleanTitle,
            url = cleanUrl,
            snippet = cleanSnippet,
            source = source,
            publishedAtEpochMs = date,
            publishedLabel = publishedLabel?.take(220),
            isResearch = research
        )
    }
}
