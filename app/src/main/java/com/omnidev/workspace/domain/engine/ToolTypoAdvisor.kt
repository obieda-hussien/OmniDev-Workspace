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
            .filter { (name, distance) -> name != input && distance in 0..limit }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(2).toList()
        val first = candidates.firstOrNull() ?: return null
        if (candidates.getOrNull(1)?.second == first.second) return null
        return first.first
    }

    private fun editDistance(a: String, b: String): Int {
        // An adjacent transposition is a common one-keystroke mistake: reop -> repo.
        if (a.length == b.length) {
            for (i in 0 until a.length - 1) {
                if (a[i] == b[i + 1] && a[i + 1] == b[i] &&
                    a.indices.all { j -> j == i || j == i + 1 || a[j] == b[j] }) return 1
            }
        }
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
