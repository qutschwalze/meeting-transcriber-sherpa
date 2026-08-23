package com.sherpa.transcript.domain.audio

/**
 * Phase 9 (0.9.0): Linear-Interpolations-Resampler (pure Kotlin, JVM-testbar).
 * Für Sprachnachrichten-Import: beliebige Quellrate → 16 kHz mono.
 * Linear reicht für ASR-Qualität völlig (die Engine normalisiert ohnehin).
 */
object AudioResampler {

    /**
     * Resampelt [input] von [fromRate] auf [toRate] (Hz).
     * Identische Raten → Input unverändert zurückgegeben (keine Kopie).
     * Degenerate Eingaben (leer, ungültige Raten) → leeres Array.
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty() || fromRate <= 0 || toRate <= 0 || fromRate == toRate) {
            return if (fromRate == toRate && fromRate > 0) input else FloatArray(0)
        }
        val outLen = input.size.toLong() * toRate / fromRate
        // Schutz gegen Overflow bei absurd langen Inputs
        val outSize = minOf(outLen, Int.MAX_VALUE.toLong() / 4).toInt()
        val output = FloatArray(outSize)
        val step = fromRate.toDouble() / toRate
        var pos = 0.0
        for (i in 0 until outSize) {
            val idx = pos.toInt()
            if (idx >= input.size - 1) { output[i] = input[input.size - 1]; continue }
            val frac = (pos - idx).toFloat()
            output[i] = input[idx] * (1f - frac) + input[idx + 1] * frac
            pos += step
        }
        return output
    }
}