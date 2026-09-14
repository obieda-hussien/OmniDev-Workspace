package com.omnidev.workspace.data.repository

/** Older releases serialized the Kotlin data class as text, not a JSON object. */
internal fun parseLegacyToolStats(name: String, raw: Any?): ToolStats {
    if (raw is Number) return ToolStats(name, raw.toLong().coerceAtLeast(0))
    val text = raw?.toString().orEmpty()
    text.toLongOrNull()?.let { return ToolStats(name, it.coerceAtLeast(0)) }
    val match = Regex("^ToolStats\\(toolName=.*?, executionCount=(\\d+), successCount=(\\d+), totalDurationMs=(\\d+)\\)$").matchEntire(text)
        ?: return ToolStats(name)
    val count = match.groupValues[1].toLongOrNull() ?: 0
    return ToolStats(name, count,
        (match.groupValues[2].toLongOrNull() ?: 0).coerceAtMost(count),
        match.groupValues[3].toLongOrNull() ?: 0)
}
