package com.qutschwalze.meetingtranscriber

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Simplified speaker diarization.
 * Compares feature vectors and detects changes when they diverge enough.
 * Focus on features that distinguish male/female voices:
 * - RMS Energy (women tend quieter)
 * - Zero Crossing Rate (higher for female)
 * - Spectral flatness (voice quality)
 * - Peak amplitude distribution
 */
class SpeakerDiarizer(
    private val changeThreshold: Float = 0.35f,
    private val matchThreshold: Float = 0.25f,
    private val minSegmentMs: Long = 500,
    private val silenceMs: Long = 350
) {
    data class SpeakerProfile(val id: Int, val centroid: FloatArray, var count: Int = 0)
    data class Result(val speakerId: Int, val changed: Boolean)

    private val speakers = mutableListOf<SpeakerProfile>()
    private var currentId = 0
    private var lastChangeMs = 0L
    private var segment = mutableListOf<FloatArray>()
    private var lastSpeechMs = 0L
    private var silent = true
    private var calibrating = true
    private var calibCount = 0

    fun reset() {
        speakers.clear()
        currentId = 0
        lastChangeMs = 0L
        segment.clear()
        lastSpeechMs = 0L
        silent = true
        calibrating = true
        calibCount = 0
    }

    fun analyze(samples: ShortArray, size: Int, nowMs: Long): Result {
        val f = features(samples, size)
        val rms = f[0]

        // Silence gate
        if (rms < 0.012f) {
            if (!silent) { silent = true; lastSpeechMs = nowMs }
            return Result(currentId, false)
        }

        if (silent) {
            silent = false
            val gap = nowMs - lastSpeechMs
            if (gap < silenceMs && lastSpeechMs > 0) {
                segment.add(f)
                return Result(currentId, false)
            }
            segment.clear()
        }

        segment.add(f)

        // Calibration: first ~0.5s establishes speaker 0
        if (calibrating) {
            calibCount++
            if (calibCount >= 5 && speakers.isEmpty()) {
                speakers.add(SpeakerProfile(0, centroid(segment)))
            }
            if (calibCount >= 10) calibrating = false
            return Result(currentId, false)
        }

        // Need minimum segment length
        if (nowMs - lastChangeMs < minSegmentMs) return Result(currentId, false)
        if (segment.size < 3) return Result(currentId, false)

        val c = centroid(segment)

        // Find best matching speaker
        var bestId = -1
        var bestDist = Float.MAX_VALUE
        var secondDist = Float.MAX_VALUE

        for (sp in speakers) {
            val d = dist(c, sp.centroid)
            if (d < bestDist) {
                secondDist = bestDist
                bestDist = d
                bestId = sp.id
            } else if (d < secondDist) {
                secondDist = d
            }
        }

        // If best match is clearly better than second → same speaker
        val gap = secondDist - bestDist
        if (bestId >= 0 && bestDist < matchThreshold && gap > 0.05f) {
            if (bestId != currentId) {
                currentId = bestId
                segment.clear(); segment.add(f)
                lastChangeMs = nowMs
                return Result(currentId, true)
            }
            segment.clear(); segment.add(f)
            lastChangeMs = nowMs
            return Result(currentId, false)
        }

        // If best is close enough → same speaker (no gap required)
        if (bestId >= 0 && bestDist < matchThreshold * 0.7f) {
            segment.clear(); segment.add(f)
            lastChangeMs = nowMs
            return Result(currentId, false)
        }

        // Features diverge significantly → new speaker
        if (bestDist > changeThreshold || bestId < 0) {
            val newId = speakers.size
            speakers.add(SpeakerProfile(newId, c))
            currentId = newId
            segment.clear(); segment.add(f)
            lastChangeMs = nowMs
            return Result(newId, true)
        }

        // Somewhat close to existing → stay with current
        segment.clear(); segment.add(f)
        lastChangeMs = nowMs
        return Result(currentId, false)
    }

    private fun centroid(feat: List<FloatArray>): FloatArray {
        if (feat.isEmpty()) return FloatArray(4)
        val dim = feat[0].size
        val c = FloatArray(dim)
        for (f in feat) for (i in 0 until dim) c[i] = c[i] + f[i]
        for (i in 0 until dim) c[i] = c[i] / feat.size
        return c
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) {
            val d = a[i] - b[i]
            s = s + d * d
        }
        return sqrt(s)
    }

    /**
     * 4 features optimized for speaker distinction:
     * [0] RMS energy
     * [1] Zero crossing rate
     * [2] Peak-to-RMS ratio (crest factor)
     * [3] Spectral flatness
     */
    private fun features(s: ShortArray, n: Int): FloatArray {
        var sumSq = 0.0
        var sumAbs = 0.0
        var maxAbs = 0
        var zc = 0

        for (i in 0 until n) {
            val v = s[i].toDouble() / Short.MAX_VALUE
            sumSq += v * v
            val a = abs(v)
            sumAbs += a
            if (a * Short.MAX_VALUE > maxAbs) maxAbs = (a * Short.MAX_VALUE).toInt()
            if (i > 0 && ((s[i] >= 0 && s[i-1] < 0) || (s[i] < 0 && s[i-1] >= 0))) zc++
        }

        val rms = sqrt(sumSq / n).toFloat()
        val zcr = (zc.toFloat() / n).coerceIn(0f, 1f)
        val meanAbs = (sumAbs / n).toFloat()
        val crestFactor = if (meanAbs > 0.001f) (maxAbs.toFloat() / Short.MAX_VALUE / meanAbs).coerceIn(0f, 5f) else 0f

        // Spectral flatness via geometric/arithmetic mean of absolute values
        var logSum = 0.0
        for (i in 0 until n) {
            val v = abs(s[i].toDouble()) + 1.0
            logSum += kotlin.math.ln(v)
        }
        val geoMean = kotlin.math.exp(logSum / n)
        val specFlat = (geoMean / (meanAbs * Short.MAX_VALUE + 1.0)).toFloat().coerceIn(0f, 1f)

        return floatArrayOf(rms, zcr, crestFactor, specFlat)
    }
}
