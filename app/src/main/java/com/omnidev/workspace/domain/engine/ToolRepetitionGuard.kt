package com.omnidev.workspace.domain.engine

/** Run-local quota. Structured keys avoid collisions in argument delimiters. */
internal class ToolRepetitionGuard(private val limit: Int) {
    private val counts = mutableMapOf<Pair<String, Map<String, String>>, Int>()

    fun allow(name: String, arguments: Map<String, String>): Boolean {
        val key = name to arguments.toMap()
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        return count <= limit
    }
}
