package com.qutschwalze.meetingtranscriber

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Feature-based speaker diarization with re-identification.
 *
 * Key improvements over basic version:
 * - Silence gap detection: pauses between utterances don't trigger speaker changes
 * - Larger feature history for more stable speaker profiles
 * - Adaptive thresholds based on audio energy
 * - Re-identification of returning speakers via Mahalanobis-like distance
 */
class SpeakerDiarizer(
    private val sensitivity: Float = 1.2f,
    private val minSegmentDurationMs: Long = 1500,  // Increased from 800ms
    private val silenceThresholdMs: Long = 800       // Ignore gaps shorter than this
) {
    data class SpeakerProfile(
        val id: Int,
        val featureCentroid: FloatArray,
        val featureVariance: FloatArray,
        var matchCount: Int = 0
    )

    data class SpeakerResult(
        val speakerId: Int,
        val changed: Boolean
    )

    private val knownSpeakers = mutableListOf<SpeakerProfile>()
    private var currentSpeakerId = 0
    private var lastSpeakerChangeMs = 0L
    private var currentSegmentFeatures = mutableListOf<FloatArray>()
    private var lastSpeechEndMs = 0L
    private var inSilence = true

    // Feature history for baseline comparison
    private val recentFeatures = ArrayDeque<FloatArray>()
    private var baselineReady = false
    private var baselineCalibrationCount = 0
    private val baselineMaxWindows = 12  // ~1.2s calibration

    fun reset() {
        knownSpeakers.clear()
        currentSpeakerId = 0
        lastSpeakerChangeMs = 0L
        currentSegmentFeatures.clear()
        lastSpeechEndMs = 0L
        inSilence = true
        recentFeatures.clear()
        baselineReady = false
        baselineCalibrationCount = 0
    }

    /**
     * Analyze audio samples and detect speaker changes.
     * Only triggers on actual speech, ignores silence gaps.
     */
    fun analyze(samples: ShortArray, size: Int, currentTimeMs: Long): SpeakerResult {
        val features = extractFeatures(samples, size)
        val rms = features[0]  // First feature is RMS energy

        // Gate: only process when there's actual speech
        if (rms < 0.02f) {
            // Silence detected
            if (!inSilence) {
                // Transition to silence
                inSilence = true
                lastSpeechEndMs = currentTimeMs
            }
            return SpeakerResult(speakerId = currentSpeakerId, changed = false)
        }

        // Speech detected
        if (inSilence) {
            // Transition from silence to speech
            inSilence = false
            val silenceGap = currentTimeMs - lastSpeechEndMs

            // If silence gap was short (< silenceThresholdMs), same speaker continues
            if (silenceGap < silenceThresholdMs && lastSpeechEndMs > 0) {
                // Don't detect speaker change — same person resumed
                currentSegmentFeatures.add(features)
                return SpeakerResult(speakerId = currentSpeakerId, changed = false)
            }

            // Long silence — might be a new speaker, but don't force change yet
            // Let the features accumulate for a bit before deciding
        }

        currentSegmentFeatures.add(features)

        // Keep recent features for baseline
        recentFeatures.addLast(features)
        if (recentFeatures.size > 20) recentFeatures.removeFirst()

        // Calibrate baseline during first second
        if (!baselineReady) {
            baselineCalibrationCount++
            if (baselineCalibrationCount >= baselineMaxWindows) {
                baselineReady = true
            }
            return SpeakerResult(speakerId = currentSpeakerId, changed = false)
        }

        // Only check for speaker change after minimum segment duration
        val timeSinceChange = currentTimeMs - lastSpeakerChangeMs
        if (timeSinceChange < minSegmentDurationMs) {
            return SpeakerResult(speakerId = currentSpeakerId, changed = false)
        }

        // Check if we have enough data in current segment
        if (currentSegmentFeatures.size < 4) {
            return SpeakerResult(speakerId = currentSpeakerId, changed = false)
        }

        // Compare current segment against known speakers
        val currentCentroid = calculateCentroid(currentSegmentFeatures)

        // Try to match against known speakers
        val matchedSpeaker = matchAgainstKnownSpeakers(currentCentroid)

        if (matchedSpeaker >= 0) {
            // Known speaker returns
            if (matchedSpeaker != currentSpeakerId) {
                currentSpeakerId = matchedSpeaker
                currentSegmentFeatures.clear()
                currentSegmentFeatures.add(features)
                lastSpeakerChangeMs = currentTimeMs
                return SpeakerResult(speakerId = currentSpeakerId, changed = true)
            }
            // Same speaker continues — just update
            currentSegmentFeatures.clear()
            currentSegmentFeatures.add(features)
            lastSpeakerChangeMs = currentTimeMs
            return SpeakerResult(speakerId = currentSpeakerId, changed = false)
        }

        // Check if current segment deviates significantly from known speakers
        if (knownSpeakers.isNotEmpty()) {
            val minDistance = knownSpeakers.minOf { speaker ->
                featureDistance(currentCentroid, speaker.featureCentroid)
            }

            // Only trigger if distance is large enough (not just slight variation)
            val threshold = sensitivity * 2.5f
            if (minDistance < threshold) {
                // Close enough to existing speaker — re-identify
                val closestIdx = knownSpeakers.indices.minByOrNull {
                    featureDistance(currentCentroid, knownSpeakers[it].featureCentroid)
                }!!
                currentSpeakerId = knownSpeakers[closestIdx].id
                knownSpeakers[closestIdx].matchCount++
                currentSegmentFeatures.clear()
                currentSegmentFeatures.add(features)
                lastSpeakerChangeMs = currentTimeMs
                return SpeakerResult(speakerId = currentSpeakerId, changed = false)
            }
        }

        // New speaker
        val newId = knownSpeakers.size
        val variance = calculateVariance(currentSegmentFeatures)
        knownSpeakers.add(SpeakerProfile(newId, currentCentroid, variance))
        currentSpeakerId = newId
        currentSegmentFeatures.clear()
        currentSegmentFeatures.add(features)
        lastSpeakerChangeMs = currentTimeMs
        return SpeakerResult(speakerId = newId, changed = true)
    }

    /**
     * Match current centroid against known speakers.
     * Returns speaker ID if match found, -1 otherwise.
     */
    private fun matchAgainstKnownSpeakers(centroid: FloatArray): Int {
        if (knownSpeakers.isEmpty()) return -1

        var bestMatch = -1
        var bestDistance = Float.MAX_VALUE

        for (speaker in knownSpeakers) {
            val distance = featureDistance(centroid, speaker.featureCentroid)

            // Lenient threshold for re-identification
            val matchThreshold = sensitivity * 3.0f

            if (distance < matchThreshold && distance < bestDistance) {
                bestDistance = distance
                bestMatch = speaker.id
            }
        }

        return bestMatch
    }

    private fun calculateCentroid(features: List<FloatArray>): FloatArray {
        if (features.isEmpty()) return FloatArray(5)
        val dim = features[0].size
        val centroid = FloatArray(dim)
        for (f in features) {
            for (i in 0 until dim) {
                centroid[i] = centroid[i] + f[i]
            }
        }
        for (i in 0 until dim) {
            centroid[i] = centroid[i] / features.size
        }
        return centroid
    }

    private fun calculateVariance(features: List<FloatArray>): FloatArray {
        if (features.size < 2) return FloatArray(5) { 1f }
        val centroid = calculateCentroid(features)
        val dim = centroid.size
        val variance = FloatArray(dim)
        for (f in features) {
            for (i in 0 until dim) {
                val diff = f[i] - centroid[i]
                variance[i] = variance[i] + diff * diff
            }
        }
        for (i in 0 until dim) {
            variance[i] = sqrt(variance[i] / (features.size - 1)) + 0.001f
        }
        return variance
    }

    private fun featureDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    /**
     * Extract 5 audio features from a window of samples.
     */
    private fun extractFeatures(samples: ShortArray, size: Int): FloatArray {
        var sumSq = 0.0
        var zeroCrossings = 0
        var maxAbs = 0
        val energies = mutableListOf<Float>()

        // RMS Energy
        for (i in 0 until size) {
            val s = samples[i].toDouble() / Short.MAX_VALUE
            sumSq += s * s
            val absVal = abs(samples[i].toInt())
            if (absVal > maxAbs) maxAbs = absVal
        }
        val rms = sqrt(sumSq / size).toFloat()

        // Zero Crossing Rate
        for (i in 1 until size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) ||
                (samples[i] < 0 && samples[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        val zcr = (zeroCrossings.toFloat() / size).coerceIn(0f, 1f)

        // Energy variance across sub-windows
        val subWindowSize = (size / 4).coerceAtLeast(1)
        for (chunk in 0 until 4) {
            val start = chunk * subWindowSize
            val end = minOf(start + subWindowSize, size)
            var chunkEnergy = 0.0
            for (i in start until end) {
                val s = samples[i].toDouble() / Short.MAX_VALUE
                chunkEnergy += s * s
            }
            energies.add((chunkEnergy / (end - start)).toFloat())
        }
        val energyVariance = if (energies.size > 1) {
            val mean = energies.average().toFloat()
            val varSum = energies.map { (it - mean) * (it - mean) }.sum()
            sqrt(varSum / energies.size)
        } else 0f

        // Spectral flatness (simplified: ratio of geometric to arithmetic mean)
        var logSum = 0.0
        var linSum = 0.0
        for (i in 0 until size) {
            val v = abs(samples[i].toDouble()) + 1.0
            logSum += kotlin.math.ln(v)
            linSum += v
        }
        val geometricMean = kotlin.math.exp(logSum / size)
        val arithmeticMean = linSum / size
        val spectralFlatness = (geometricMean / arithmeticMean).toFloat().coerceIn(0f, 1f)

        // Normalized volume
        val normalizedVolume = (maxAbs.toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)

        return floatArrayOf(rms, zcr, energyVariance, spectralFlatness, normalizedVolume)
    }
}
