package com.omnidev.workspace.data.voice

object WakePhrasePolicy {
    const val DEFAULT = "Hi Omni"
    fun normalize(value: String): String {
        require(value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            "Use a short phrase without control or formatting characters."
        }
        val phrase = java.text.Normalizer.normalize(value.trim().replace(Regex("\\s+"), " "), java.text.Normalizer.Form.NFC)
        require(phrase.length in 1..60 && phrase.any { it.isLetterOrDigit() }) { "Enter a wake phrase of 1–60 characters." }
        return phrase
    }
}
