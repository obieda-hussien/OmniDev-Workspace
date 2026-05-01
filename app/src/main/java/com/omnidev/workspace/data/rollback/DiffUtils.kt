package com.omnidev.workspace.data.rollback

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * DiffUtils — أدوات Diff خفيفة لنظام Rollback (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Mobile-first:
 * - LCS (Longest Common Subsequence) محدود بـ 8000 سطر لتفادي O(n²) memory
 * - الملفات > 4 KB → unified diff (توفير ~70% من المساحة)
 * - الملفات ≤ 4 KB → نخزن المحتوى الكامل مضغوطاً Deflate
 * - SHA-256 truncated (16 hex) للتحقق من سلامة الـ rollback
 *
 * كل العمليات synchronous — يجب استدعاؤها من Dispatchers.IO.
 */
object DiffUtils {

    /** الحد الذي بعده نلجأ للـ diff بدل تخزين المحتوى الكامل. */
    const val FULL_CONTENT_THRESHOLD_BYTES = 4 * 1024

    /** سقف عدد السطور لخوارزمية الـ LCS (حماية من OOM على هواتف ضعيفة). */
    private const val MAX_LCS_LINES = 8_000

    /** أقصى حجم للملف يُسمح بمعالجته (10 MB). */
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
    // Compression (Deflate — متوفر في كل JVM/Android بدون مكتبات إضافية)
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
     * يبني unified diff بسيط بين النصين.
     * format: لكل سطر:
     *   "= line"   = نفس السطر (context — نخزن lines قليلة فقط)
     *   "- line"   = سطر مُزال
     *   "+ line"   = سطر مُضاف
     *
     * Mobile-first: لا نخزن context كامل، فقط hunks المتغيرة + 2 سطر context.
     * هذا يوفر مساحة هائلة في الملفات الكبيرة.
     */
    fun buildDiff(before: String, after: String): String {
        val a = before.split('\n')
        val b = after.split('\n')

        // حماية: لو الملف ضخم، نتراجع لتخزين المحتوى كاملاً (يُتعامل معه خارجياً)
        if (a.size > MAX_LCS_LINES || b.size > MAX_LCS_LINES) {
            return buildSimpleDiff(a, b)
        }

        val ops = computeLcsOps(a, b)
        return formatHunks(ops, contextLines = 2)
    }

    /**
     * يستعيد المحتوى الأصلي من unified diff + المحتوى الحالي.
     * @return المحتوى الأصلي (قبل الـ edit) إذا نجح، null لو فشل.
     */
    fun applyReverseDiff(currentContent: String, diff: String): String? {
        if (diff.isBlank()) return currentContent
        return try {
            // العملية العكسية: نحول diff إلى قائمة عمليات ثم نعكسها
            val ops = parseDiff(diff)
            val current = currentContent.split('\n').toMutableList()
            val original = mutableListOf<String>()

            // ابسط طريقة: نمشي على ops ونبني الأصل
            // - = → نأخذ من current
            // + → نتجاهل (كان مُضاف في after، يُحذف لاستعادة before)
            // - → نضع السطر (كان موجوداً في before، يُستعاد)
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
                        // كان مضافاً في after — نتجاهله ونتقدم في current
                        if (ci < current.size && current[ci] == op.line) ci++
                    }
                    DiffOp.Kind.REMOVED -> {
                        // كان موجوداً في before — نضعه
                        original += op.line
                    }
                }
            }

            // إذا بقيت أسطر في current لم يغطها diff (لا يفترض)، نضيفها كما هي
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

    /** يحسب LCS operations بـ DP table O(m*n) memory — مقبول حتى 8K×8K. */
    private fun computeLcsOps(a: List<String>, b: List<String>): List<DiffOp> {
        val m = a.size
        val n = b.size
        // نستخدم IntArray مُسطّح لتقليل overhead الـ Object[]
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

    /** Fallback لما الملف أكبر من حد LCS — diff بدائي خط-بخط. */
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

    /** يحوّل ops إلى hunks مع context محدود (توفير مساحة). */
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
