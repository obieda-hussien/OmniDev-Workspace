package com.omnidev.workspace.data.rollback

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

/** DiffUtils provides synchronous rollback diff helpers; call from Dispatchers.IO. LCS is bounded to 8000 lines to constrain quadratic memory use. Files above 4 KB use a diff; smaller files use Deflate-compressed full content. Truncated SHA-256 hashes (16 hex characters) verify restoration. */
object DiffUtils {

    /** Threshold for storing a diff instead of full content. */
    const val FULL_CONTENT_THRESHOLD_BYTES = 4 * 1024

    /** LCS line limit to constrain memory use on smaller devices. */
    private const val MAX_LCS_LINES = 8_000

    /** Maximum file size accepted for processing (10 MB). */
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
    // Deflate compression is available on JVM/Android without extra libraries.
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

    /** Build a compact line diff: '= line' is unchanged context, '- line' is removed and '+ line' is added. Store changed hunks with two context lines instead of the complete unchanged content. */
    fun buildDiff(before: String, after: String): String {
        val a = before.split('\n')
        val b = after.split('\n')

        // For oversized files, fall back to full-content storage handled by the caller.
        if (a.size > MAX_LCS_LINES || b.size > MAX_LCS_LINES) {
            return buildSimpleDiff(a, b)
        }

        val ops = computeLcsOps(a, b)
        return formatHunks(ops, contextLines = 2)
    }

    /** Restore the original content from the diff and current text; return null if restoration fails. */
    fun applyReverseDiff(currentContent: String, diff: String): String? {
        if (diff.isBlank()) return currentContent
        return try {
            // Reverse the diff by parsing operations and undoing them.
            val ops = parseDiff(diff)
            val current = currentContent.split('\n').toMutableList()
            val original = mutableListOf<String>()

            // Walk operations and reconstruct the original content.
            // = takes a line from current content.
            // + skips a line added in the after state to restore the before state.
            // - restores a line present in the before state.
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
                        // Skip a line added in the after state and advance through current content.
                        if (ci < current.size && current[ci] == op.line) ci++
                    }
                    DiffOp.Kind.REMOVED -> {
                        // Restore a line from the before state.
                        original += op.line
                    }
                }
            }

            // Append any current lines not covered by the diff.
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

    /** Compute LCS operations with an O(m*n) dynamic-programming table within the configured line limit. */
    private fun computeLcsOps(a: List<String>, b: List<String>): List<DiffOp> {
        val m = a.size
        val n = b.size
        // Use a flat IntArray to reduce object-array overhead.
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

    /** Use a basic line-by-line diff when input exceeds the LCS limit. */
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

    /** Convert operations into hunks with bounded context. */
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
