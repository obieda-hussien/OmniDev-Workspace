package com.omnidev.workspace.data.rollback

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * DiffUtils — System awareness note Diff System awareness note System awareness note Rollback (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Mobile-first:
 * - LCS (Longest Common Subsequence) System awareness note System awareness note 8000 System awareness note System awareness note O(n²) memory
 * - System awareness note > 4 KB → unified diff (System awareness note ~70% System awareness note System awareness note)
 * - System awareness note ≤ 4 KB → System awareness note System awareness note System awareness note System awareness note Deflate
 * - SHA-256 truncated (16 hex) System awareness note System awareness note System awareness note System awareness note rollback
 *
 * System awareness note System awareness note synchronous — System awareness note System awareness note System awareness note Dispatchers.IO.
 */
object DiffUtils {

    /** System awareness note System awareness note System awareness note System awareness note System awareness note diff System awareness note System awareness note System awareness note System awareness note. */
    const val FULL_CONTENT_THRESHOLD_BYTES = 4 * 1024

    /** System awareness note System awareness note System awareness note System awareness note System awareness note LCS (System awareness note System awareness note OOM System awareness note System awareness note System awareness note). */
    private const val MAX_LCS_LINES = 8_000

    /** System awareness note System awareness note System awareness note System awareness note System awareness note (10 MB). */
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
    // Compression (Deflate — System awareness note System awareness note System awareness note JVM/Android System awareness note System awareness note System awareness note)
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
     * System awareness note unified diff System awareness note System awareness note System awareness note.
     * format: System awareness note System awareness note:
     *   "= line"   = System awareness note System awareness note (context — System awareness note lines System awareness note System awareness note)
     *   "- line"   = System awareness note System awareness note
     *   "+ line"   = System awareness note System awareness note
     *
     * Mobile-first: System awareness note System awareness note context System awareness note System awareness note hunks System awareness note + 2 System awareness note context.
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
     */
    fun buildDiff(before: String, after: String): String {
        val a = before.split('\n')
        val b = after.split('\n')

        // System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note)
        if (a.size > MAX_LCS_LINES || b.size > MAX_LCS_LINES) {
            return buildSimpleDiff(a, b)
        }

        val ops = computeLcsOps(a, b)
        return formatHunks(ops, contextLines = 2)
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note unified diff + System awareness note System awareness note.
     * @return System awareness note System awareness note (System awareness note System awareness note edit) System awareness note System awareness note null System awareness note System awareness note.
     */
    fun applyReverseDiff(currentContent: String, diff: String): String? {
        if (diff.isBlank()) return currentContent
        return try {
            // System awareness note System awareness note: System awareness note diff System awareness note System awareness note System awareness note System awareness note System awareness note
            val ops = parseDiff(diff)
            val current = currentContent.split('\n').toMutableList()
            val original = mutableListOf<String>()

            // System awareness note System awareness note: System awareness note System awareness note ops System awareness note System awareness note
            // - = → System awareness note System awareness note current
            // + → System awareness note (System awareness note System awareness note System awareness note afterSystem awareness note System awareness note System awareness note before)
            // - → System awareness note System awareness note (System awareness note System awareness note System awareness note beforeSystem awareness note System awareness note)
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
                        // System awareness note System awareness note System awareness note after — System awareness note System awareness note System awareness note current
                        if (ci < current.size && current[ci] == op.line) ci++
                    }
                    DiffOp.Kind.REMOVED -> {
                        // System awareness note System awareness note System awareness note before — System awareness note
                        original += op.line
                    }
                }
            }

            // System awareness note System awareness note System awareness note System awareness note current System awareness note System awareness note diff (System awareness note System awareness note)System awareness note System awareness note System awareness note System awareness note
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

    /** System awareness note LCS operations System awareness note DP table O(m*n) memory — System awareness note System awareness note 8K×8K. */
    private fun computeLcsOps(a: List<String>, b: List<String>): List<DiffOp> {
        val m = a.size
        val n = b.size
        // System awareness note IntArray System awareness note System awareness note overhead System awareness note Object[]
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

    /** Fallback System awareness note System awareness note System awareness note System awareness note System awareness note LCS — diff System awareness note System awareness note-System awareness note. */
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

    /** System awareness note ops System awareness note hunks System awareness note context System awareness note (System awareness note System awareness note). */
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
