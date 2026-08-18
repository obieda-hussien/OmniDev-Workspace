package com.omnidev.workspace.data.rollback

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * DiffUtils — [Localized] Diff [Localized] [Localized] Rollback (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Mobile-first:
 * - LCS (Longest Common Subsequence) [Localized] [Localized] 8000 [Localized] [Localized] O(n²) memory
 * - [Localized] > 4 KB → unified diff ([Localized] ~70% [Localized] [Localized])
 * - [Localized] ≤ 4 KB → [Localized] [Localized] [Localized] [Localized] Deflate
 * - SHA-256 truncated (16 hex) [Localized] [Localized] [Localized] [Localized] rollback
 *
 * [Localized] [Localized] synchronous — [Localized] [Localized] [Localized] Dispatchers.IO.
 */
object DiffUtils {

    /** [Localized] [Localized] [Localized] [Localized] [Localized] diff [Localized] [Localized] [Localized] [Localized]. */
    const val FULL_CONTENT_THRESHOLD_BYTES = 4 * 1024

    /** [Localized] [Localized] [Localized] [Localized] [Localized] LCS ([Localized] [Localized] OOM [Localized] [Localized] [Localized]). */
    private const val MAX_LCS_LINES = 8_000

    /** [Localized] [Localized] [Localized] [Localized] [Localized] (10 MB). */
    const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024

    // ──────────────────────────────────────────────────────────────────
    // SHA-256 (16 hex prefix)
    // ──────────────────────────────────────────────────────────────────

    fun sha256(content: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(content)
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    fun sha256(text: String): String = sha256(text.toByteArray())

    // ──────────────────────────────────────────────────────────────────
    // Compression (Deflate — [Localized] [Localized] [Localized] JVM/Android [Localized] [Localized] [Localized])
    // ──────────────────────────────────────────────────────────────────

    fun compress(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ByteArrayOutputStream(data.size / 2)
        DeflaterOutputStream(out, Deflater(Deflater.BEST_SPEED)).use { it.write(data) }
        return out.toByteArray()
    }

    fun decompress(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ByteArrayOutputStream(data.size * 2)
        InflaterOutputStream(out, Inflater()).use { it.write(data) }
        return out.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────
    // Unified Diff
    // ──────────────────────────────────────────────────────────────────

    /**
     * [Localized] unified diff [Localized] [Localized] [Localized].
     * format: [Localized] [Localized]:
     *   "= line"   = [Localized] [Localized] (context — [Localized] lines [Localized] [Localized])
     *   "- line"   = [Localized] [Localized]
     *   "+ line"   = [Localized] [Localized]
     *
     * Mobile-first: [Localized] [Localized] context [Localized] [Localized] hunks [Localized] + 2 [Localized] context.
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun buildDiff(before: String, after: String): String {
        val a = before.split('\n')
        val b = after.split('\n')

        // [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized])
        if (a.size > MAX_LCS_LINES || b.size > MAX_LCS_LINES) {
            return buildSimpleDiff(a, b)
        }

        val ops = computeLcsOps(a, b)
        return formatHunks(ops, contextLines = 2)
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] unified diff + [Localized] [Localized].
     * @return [Localized] [Localized] ([Localized] [Localized] edit) [Localized] [Localized] null [Localized] [Localized].
     */
    fun applyReverseDiff(currentContent: String, diff: String): String? {
        if (diff.isBlank()) return currentContent
        return try {
            // [Localized] [Localized]: [Localized] diff [Localized] [Localized] [Localized] [Localized] [Localized]
            val ops = parseDiff(diff)
            val current = currentContent.split('\n').toMutableList()
            val original = mutableListOf<String>()

            // [Localized] [Localized]: [Localized] [Localized] ops [Localized] [Localized]
            // - = → [Localized] [Localized] current
            // + → [Localized] ([Localized] [Localized] [Localized] after[Localized] [Localized] [Localized] before)
            // - → [Localized] [Localized] ([Localized] [Localized] [Localized] before[Localized] [Localized])
            var ci = 0
            for (op in ops) {
                when (op.kind) {
                    DiffOp.Kind.CONTEXT -> {
                        if (ci < current.size) {
                            original += current[ci]
                            ci++
                        } else {
                            original += op.line
                        }
                    }
                    DiffOp.Kind.ADDED -> {
                        // [Localized] [Localized] [Localized] after — [Localized] [Localized] [Localized] current
                        if (ci < current.size && current[ci] == op.line) ci++
                    }
                    DiffOp.Kind.REMOVED -> {
                        // [Localized] [Localized] [Localized] before — [Localized]
                        original += op.line
                    }
                }
            }

            // [Localized] [Localized] [Localized] [Localized] current [Localized] [Localized] diff ([Localized] [Localized])[Localized] [Localized] [Localized] [Localized]
            while (ci < current.size) {
                original += current[ci]
                ci++
            }

            original.joinToString("\n")
        } catch (t: Throwable) {
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    private data class DiffOp(val kind: Kind, val line: String) {
        enum class Kind { CONTEXT, ADDED, REMOVED }
    }

    /** [Localized] LCS operations [Localized] DP table O(m*n) memory — [Localized] [Localized] 8K×8K. */
    private fun computeLcsOps(a: List<String>, b: List<String>): List<DiffOp> {
        val m = a.size
        val n = b.size
        // [Localized] IntArray [Localized] [Localized] overhead [Localized] Object[]
        val dp = IntArray((m + 1) * (n + 1))
        val w = n + 1
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i * w + j] = if (a[i] == b[j]) {
                    dp[(i + 1) * w + (j + 1)] + 1
                } else {
                    maxOf(dp[(i + 1) * w + j], dp[i * w + (j + 1)])
                }
            }
        }

        val ops = ArrayList<DiffOp>(m + n)
        var i = 0; var j = 0
        while (i < m && j < n) {
            when {
                a[i] == b[j] -> {
                    ops += DiffOp(DiffOp.Kind.CONTEXT, a[i])
                    i++; j++
                }
                dp[(i + 1) * w + j] >= dp[i * w + (j + 1)] -> {
                    ops += DiffOp(DiffOp.Kind.REMOVED, a[i])
                    i++
                }
                else -> {
                    ops += DiffOp(DiffOp.Kind.ADDED, b[j])
                    j++
                }
            }
        }
        while (i < m) { ops += DiffOp(DiffOp.Kind.REMOVED, a[i]); i++ }
        while (j < n) { ops += DiffOp(DiffOp.Kind.ADDED, b[j]); j++ }
        return ops
    }

    /** Fallback [Localized] [Localized] [Localized] [Localized] [Localized] LCS — diff [Localized] [Localized]-[Localized]. */
    private fun buildSimpleDiff(a: List<String>, b: List<String>): String {
        val sb = StringBuilder()
        val limit = minOf(a.size, b.size)
        for (k in 0 until limit) {
            if (a[k] == b[k]) sb.append("= ").append(a[k]).append('\n')
            else {
                sb.append("- ").append(a[k]).append('\n')
                sb.append("+ ").append(b[k]).append('\n')
            }
        }
        for (k in limit until a.size) sb.append("- ").append(a[k]).append('\n')
        for (k in limit until b.size) sb.append("+ ").append(b[k]).append('\n')
        return sb.toString()
    }

    /** [Localized] ops [Localized] hunks [Localized] context [Localized] ([Localized] [Localized]). */
    private fun formatHunks(ops: List<DiffOp>, contextLines: Int): String {
        val sb = StringBuilder()
        for (op in ops) {
            when (op.kind) {
                DiffOp.Kind.CONTEXT -> sb.append("= ")
                DiffOp.Kind.ADDED -> sb.append("+ ")
                DiffOp.Kind.REMOVED -> sb.append("- ")
            }
            sb.append(op.line).append('\n')
        }
        return sb.toString()
    }

    private fun parseDiff(diff: String): List<DiffOp> {
        val out = ArrayList<DiffOp>()
        for (line in diff.split('\n')) {
            if (line.isEmpty()) continue
            val kind = when {
                line.startsWith("= ") -> DiffOp.Kind.CONTEXT
                line.startsWith("+ ") -> DiffOp.Kind.ADDED
                line.startsWith("- ") -> DiffOp.Kind.REMOVED
                else -> continue
            }
            out += DiffOp(kind, line.substring(2))
        }
        return out
    }
}
