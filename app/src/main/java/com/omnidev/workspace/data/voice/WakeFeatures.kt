package com.omnidev.workspace.data.voice

import kotlin.math.*

/** Local 16 kHz MFCC frontend. No recognizer, network, transcript, or retained PCM. */
object WakeFeatures {
    const val RATE = 16_000
    const val DIM = 12
    data class Sample(val frames: Array<FloatArray>, val voice: FloatArray)
    private const val FFT = 512
    private const val WINDOW = 400
    private const val HOP = 160
    private val window = DoubleArray(WINDOW) { 0.54 - 0.46 * cos(2 * PI * it / (WINDOW - 1)) }
    private val filters = Array(26) { band ->
        // Convert equally spaced natural-log mel points directly back to Hz.
        fun bin(i: Int) = ((FFT + 1) * (700 * (exp(ln(1 + 7600.0 / 700) * i / 27) - 1)) / RATE).toInt()
        val left = bin(band); val center = bin(band + 1); val right = bin(band + 2)
        DoubleArray(FFT / 2 + 1) { k -> when {
            k < left || k > right -> 0.0
            k <= center -> (k - left).toDouble() / (center - left).coerceAtLeast(1)
            else -> (right - k).toDouble() / (right - center).coerceAtLeast(1)
        } }
    }
    private val dct = Array(DIM) { c -> DoubleArray(26) { k -> cos(PI * (c + 1) * (k + .5) / 26) * sqrt(2.0 / 26) } }

    fun extract(pcm: ShortArray): Sample {
        require(pcm.size in RATE / 3..RATE * 3) { "Say the complete phrase in 0.4–2.5 seconds." }
        val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
        require(rms > 200 && pcm.count { abs(it.toInt()) >= 32700 } < pcm.size / 100) { "Recording is too quiet or clipped. Try again." }
        val raw = Array((pcm.size - WINDOW) / HOP + 1) { n ->
            val real = DoubleArray(FFT); val imaginary = DoubleArray(FFT)
            for (i in 0 until WINDOW) {
                val p = n * HOP + i
                real[i] = (pcm[p] - .97 * (if (p > 0) pcm[p - 1].toDouble() else 0.0)) / 32768 * window[i]
            }
            fft(real, imaginary)
            val power = DoubleArray(FFT / 2 + 1) { real[it] * real[it] + imaginary[it] * imaginary[it] }
            val logs = DoubleArray(26) { b -> ln(filters[b].indices.sumOf { k -> filters[b][k] * power[k] }.coerceAtLeast(1e-12)) }
            FloatArray(DIM) { c -> (logs.indices.sumOf { dct[c][it] * logs[it] } / 10).toFloat() }
        }
        val mean = FloatArray(DIM) { c -> raw.sumOf { it[c].toDouble() }.toFloat() / raw.size }
        val frames = Array(raw.size) { n -> FloatArray(DIM * 2) { c ->
            if (c < DIM) raw[n][c] - mean[c]
            else (raw[(n + 2).coerceAtMost(raw.lastIndex)][c - DIM] - raw[(n - 2).coerceAtLeast(0)][c - DIM]) / 4
        } }
        return Sample(frames, mean)
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until FFT) {
            var bit = FFT shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val r = real[i]; real[i] = real[j]; real[j] = r }
        }
        var size = 2
        while (size <= FFT) {
            val angle = -2 * PI / size
            for (start in 0 until FFT step size) for (k in 0 until size / 2) {
                val a = start + k; val b = a + size / 2
                val re = cos(angle * k) * real[b] - sin(angle * k) * imaginary[b]
                val im = sin(angle * k) * real[b] + cos(angle * k) * imaginary[b]
                real[b] = real[a] - re; imaginary[b] = imaginary[a] - im
                real[a] += re; imaginary[a] += im
            }
            size *= 2
        }
    }
}
