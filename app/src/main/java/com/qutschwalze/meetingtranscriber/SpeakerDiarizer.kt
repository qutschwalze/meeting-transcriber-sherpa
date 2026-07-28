package com.qutschwalze.meetingtranscriber

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Speaker diarization with re-identification.
 * Stores feature fingerprints of known speakers and matches new segments against them.
 */
class SpeakerDiarizer(
    private val sensitivity: Float = 1.8f,
    private val minSegmentDurationMs: Long = 1200,
    private val windowSize: Int = 1600
) {
    private var lastChangeTime = 0L
    private var currentSpeakerId = -1
    private val knownSpeakers = mutableListOf<SpeakerProfile>()
    private var currentSegmentFeatures = mutableListOf<FloatArray>()
    private var baselineFeatures: FloatArray? = null
    private var baselineWindowCount = 0
    private val baselineMaxWindows = 8  // Faster baseline (~0.8s instead of ~2s)

    data class SpeakerProfile(
        val id: Int,
        val featureCentroid: FloatArray,  // Average features (centroid)
        val featureVariance: FloatArray,  // Variance of features
        var matchCount: Int = 0
    )

    data class SpeakerResult(
        val speakerId: Int,
        val energy: Float,
        val changed: Boolean
    )

    fun analyze(samples: ShortArray, bufferSize: Int, currentTimeMs: Long): SpeakerResult {
        val features = extractFeatures(samples, bufferSize)

        // Build baseline from first ~2s of audio
        if (baselineFeatures == null) {
            if (baselineWindowCount < baselineMaxWindows) {
                baselineWindowCount++
                if (baselineWindowCount == 1) {
                    baselineFeatures = FloatArray(features.size)
                }
                val base = baselineFeatures!!
                for (i in features.indices) {
                    base[i] = base[i] + (features[i] - base[i]) / baselineWindowCount
                }
            }
            currentSegmentFeatures.add(features)
            return SpeakerResult(speakerId = 0, energy = features[0], changed = false)
        }

        currentSegmentFeatures.add(features)

        // Check if we should detect a speaker change
        if (currentTimeMs - lastChangeTime >= minSegmentDurationMs) {
            val shouldChange = detectChange(features)

            if (shouldChange) {
                // Finalize current segment
                val segmentFeatures = currentSegmentFeatures.toList()
                currentSegmentFeatures.clear()
                currentSegmentFeatures.add(features)

                // Match against known speakers
                val matchedSpeaker = matchSegment(segmentFeatures)

                if (matchedSpeaker >= 0) {
                    // Known speaker returns
                    currentSpeakerId = matchedSpeaker
                    lastChangeTime = currentTimeMs
                    return SpeakerResult(
                        speakerId = currentSpeakerId,
                        energy = features[0],
                        changed = true
                    )
                } else {
                    // New speaker
                    val newId = knownSpeakers.size
                    val profile = buildProfile(segmentFeatures, newId)
                    knownSpeakers.add(profile)
                    currentSpeakerId = newId
                    lastChangeTime = currentTimeMs
                    return SpeakerResult(
                        speakerId = currentSpeakerId,
                        energy = features[0],
                        changed = true
                    )
                }
            }
        }

        if (currentSpeakerId < 0) {
            currentSpeakerId = 0
        }

        return SpeakerResult(
            speakerId = currentSpeakerId,
            energy = features[0],
            changed = false
        )
    }

    private fun matchSegment(segmentFeatures: List<FloatArray>): Int {
        if (knownSpeakers.isEmpty() || segmentFeatures.size < 3) return -1

        val segmentCentroid = computeCentroid(segmentFeatures)
        val segmentVariance = computeVariance(segmentFeatures, segmentCentroid)

        var bestMatch = -1
        var bestScore = Float.MAX_VALUE

        for (profile in knownSpeakers) {
            val distance = featureDistance(segmentCentroid, profile.featureCentroid, segmentVariance, profile.featureVariance)
            if (distance < bestScore) {
                bestScore = distance
                bestMatch = profile.id
            }
        }

        // Accept match only if distance is below threshold (more lenient)
        val threshold = sensitivity * 3.5f  // More lenient matching (was 2.5f)
        return if (bestScore < threshold) bestMatch else -1
    }

    private fun buildProfile(features: List<FloatArray>, id: Int): SpeakerProfile {
        val centroid = computeCentroid(features)
        val variance = computeVariance(features, centroid)
        return SpeakerProfile(id = id, featureCentroid = centroid, featureVariance = variance, matchCount = 1)
    }

    private fun computeCentroid(features: List<FloatArray>): FloatArray {
        val n = features[0].size
        val centroid = FloatArray(n)
        for (f in features) {
            for (i in 0 until n) {
                centroid[i] = centroid[i] + f[i]
            }
        }
        for (i in 0 until n) {
            centroid[i] = centroid[i] / features.size
        }
        return centroid
    }

    private fun computeVariance(features: List<FloatArray>, centroid: FloatArray): FloatArray {
        val n = centroid.size
        val variance = FloatArray(n)
        for (f in features) {
            for (i in 0 until n) {
                variance[i] = variance[i] + (f[i] - centroid[i]) * (f[i] - centroid[i])
            }
        }
        for (i in 0 until n) {
            variance[i] = variance[i] / features.size
        }
        return variance
    }

    private fun featureDistance(
        c1: FloatArray, c2: FloatArray,
        v1: FloatArray, v2: FloatArray
    ): Float {
        var dist = 0f
        val n = c1.size
        for (i in 0 until n) {
            val diff = c1[i] - c2[i]
            val avgVar = (v1[i] + v2[i]) / 2f + 0.0001f
            dist += (diff * diff) / avgVar
        }
        return sqrt(dist / n)
    }

    private fun extractFeatures(samples: ShortArray, size: Int): FloatArray {
        val n = size.coerceAtMost(samples.size)

        // RMS Energy
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / n).toFloat()

        // Zero Crossing Rate
        var zcr = 0
        for (i in 1 until n) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) {
                zcr++
            }
        }
        val zcrNorm = zcr.toFloat() / n

        // Pitch proxy via autocorrelation
        var maxCorr = 0f
        var bestLag = 1
        val maxLag = (n / 4).coerceAtMost(1000)
        for (lag in 1 until maxLag) {
            var corr = 0f
            var count = 0
            var i = 0
            while (i + lag < n) {
                corr += samples[i].toFloat() * samples[i + lag].toFloat()
                count++
                i++
            }
            if (count > 0) {
                corr /= count
                if (corr > maxCorr) {
                    maxCorr = corr
                    bestLag = lag
                }
            }
        }
        val pitchProxy = bestLag.toFloat() / maxLag

        // Energy variance (speaking dynamics)
        var energyVariance = 0f
        val chunkSize = n / 8
        if (chunkSize > 0) {
            val chunkEnergies = FloatArray(8)
            for (c in 0 until 8) {
                var chunkSq = 0.0
                val start = c * chunkSize
                val end = (start + chunkSize).coerceAtMost(n)
                for (i in start until end) {
                    val s = samples[i].toDouble()
                    chunkSq += s * s
                }
                chunkEnergies[c] = sqrt(chunkSq / (end - start)).toFloat()
            }
            val mean = chunkEnergies.average().toFloat()
            for (e in chunkEnergies) {
                energyVariance += (e - mean) * (e - mean)
            }
            energyVariance /= 8
        }

        // Spectral flatness (voice vs noise indicator)
        var logSum = 0.0
        var linSum = 0.0
        for (i in 0 until n) {
            val s = abs(samples[i].toDouble()) + 1.0
            logSum += kotlin.math.ln(s)
            linSum += s
        }
        val spectralFlatness = if (linSum > 0) {
            (kotlin.math.exp(logSum / n) / (linSum / n)).toFloat()
        } else 0f

        return floatArrayOf(rms, zcrNorm, pitchProxy, energyVariance, spectralFlatness)
    }

    private fun detectChange(currentFeatures: FloatArray): Boolean {
        if (currentSegmentFeatures.size < 3) return false  // Faster start (was 5)

        val recentFeatures = currentSegmentFeatures.takeLast(8).toTypedArray()  // Shorter window (was 15)
        val featureCount = currentFeatures.size
        val means = FloatArray(featureCount)
        val variances = FloatArray(featureCount)

        for (f in recentFeatures) {
            for (i in 0 until featureCount) {
                means[i] = means[i] + f[i]
            }
        }
        for (i in 0 until featureCount) {
            means[i] = means[i] / recentFeatures.size
        }
        for (f in recentFeatures) {
            for (i in 0 until featureCount) {
                variances[i] = variances[i] + (f[i] - means[i]) * (f[i] - means[i])
            }
        }
        for (i in 0 until featureCount) {
            variances[i] = variances[i] / recentFeatures.size
        }

        var deviationScore = 0f
        for (i in 0 until featureCount) {
            val std = sqrt(variances[i].coerceAtLeast(0.0001f))
            val deviation = abs(currentFeatures[i] - means[i]) / std
            deviationScore += deviation
        }

        // More sensitive change detection
        return (deviationScore / featureCount) > (sensitivity * 0.7f)
    }

    fun getSpeakerCount(): Int = knownSpeakers.size.coerceAtLeast(1)

    fun reset() {
        lastChangeTime = 0L
        currentSpeakerId = -1
        knownSpeakers.clear()
        currentSegmentFeatures.clear()
        baselineFeatures = null
        baselineWindowCount = 0
    }
}
