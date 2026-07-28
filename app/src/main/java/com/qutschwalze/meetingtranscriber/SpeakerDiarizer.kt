package com.qutschwalze.meetingtranscriber

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Speaker diarization using audio features.
 *
 * Key features for male/female distinction:
 * - Pitch (autocorrelation-based) — strongest gender indicator
 * - Zero Crossing Rate — correlates with pitch
 * - RMS Energy — volume differences
 * - Spectral centroid proxy — voice brightness
 * - Energy dynamics — speaking pattern
 */
class SpeakerDiarizer(
    private val sensitivity: Float = 1.0f,
    private val minSegmentDurationMs: Long = 600,
    private val silenceThresholdMs: Long = 400
) {
    data class SpeakerProfile(
        val id: Int,
        var centroid: FloatArray,
        var matchCount: Int = 0,
        var lastSeenMs: Long = 0
    )

    data class SpeakerResult(val speakerId: Int, val changed: Boolean)

    private val knownSpeakers = mutableListOf<SpeakerProfile>()
    private var currentSpeakerId = 0
    private var lastSpeakerChangeMs = 0L
    private var segmentFeatures = mutableListOf<FloatArray>()
    private var lastSpeechEndMs = 0L
    private var inSilence = true
    private var calibrationCount = 0
    private val calibrationWindows = 6  // ~0.6s

    fun reset() {
        knownSpeakers.clear()
        currentSpeakerId = 0
        lastSpeakerChangeMs = 0L
        segmentFeatures.clear()
        lastSpeechEndMs = 0L
        inSilence = true
        calibrationCount = 0
    }

    fun analyze(samples: ShortArray, size: Int, currentTimeMs: Long): SpeakerResult {
        val features = extractFeatures(samples, size)
        val rms = features[0]

        // Gate: skip silence
        if (rms < 0.015f) {
            if (!inSilence) {
                inSilence = true
                lastSpeechEndMs = currentTimeMs
            }
            return SpeakerResult(currentSpeakerId, false)
        }

        // Speech detected after silence
        if (inSilence) {
            inSilence = false
            val gap = currentTimeMs - lastSpeechEndMs
            if (gap < silenceThresholdMs && lastSpeechEndMs > 0) {
                // Short pause — same speaker continues
                segmentFeatures.add(features)
                return SpeakerResult(currentSpeakerId, false)
            }
            // Long pause or first speech — reset segment for fresh comparison
            segmentFeatures.clear()
        }

        segmentFeatures.add(features)

        // Calibration phase
        if (calibrationCount < calibrationWindows) {
            calibrationCount++
            if (calibrationCount == calibrationWindows && knownSpeakers.isEmpty()) {
                // First speaker established
                val centroid = calcCentroid(segmentFeatures)
                knownSpeakers.add(SpeakerProfile(0, centroid, 0, currentTimeMs))
            }
            return SpeakerResult(currentSpeakerId, false)
        }

        // Minimum segment duration check
        if (currentTimeMs - lastSpeakerChangeMs < minSegmentDurationMs) {
            return SpeakerResult(currentSpeakerId, false)
        }

        // Need at least 3 feature windows
        if (segmentFeatures.size < 3) {
            return SpeakerResult(currentSpeakerId, false)
        }

        val centroid = calcCentroid(segmentFeatures)

        // Compare against ALL known speakers
        var bestMatchId = -1
        var bestDistance = Float.MAX_VALUE
        var secondBestDistance = Float.MAX_VALUE

        for (speaker in knownSpeakers) {
            val dist = featureDist(centroid, speaker.centroid)
            if (dist < bestDistance) {
                secondBestDistance = bestDistance
                bestDistance = dist
                bestMatchId = speaker.id
            } else if (dist < secondBestDistance) {
                secondBestDistance = dist
            }
        }

        // Key insight: use the GAP between best and second-best match.
        // If best is much closer than second-best → confident match to existing speaker.
        // If best and second-best are similar → ambiguous → possible new speaker.
        val gap = secondBestDistance - bestDistance

        // Tight re-identification: best match must be clearly closer than second-best
        val reidThreshold = sensitivity * 1.2f
        val minGap = sensitivity * 0.3f

        if (bestMatchId >= 0 && bestDistance < reidThreshold && gap > minGap) {
            // Confident match to known speaker
            if (bestMatchId != currentSpeakerId) {
                // Speaker switched back
                currentSpeakerId = bestMatchId
                knownSpeakers.find { it.id == bestMatchId }?.let {
                    it.matchCount++
                    it.lastSeenMs = currentTimeMs
                    it.centroid = blendCentroid(it.centroid, centroid, 0.3f)
                }
                segmentFeatures.clear()
                segmentFeatures.add(features)
                lastSpeakerChangeMs = currentTimeMs
                return SpeakerResult(currentSpeakerId, true)
            }
            // Same speaker continues
            knownSpeakers.find { it.id == bestMatchId }?.lastSeenMs = currentTimeMs
            segmentFeatures.clear()
            segmentFeatures.add(features)
            lastSpeakerChangeMs = currentTimeMs
            return SpeakerResult(currentSpeakerId, false)
        }

        // No confident match → new speaker
        val newId = knownSpeakers.size
        knownSpeakers.add(SpeakerProfile(newId, centroid, 0, currentTimeMs))
        currentSpeakerId = newId
        segmentFeatures.clear()
        segmentFeatures.add(features)
        lastSpeakerChangeMs = currentTimeMs
        return SpeakerResult(newId, true)
    }

    private fun blendCentroid(old: FloatArray, new: FloatArray, alpha: Float): FloatArray {
        return FloatArray(old.size) { i -> old[i] * (1 - alpha) + new[i] * alpha }
    }

    private fun calcCentroid(features: List<FloatArray>): FloatArray {
        if (features.isEmpty()) return FloatArray(5)
        val dim = features[0].size
        val c = FloatArray(dim)
        for (f in features) for (i in 0 until dim) c[i] = c[i] + f[i]
        for (i in 0 until dim) c[i] = c[i] / features.size
        return c
    }

    private fun featureDist(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    /**
     * Extract features optimized for male/female voice distinction.
     * Feature 0: RMS energy
     * Feature 1: Pitch proxy (autocorrelation fundamental frequency)
     * Feature 2: Zero crossing rate
     * Feature 3: Spectral brightness (high-freq energy ratio)
     * Feature 4: Energy variance (dynamics)
     */
    private fun extractFeatures(samples: ShortArray, size: Int): FloatArray {
        var sumSq = 0.0
        var maxAbs = 0

        for (i in 0 until size) {
            val s = samples[i].toDouble() / Short.MAX_VALUE
            sumSq += s * s
            val a = abs(samples[i].toInt())
            if (a > maxAbs) maxAbs = a
        }
        val rms = sqrt(sumSq / size).toFloat()

        // Pitch proxy via autocorrelation (simplified)
        // Male voices: ~85-180Hz, Female voices: ~165-255Hz
        // At 16kHz: male lag ~88-188, female lag ~63-97
        val pitchProxy = estimatePitch(samples, size)

        // Zero Crossing Rate
        var zc = 0
        for (i in 1 until size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) ||
                (samples[i] < 0 && samples[i - 1] >= 0)) zc++
        }
        val zcr = (zc.toFloat() / size).coerceIn(0f, 1f)

        // Spectral brightness: ratio of high-frequency energy to total
        val mid = size / 2
        var highEnergy = 0.0
        var totalEnergy = 0.0
        for (i in 0 until size) {
            val s = samples[i].toDouble() / Short.MAX_VALUE
            totalEnergy += s * s
            if (i >= mid) highEnergy += s * s
        }
        val brightness = if (totalEnergy > 0) (highEnergy / totalEnergy).toFloat() else 0f

        // Energy variance across sub-windows
        val subSize = (size / 4).coerceAtLeast(1)
        val energies = (0 until 4).map { chunk ->
            val start = chunk * subSize
            val end = minOf(start + subSize, size)
            var e = 0.0
            for (i in start until end) {
                val s = samples[i].toDouble() / Short.MAX_VALUE
                e += s * s
            }
            (e / (end - start)).toFloat()
        }
        val mean = energies.average().toFloat()
        val variance = sqrt(energies.map { (it - mean) * (it - mean) }.average()).toFloat()

        return floatArrayOf(rms, pitchProxy, zcr, brightness, variance)
    }

    /**
     * Simple pitch estimation via autocorrelation.
     * Returns a normalized pitch value (higher = higher pitch = more likely female).
     */
    private fun estimatePitch(samples: ShortArray, size: Int): Float {
        if (size < 256) return 0.5f

        // Check lags corresponding to 60-300Hz at 16kHz
        // Lag = 16000 / freq
        // 300Hz → lag 53, 60Hz → lag 267
        val minLag = 53   // 300Hz
        val maxLag = 267  // 60Hz

        var bestCorr = 0.0
        var bestLag = minLag

        for (lag in minLag..maxLag) {
            var corr = 0.0
            var norm1 = 0.0
            var norm2 = 0.0
            val n = size - lag
            for (i in 0 until n) {
                val a = samples[i].toDouble() / Short.MAX_VALUE
                val b = samples[i + lag].toDouble() / Short.MAX_VALUE
                corr += a * b
                norm1 += a * a
                norm2 += b * b
            }
            val norm = sqrt(norm1 * norm2)
            if (norm > 0) {
                corr /= norm
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLag = lag
                }
            }
        }

        // Convert lag to normalized pitch value
        // Lower lag = higher pitch
        // Male: lag ~100-180, Female: lag ~60-100
        // Normalize: 0 = low pitch (male), 1 = high pitch (female)
        val freq = 16000.0 / bestLag
        return ((freq - 60.0) / 240.0).coerceIn(0.0, 1.0).toFloat()
    }
}
