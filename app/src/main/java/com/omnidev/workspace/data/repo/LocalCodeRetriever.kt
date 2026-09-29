package com.omnidev.workspace.data.repo

import java.io.File

/** Small, fully local evidence retriever. File reads are bounded to indexed candidates. */
object LocalCodeRetriever {
    data class Candidate(val path: String, val symbol: String = "")
    data class Hit(val path: String, val startLine: Int, val endLine: Int, val excerpt: String, val score: Int)

    private val words = Regex("[\\p{L}\\p{N}_]+")
    private val camelBoundary = Regex("([a-z])([A-Z])")
    private val stop = setOf("the", "and", "where", "what", "how", "does", "which", "find", "code", "file", "that", "for", "from", "with", "في", "من", "فين", "ازاي", "الكود", "اللي", "على", "عن")
    private val concepts = mapOf(
        "auth" to setOf("authenticate", "authentication", "login", "token", "permission", "صلاحية", "دخول"),
        "crash" to setOf("exception", "failure", "error", "خطأ", "كراش"),
        "retry" to setOf("backoff", "timeout", "reconnect", "إعادة", "محاولة"),
        "memory" to setOf("cache", "recall", "history", "ذاكرة", "سجل"),
        "save" to setOf("persist", "store", "write", "حفظ", "تخزين"),
        "search" to setOf("query", "lookup", "find", "بحث", "ابحث")
    )

    private fun tokens(text: String): Set<String> = words.findAll(text.replace(camelBoundary, "$1 $2").lowercase())
        .map { it.value }.filter { it.length > 2 && it !in stop }.toSet()

    private fun terms(question: String): Set<String> {
        val requested = tokens(question)
        val expanded = requested.toMutableSet()
        concepts.forEach { (head, aliases) ->
            if (head in requested || aliases.any { it in requested }) expanded += aliases + head
        }
        return expanded
    }

    fun retrieve(question: String, root: File, candidates: List<Candidate>, limit: Int = 6): List<Hit> {
        val query = terms(question)
        if (query.isEmpty() || !root.isDirectory) return emptyList()
        val canonicalRoot = root.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
        val ranked = candidates.asSequence().distinctBy { it.path }
            .map { candidate ->
                val name = tokens(File(candidate.path).nameWithoutExtension)
                val path = tokens(candidate.path.removePrefix(root.path))
                val symbol = tokens(candidate.symbol)
                candidate to (query.count { it in name } * 7 + query.count { it in symbol } * 6 + query.count { it in path } * 2)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(48).toList()

        return ranked.mapNotNull { (candidate, baseScore) ->
            val file = File(candidate.path)
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@mapNotNull null
            if (!canonical.startsWith(canonicalRoot) || !file.isFile || file.length() > 512 * 1024L) return@mapNotNull null
            val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            var bestLine = -1
            var bestScore = 0
            lines.forEachIndexed { index, line ->
                val matched = query.count { it in tokens(line.take(500)) }
                val score = matched * 3 + if (matched > 1) 2 else 0
                if (score > bestScore) { bestScore = score; bestLine = index }
            }
            if (bestLine < 0) return@mapNotNull null
            val start = (bestLine - 2).coerceAtLeast(0)
            val end = (bestLine + 4).coerceAtMost(lines.lastIndex)
            Hit(candidate.path, start + 1, end + 1,
                (start..end).joinToString("\n") { "${it + 1}: ${lines[it].take(300)}" },
                baseScore + bestScore)
        }.sortedByDescending { it.score }.take(limit.coerceIn(1, 12))
    }
}
