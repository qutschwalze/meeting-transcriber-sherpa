package com.qutschwalze.meetingtranscriber

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio preprocessor for better Vosk recognition.
 * - Noise gate: mute silence below threshold
 * - Auto gain control: normalize volume levels
 * - Voice activity detection: detect speech segments
 */
class AudioPreprocessor(
    private val noiseThreshold: Float = 0.02f,    // Below this = silence (2% of max)
    private val targetRMS: Float = 0.15f,          // Target RMS for AGC (15% of max)
    private val attackTime: Float = 0.1f,          // AGC attack speed (fast)
    private val releaseTime: Float = 0.3f,         // AGC release speed (slow)
    private val silenceTimeoutMs: Long = 800       // Silence duration to consider "stopped speaking"
) {
    private var currentGain = 1.0f
    private var isSpeaking = false
    private var silenceStart = 0L

    data class ProcessedAudio(
        val samples: ShortArray,
        val isSpeech: Boolean,
        val rms: Float,
        val wasMuted: Boolean
    )

    /**
     * Process audio buffer: apply noise gate + AGC + VAD.
     */
    fun process(samples: ShortArray, count: Int, timestampMs: Long): ProcessedAudio {
        val n = count.coerceAtMost(samples.size)
        val output = ShortArray(n)

        // Step 1: Calculate input RMS
        val inputRMS = calculateRMS(samples, n)

        // Step 2: Voice Activity Detection (before processing)
        val isSpeech = inputRMS > noiseThreshold
        val wasMuted = !isSpeech

        // Step 3: Update AGC gain
        if (isSpeech) {
            val error = targetRMS - inputRMS
            val adjustment = if (error > 0) {
                1.0f + (error * attackTime * 10)
            } else {
                1.0f + (error * releaseTime * 5)
            }
            currentGain = (currentGain * adjustment).coerceIn(0.5f, 8.0f)
        }

        // Step 4: Apply processing
        for (i in 0 until n) {
            if (!isSpeech) {
                // Noise gate: mute below threshold
                output[i] = 0
            } else {
                // Apply AGC
                val amplified = (samples[i].toFloat() * currentGain).toInt()
                output[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        // Step 5: Track silence for VAD
        if (!isSpeech) {
            if (silenceStart == 0L) silenceStart = timestampMs
        } else {
            silenceStart = 0L
        }

        val outputRMS = calculateRMS(output, n)

        return ProcessedAudio(
            samples = output,
            isSpeech = isSpeech,
            rms = outputRMS,
            wasMuted = wasMuted
        )
    }

    private fun calculateRMS(samples: ShortArray, count: Int): Float {
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble() / Short.MAX_VALUE
            sumSq += s * s
        }
        return sqrt(sumSq / count).toFloat()
    }

    fun isSilentNow(): Boolean {
        return silenceStart > 0 && 
               (System.currentTimeMillis() - silenceStart) > silenceTimeoutMs
    }

    fun reset() {
        currentGain = 1.0f
        isSpeaking = false
        silenceStart = 0L
    }
}
