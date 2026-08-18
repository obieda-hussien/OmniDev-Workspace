package com.omnidev.workspace.data.rollback

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * DiffUtils — Context note Diff Context note Context note Rollback (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Mobile-first:
 * - LCS (Longest Common Subsequence) Context note Context note 8000 Context note Context note O(n²) memory
 * - Context note > 4 KB → unified diff (Context note ~70% Context note Context note)
 * - Context note ≤ 4 KB → Context note Context note Context note Context note Deflate
 * - SHA-256 truncated (16 hex) Context note Context note Context note Context note rollback
 *
 * Context note Context note synchronous — Context note Context note Context note Dispatchers.IO.
 */
object DiffUtils {

    /** Context note Context note Context note Context note Context note diff Context note Context note Context note Context note. */
    const val FULL_CONTENT_THRESHOLD_BYTES = 4 * 1024

    /** Context note Context note Context note Context note Context note LCS (Context note Context note OOM Context note Context note Context note). */
    private const val MAX_LCS_LINES = 8_000

    /** Context note Context note Context note Context note Context note (10 MB). */
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
    // Compression (Deflate — Context note Context note Context note JVM/Android Context note Context note Context note)
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
     * Context note unified diff Context note Context note Context note.
     * format: Context note Context note:
     *   "= line"   = Context note Context note (context — Context note lines Context note Context note)
     *   "- line"   = Context note Context note
     *   "+ line"   = Context note Context note
     *
     * Mobile-first: Context note Context note context Context note Context note hunks Context note + 2 Context note context.
     * Context note Context note Context note Context note Context note Context note Context note.
     */
    fun buildDiff(before: String, after: String): String {
        val a = before.split('\n')
        val b = after.split('\n')

        // Context note: Context note Context note Context note Context note Context note Context note Context note (Context note Context note Context note)
        if (a.size > MAX_LCS_LINES || b.size > MAX_LCS_LINES) {
            return buildSimpleDiff(a, b)
        }

        val ops = computeLcsOps(a, b)
        return formatHunks(ops, contextLines = 2)
    }

    /**
     * Context note Context note Context note Context note unified diff + Context note Context note.
     * @return Context note Context note (Context note Context note edit) Context note Context note null Context note Context note.
     */
    fun applyReverseDiff(currentContent: String, diff: String): String? {
        if (diff.isBlank()) return currentContent
        return try {
            // Context note Context note: Context note diff Context note Context note Context note Context note Context note
            val ops = parseDiff(diff)
            val current = currentContent.split('\n').toMutableList()
            val original = mutableListOf<String>()

            // Context note Context note: Context note Context note ops Context note Context note
            // - = → Context note Context note current
            // + → Context note (Context note Context note Context note afterContext note Context note Context note before)
            // - → Context note Context note (Context note Context note Context note beforeContext note Context note)
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
                        // Context note Context note Context note after — Context note Context note Context note current
                        if (ci < current.size && current[ci] == op.line) ci++
                    }
                    DiffOp.Kind.REMOVED -> {
                        // Context note Context note Context note before — Context note
                        original += op.line
                    }
                }
            }

            // Context note Context note Context note Context note current Context note Context note diff (Context note Context note)Context note Context note Context note Context note
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

    /** Context note LCS operations Context note DP table O(m*n) memory — Context note Context note 8K×8K. */
    private fun computeLcsOps(a: List<String>, b: List<String>): List<DiffOp> {
        val m = a.size
        val n = b.size
        // Context note IntArray Context note Context note overhead Context note Object[]
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

    /** Fallback Context note Context note Context note Context note Context note LCS — diff Context note Context note-Context note. */
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

    /** Context note ops Context note hunks Context note context Context note (Context note Context note). */
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
