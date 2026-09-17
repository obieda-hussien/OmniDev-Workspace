package com.omnidev.workspace.data.brain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Lightweight, deterministic multilingual embedding used by OmniDev's local memory systems.
 *
 * This is intentionally mobile-first: no model download, no network, and no persistent index is
 * required. It combines word hashing, character n-grams, and token bigrams into a normalized
 * 256-dimensional vector that works for Arabic, English, code identifiers, and mixed text.
 */
object HashEmbedder {

    /** 256 floats × 4 bytes = 1024 bytes. */
    const val DIM = 256

    private val STOP_WORDS = setOf(
        // English
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be",
        "been", "being", "have", "has", "had", "do", "does", "did", "of", "to",
        "in", "on", "for", "with", "at", "by", "from", "as", "this", "that",
        "it", "its", "i", "you", "he", "she", "we", "they", "them", "my",
        "your", "our", "their", "can", "could", "should", "would", "will",
        // Arabic (normalized forms where applicable)
        "في", "من", "علي", "الى", "عن", "هذا", "هذه", "ذلك", "تلك", "هو", "هي",
        "هم", "هن", "انا", "انت", "انتم", "نحن", "ما", "ماذا", "لم", "لن", "لا",
        "ليس", "كان", "كانت", "يكون", "مع", "او", "ثم", "كل", "اي", "تم", "قد",
        "لقد", "كما", "لكن", "اذا", "هناك", "هنا", "بعد", "قبل", "بين", "عند"
    )

    private const val MAX_TOKENS = 200

    /** Returns an L2-normalized local embedding. */
    fun embed(text: String): FloatArray {
        if (text.isBlank()) return FloatArray(DIM)

        val vec = FloatArray(DIM)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vec

        for (token in tokens) {
            addHashed(vec, token, weight = 1.0f)
        }

        // Character n-grams make retrieval tolerant of spelling variants, inflection and typos.
        for (token in tokens) {
            if (token.length < 3) continue
            for (n in 3..4) {
                if (token.length < n) continue
                for (i in 0..token.length - n) {
                    addHashed(vec, "#${token.substring(i, i + n)}", weight = 0.5f)
                }
            }
        }

        // Token bigrams preserve a small amount of local phrase/order information.
        for (i in 0 until tokens.size - 1) {
            addHashed(vec, "${tokens[i]}_${tokens[i + 1]}", weight = 0.3f)
        }

        l2Normalize(vec)
        return vec
    }

    /** Cosine similarity for normalized vectors. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.coerceIn(-1f, 1f)
    }

    fun toBytes(vec: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vec) buffer.putFloat(value)
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val count = bytes.size / 4
        val output = FloatArray(count)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) output[i] = buffer.float
        return output
    }

    private fun tokenize(text: String): List<String> =
        normalize(text)
            .split(Regex("\\s+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .take(MAX_TOKENS)
            .toList()

    /**
     * Unicode-aware normalization. Arabic diacritics/tatweel are removed and common Alef/Ya
     * variants are folded so Egyptian/Modern Standard Arabic queries match more consistently.
     */
    internal fun normalize(text: String): String {
        val output = StringBuilder(text.length)
        for (raw in text) {
            if (raw in '\u064B'..'\u065F' || raw == '\u0670' || raw == '\u0640') continue

            val ch = when (raw) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                else -> raw.lowercaseChar()
            }

            if (ch.isLetterOrDigit() || ch == '_') output.append(ch) else output.append(' ')
        }
        return output.toString()
    }

    private fun addHashed(vec: FloatArray, token: String, weight: Float) {
        val hash = stableHash(token)
        val index = (hash ushr 1) % DIM
        val sign = if (hash and 1 == 0) 1f else -1f
        vec[index] += sign * weight
    }

    private fun stableHash(value: String): Int {
        var hash = 0x9E3779B1.toInt()
        for (char in value) {
            hash = hash xor char.code
            hash *= 0x01000193.toInt()
            hash = (hash shl 13) or (hash ushr 19)
        }
        return hash and 0x7FFFFFFF
    }

    private fun l2Normalize(vec: FloatArray) {
        var sumSq = 0.0
        for (value in vec) sumSq += value * value
        if (sumSq <= 1e-12) return
        val norm = sqrt(sumSq).toFloat()
        for (i in vec.indices) vec[i] /= norm
    }
}
