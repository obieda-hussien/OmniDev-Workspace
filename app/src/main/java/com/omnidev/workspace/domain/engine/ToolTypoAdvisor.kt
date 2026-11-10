package com.omnidev.workspace.domain.engine

/**
 * Read-only typo hints from already-exposed tool contracts.
 * Hints never authorize or execute a call or rewrite its arguments.
 */
internal object ToolTypoAdvisor {
    fun suggest(input: String, exposed: Collection<String>): String? {
        if (input.isBlank() || input.length > 160 || exposed.size > 1000) return null
        val limit = if (input.length >= 8) 2 else 1
        val candidates = exposed.asSequence()
            .filter { it.length <= 160 }
            .map { it to editDistance(input.lowercase(), it.lowercase()) }
            .filter { it.second in 1..limit }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(2).toList()
        val first = candidates.firstOrNull() ?: return null
        if (candidates.getOrNull(1)?.second == first.second) return null
        return first.first
    }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
