package com.qutschwalze.meetingtranscriber

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Wraps Sherpa-ONNX native OfflineSpeakerDiarization.
 * Uses Pyannote segmentation + 3dspeaker embedding models.
 *
 * Workflow:
 * 1. init() — copy models from assets to internal storage
 * 2. process(samples) — returns segments with speaker IDs and timestamps
 * 3. release() — free native resources
 */
class DiarizationManager(private val context: Context) {

    private var diarizer: OfflineSpeakerDiarization? = null
    private var initialized = false

    /**
     * Initialize diarization models.
     * Copies models from assets to internal storage (required for native ONNX).
     * Call this once at app start.
     */
    fun init(): Boolean {
        if (initialized) return true

        return try {
            val segModel = copyAssetToInternal("models/pyannote-segmentation.int8.onnx")
            val embModel = copyAssetToInternal("models/speaker-embedding.onnx")

            if (segModel == null || embModel == null) {
                return false
            }

            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = segModel.absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = embModel.absolutePath,
                    numThreads = 2,
                    debug = false,
                ),
                clustering = FastClusteringConfig(
                    numClusters = -1,  // Auto-detect number of speakers
                    threshold = 0.5f,
                ),
                minDurationOn = 0.3f,   // Min speech duration (ignore short bursts)
                minDurationOff = 0.5f,  // Min silence between segments (merge nearby)
            )

            diarizer = OfflineSpeakerDiarization(config = config)
            initialized = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Process audio samples and return speaker segments.
     * Input: FloatArray at 16kHz mono (-1.0 to 1.0).
     * Output: List of segments with start/end times and speaker IDs.
     */
    fun process(samples: FloatArray): List<OfflineSpeakerDiarizationSegment> {
        val d = diarizer ?: return emptyList()
        return try {
            d.process(samples).toList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun release() {
        diarizer?.release()
        diarizer = null
        initialized = false
    }

    private fun copyAssetToInternal(assetPath: String): File? {
        val file = File(context.filesDir, assetPath)
        if (file.exists() && file.length() > 0) return file

        return try {
            file.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
