package com.sherpa.transcript.engine

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Testmodi für das Clustering der Speaker-Diarization.
 * DEFAULT_AUTO ist der Produktivmodus.
 */
enum class DiarizationClusteringMode(
    val numClusters: Int,
    val threshold: Float,
    val modeName: String,
) {
    DEFAULT_AUTO(numClusters = -1, threshold = 0.3f, modeName = "DEFAULT_AUTO"),
    FIXED_2(numClusters = 2, threshold = 0.3f, modeName = "FIXED_2"),
    FIXED_4(numClusters = 4, threshold = 0.3f, modeName = "FIXED_4"),
    AUTO_LOWER_THRESHOLD(numClusters = -1, threshold = 0.25f, modeName = "AUTO_LOWER_THRESHOLD"),
    AUTO_HIGHER_THRESHOLD(numClusters = -1, threshold = 0.35f, modeName = "AUTO_HIGHER_THRESHOLD"),
}

data class DiarizationSegment(
    val startSec: Float,
    val endSec: Float,
    val speaker: Int,
)

/**
 * Offizielle Sherpa-ONNX Speaker-Diarization mit pyannote + NeMo Titanet.
 * Wie im offiziellen APK: sherpa-onnx-...-pyannote_audio-nemo.apk
 */
class SpeakerDiarizationEngine(private val context: Context, private val assetManager: AssetManager? = null) {

    companion object {
        private const val TAG = "SpeakerDiarization"
        private const val SEGMENTATION_MODEL = "segmentation.onnx"
        private const val EMBEDDING_MODEL = "embedding.onnx"
    }

    /** Aktueller Clustering-Modus – über setClusteringMode() änderbar */
    var clusteringMode: DiarizationClusteringMode = DiarizationClusteringMode.DEFAULT_AUTO
        private set

    private var diarization: OfflineSpeakerDiarization? = null

    /** Session-Zähler: wie oft lieferte die Engine 0 Segmente? (für Recall-Tracking) */
    var zeroSegmentCount: Int = 0
        private set
    var engineOrThresholdCount: Int = 0
        private set

    /** Setzt die Session-Zähler zurück (Aufruf bei startRecording). */
    fun resetZeroSegmentCounters() {
        zeroSegmentCount = 0
        engineOrThresholdCount = 0
    }

    val isInitialized: Boolean get() = diarization != null

    /**
     * Initialisiert die Engine. Bei [mode] != null wird der angegebene
     * Clustering-Modus verwendet, sonst DEFAULT_AUTO.
     */
    fun initialize(mode: DiarizationClusteringMode = DiarizationClusteringMode.DEFAULT_AUTO) {
        clusteringMode = mode
        Log.i(TAG, "Initializing: mode=${mode.modeName} numClusters=${mode.numClusters} threshold=${mode.threshold}")

        val clustering = FastClusteringConfig(numClusters = mode.numClusters, threshold = mode.threshold)

        if (assetManager != null) {
            // Modelle aus APK-Assets laden (wie Demo-App)
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(SEGMENTATION_MODEL),
                    debug = false,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = EMBEDDING_MODEL,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                clustering = clustering,
                minDurationOn = 0.1f,
                minDurationOff = 0.05f,
            )
            diarization = OfflineSpeakerDiarization(assetManager = assetManager, config = config)
            Log.i(TAG, "Initialized from assets: mode=${mode.modeName} numClusters=${mode.numClusters} threshold=${mode.threshold} minOn=0.1 minOff=0.05")
            return
        }

        // Fallback: Modelle aus Dateisystem laden
        val modelDir = context.filesDir.resolve("models/speaker")
        val segPath = modelDir.resolve(SEGMENTATION_MODEL).absolutePath
        val embPath = modelDir.resolve(EMBEDDING_MODEL).absolutePath

        if (!File(segPath).exists() || !File(embPath).exists()) {
            Log.w(TAG, "Models missing (seg=${File(segPath).exists()}, emb=${File(embPath).exists()})")
            return
        }

        Log.i(TAG, "seg onnx: ${File(segPath).length() / 1024} KB, emb onnx: ${File(embPath).length() / 1024} KB")

        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segPath),
                debug = false,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embPath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            clustering = clustering,
            minDurationOn = 0.1f,
            minDurationOff = 0.05f,
        )

        diarization = OfflineSpeakerDiarization(assetManager = null, config = config)
        Log.i(TAG, "Initialized from files: mode=${mode.modeName} numClusters=${mode.numClusters} threshold=${mode.threshold} minOn=0.1 minOff=0.05")
    }

    /** Wechselt den Clustering-Modus zur Laufzeit (re-initialisiert die Engine). */
    fun setClusteringMode(mode: DiarizationClusteringMode) {
        if (mode == clusteringMode && isInitialized) return
        release()
        initialize(mode)
    }

    fun process(samples: FloatArray): List<DiarizationSegment> {
        val sd = diarization ?: return emptyList()
        if (samples.size < 16000) {
            Log.w(TAG, "process: too few samples: ${samples.size} (< 16000)")
            return emptyList()
        }
        return try {
            val result = sd.process(samples)
            val durationSec = samples.size / 16000f
            if (result.isEmpty()) {
                zeroSegmentCount++
                // Klassifikation: < 5s Audio → erwartbar zu kurz (pyannote braucht Kontext);
                // >= 5s → echtes Engine/Threshold-Problem
                val cause = when {
                    durationSec < 5f -> "bufferTooShort"
                    durationSec < 8f -> "borderline"
                    else -> "engineOrThreshold"
                }
                if (cause == "engineOrThreshold") engineOrThresholdCount++
                Log.w(TAG, "process: 0 segments from engine (audio=${
                    "%.1f".format(durationSec)
                }s, cause=$cause, numClusters=${clusteringMode.numClusters}, threshold=${clusteringMode.threshold}, minOn=0.1, minOff=0.05)")
            } else {
                val speakerIds = result.map { it.speaker }.distinct().sorted()
                val minDur = result.minOf { it.end - it.start }
                val maxDur = result.maxOf { it.end - it.start }
                val avgDur = result.map { it.end - it.start }.let { segs ->
                    if (segs.isEmpty()) 0f else segs.sum() / segs.size
                }
                Log.d(TAG, "process: ${result.size} segments, ${speakerIds.size} speakers " +
                        "speakers=${speakerIds.joinToString(",")} " +
                        "audio=${"%.1f".format(durationSec)}s " +
                        "dur=[${"%.1f".format(minDur)}..${"%.1f".format(avgDur)}..${"%.1f".format(maxDur)}]s")
            }
            result.map { DiarizationSegment(it.start, it.end, it.speaker) }
        } catch (t: Throwable) {
            Log.e(TAG, "process() threw: ${t.message}")
            emptyList()
        }
    }

    fun release() { diarization?.release(); diarization = null }
}
