package com.omnidev.workspace.data.tools

import kotlin.math.sqrt

/**
 * Ephemeral multilingual embedding dedicated to Omni Memory retrieval.
 *
 * It is intentionally separate from Agent Brain's persisted HashEmbedder format so retrieval can
 * evolve without invalidating embeddings already stored by episodic/reflexion memory.
 */
internal object OmniMemoryEmbedder {
    private const val DIM = 256
    private const val MAX_TOKENS = 200

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be",
        "been", "being", "have", "has", "had", "do", "does", "did", "of", "to",
        "in", "on", "for", "with", "at", "by", "from", "as", "this", "that",
        "it", "its", "i", "you", "he", "she", "we", "they", "them", "my",
        "your", "our", "their", "can", "could", "should", "would", "will",
        "في", "من", "علي", "الى", "عن", "هذا", "هذه", "ذلك", "تلك", "هو", "هي",
        "هم", "هن", "انا", "انت", "انتم", "نحن", "ما", "ماذا", "لم", "لن", "لا",
        "ليس", "كان", "كانت", "يكون", "مع", "او", "ثم", "كل", "اي", "تم", "قد",
        "لقد", "كما", "لكن", "اذا", "هناك", "هنا", "بعد", "قبل", "بين", "عند"
    )

    fun embed(text: String): FloatArray {
        if (text.isBlank()) return FloatArray(DIM)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return FloatArray(DIM)

        val vector = FloatArray(DIM)
        for (token in tokens) addHashed(vector, token, 1.0f)

        for (token in tokens) {
            if (token.length < 3) continue
            for (n in 3..4) {
                if (token.length < n) continue
                for (i in 0..token.length - n) {
                    addHashed(vector, "#${token.substring(i, i + n)}", 0.5f)
                }
            }
        }

        for (i in 0 until tokens.size - 1) {
            addHashed(vector, "${tokens[i]}_${tokens[i + 1]}", 0.3f)
        }

        normalizeVector(vector)
        return vector
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.coerceIn(-1f, 1f)
    }

    fun normalizeText(text: String): String {
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

    private fun tokenize(text: String): List<String> =
        normalizeText(text)
            .split(Regex("\\s+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .take(MAX_TOKENS)
            .toList()

    private fun addHashed(vector: FloatArray, token: String, weight: Float) {
        val hash = stableHash(token)
        val index = (hash ushr 1) % DIM
        val sign = if (hash and 1 == 0) 1f else -1f
        vector[index] += sign * weight
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

    private fun normalizeVector(vector: FloatArray) {
        var sumSq = 0.0
        for (value in vector) sumSq += value * value
        if (sumSq <= 1e-12) return
        val norm = sqrt(sumSq).toFloat()
        for (i in vector.indices) vector[i] /= norm
    }
}
