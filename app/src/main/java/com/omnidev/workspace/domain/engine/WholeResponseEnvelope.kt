package com.omnidev.workspace.domain.engine

/**
 * Removes Markdown fences only when they wrap the entire response.
 * Never extracts JSON from surrounding prose, quoted examples, or observations.
 */
internal object WholeResponseEnvelope {
    private val fence = Regex("""(?is)^\x60\x60\x60(?:json)?[ \t]*\r?\n(.*?)\r?\n?\x60\x60\x60[ \t]*$""")

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val match = fence.matchEntire(trimmed) ?: return trimmed
        return match.groupValues[1].trim()
    }
}
