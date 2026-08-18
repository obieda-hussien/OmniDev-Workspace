package com.omnidev.workspace.data.brain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * HashEmbedder — [Localized] Embedding [Localized] [Localized] [Localized] (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Mobile-first by design:
 *   - 0 RAM [Localized] (stateless[Localized] singleton object)
 *   - 0 disk I/O ([Localized] [Localized] [Localized])
 *   - ~0.5 ms [Localized] 100 [Localized] [Localized] Snapdragon 660
 *   - [Localized] offline [Localized]
 *
 * **[Localized]**: hashing trick + character n-grams + bigrams.
 *   1) Tokenize (lowercase + diacritic strip + punctuation strip)
 *   2) [Localized] stop-words [Localized]/[Localized] [Localized]
 *   3) [Localized] token: hash(word) → index[Localized] [Localized] [Localized] [Localized]
 *   4) [Localized] token: char 3-grams [Localized] 4-grams → [Localized] [Localized]
 *   5) bigrams [Localized] tokens [Localized]
 *   6) L2-normalize → cosine similarity [Localized] [Localized] vector [Localized]
 *
 * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]/[Localized].
 * [Localized] [Localized] TF-IDF [Localized] retrieval[Localized] [Localized] [Localized] [Localized] any embedding model.
 */
object HashEmbedder {

    /** [Localized] [Localized] embedding — 256 floats × 4 bytes = 1024 bytes. */
    const val DIM = 256

    /** Stop-words [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] embedding). */
    private val STOP_WORDS = setOf(
        // English
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be",
        "been", "being", "have", "has", "had", "do", "does", "did", "of", "to",
        "in", "on", "for", "with", "at", "by", "from", "as", "this", "that",
        "it", "its", "i", "you", "he", "she", "we", "they", "them",
        // Arabic
        "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]",
        "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]",
        "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]", "[Localized]"
    )

    private const val MAX_TOKENS = 200

    // ──────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────

    /** [Localized] embedding L2-normalized [Localized]. */
    fun embed(text: String): FloatArray {
        if (text.isBlank()) return FloatArray(DIM)

        val vec = FloatArray(DIM)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vec

        // 1) word-level
        for (tok in tokens) {
            addHashed(vec, tok, weight = 1.0f)
        }

        // 2) char n-grams (3 [Localized] 4) — [Localized] [Localized] [Localized]
        for (tok in tokens) {
            if (tok.length < 3) continue
            for (n in 3..4) {
                if (tok.length < n) continue
                for (i in 0..tok.length - n) {
                    addHashed(vec, "#${tok.substring(i, i + n)}", weight = 0.5f)
                }
            }
        }

        // 3) bigrams [Localized] tokens [Localized] ([Localized] [Localized])
        for (i in 0 until tokens.size - 1) {
            addHashed(vec, "${tokens[i]}_${tokens[i + 1]}", weight = 0.3f)
        }

        l2Normalize(vec)
        return vec
    }

    /** Cosine similarity [Localized] [Localized] L2-normalized — [Localized] dot product. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.coerceIn(-1f, 1f)
    }

    // ──────────────────────────────────────────────────────────────────
    // Serialization (FloatArray ↔ ByteArray) [Localized] [Localized] Room
    // ──────────────────────────────────────────────────────────────────

    fun toBytes(vec: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) buf.putFloat(v)
        return buf.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val n = bytes.size / 4
        val out = FloatArray(n)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = buf.float
        return out
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    private fun tokenize(text: String): List<String> {
        val normalized = normalize(text)
        return normalized.split(Regex("\\s+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .take(MAX_TOKENS)
            .toList()
    }

    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            // Skip Arabic diacritics (U+064B..U+065F, U+0670)
            if (ch in '\u064B'..'\u065F' || ch == '\u0670') continue
            // Punctuation → space
            if (!ch.isLetterOrDigit() && ch != '_') sb.append(' ')
            else sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * Hashing trick: [Localized] 2 indices [Localized] [Localized] vector + [Localized] [Localized] [Localized] [Localized] hash.
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] double-hashing [Localized].
     */
    private fun addHashed(vec: FloatArray, token: String, weight: Float) {
        val h = stableHash(token)
        val idx = (h ushr 1) % DIM
        val sign = if (h and 1 == 0) 1f else -1f
        vec[idx] += sign * weight
    }

    /** Murmur-like 32-bit hash. [Localized] [Localized] [Localized] [Localized] JVM ([Localized] [Localized] [Localized] String.hashCode). */
    private fun stableHash(s: String): Int {
        var h = 0x9E3779B1.toInt()
        for (i in s.indices) {
            h = h xor s[i].code
            h *= 0x01000193.toInt()
            h = (h shl 13) or (h ushr 19) // rotate
        }
        return h and 0x7FFFFFFF
    }

    private fun l2Normalize(vec: FloatArray) {
        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        if (sumSq <= 1e-12) return
        val norm = sqrt(sumSq).toFloat()
        for (i in vec.indices) vec[i] = vec[i] / norm
    }
}
