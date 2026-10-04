package com.omnidev.workspace.data.voice

import kotlin.math.*

/** 20ms chunks, adaptive noise floor, 160ms pre-roll, 400ms endpoint, bounded memory. */
class WakeSegmenter(initialNoise: Double = 100.0) {
    private val preRoll = ArrayDeque<ShortArray>()
    private val speech = ArrayList<ShortArray>()
    private var noise = initialNoise.also { require(it.isFinite() && it in 0.0..5000.0) }.coerceAtLeast(50.0)
    private var quiet = 0
    private var voiced = 0
    private var overflow = false
    fun reset() { preRoll.forEach { it.fill(0) }; speech.forEach { it.fill(0) }; preRoll.clear(); speech.clear(); quiet = 0; voiced = 0; overflow = false }
    fun accept(chunk: ShortArray): ShortArray? {
        require(chunk.size == 320)
        val rms = sqrt(chunk.sumOf { it.toDouble() * it } / chunk.size)
        val active = rms > max(250.0, noise * 3)
        if (overflow) {
            quiet = if (active) 0 else quiet + 1
            if (quiet >= 20) reset()
            return null
        }
        if (speech.isEmpty()) {
            if (!active) {
                noise = .98 * noise + .02 * rms.coerceAtMost(noise * 1.1 + 50)
                preRoll.addLast(chunk.copyOf()); if (preRoll.size > 8) preRoll.removeFirst().fill(0)
                return null
            }
            speech.addAll(preRoll); preRoll.clear()
        }
        speech.add(chunk.copyOf())
        if (active) { quiet = 0; voiced++ } else quiet++
        if (speech.size > 150) { speech.forEach { it.fill(0) }; speech.clear(); overflow = true }
        if (quiet < 20) return null
        val end = (speech.size - quiet + 4).coerceAtLeast(0)
        val result = if (!overflow && voiced >= 15 && end in 17..140)
            ShortArray(end * 320) { speech[it / 320][it % 320] } else null
        speech.forEach { it.fill(0) }; reset()
        return result
    }
}
