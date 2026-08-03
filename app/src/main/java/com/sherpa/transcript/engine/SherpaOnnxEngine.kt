package com.sherpa.transcript.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Ergebnis aus der ASR-Pipeline.
 * @param text   transkribierter Text
 * @param isFinal true = abgeschlossenes Segment (Endpoint erreicht)
 */
data class TranscriptionResult(
    val text: String,
    val isFinal: Boolean,
)

/**
 * Phase-1-Engine: Streaming-ASR mit Sherpa-ONNX OnlineRecognizer.
 *
 * Nutzt die eingebaute Endpunkt-Erkennung für Segment-Grenzen.
 * VAD wird in Phase 2+ für verbesserte Segmentierung ergänzt.
 *
 * Aufruf:
 *   val engine = SherpaOnnxEngine(context)
 *   engine.initialize()
 *   engine.startSession()
 *   engine.processFrame(floatArray)  // → TranscriptionResult?
 *   engine.stopSession()
 *   engine.release()
 */
class SherpaOnnxEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaOnnxEngine"
        const val SAMPLE_RATE = 16000
        private const val DEFAULT_MODEL = "kroko-de"
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var sessionActive = false
    private var lastPartialText: String = ""

    val isInitialized: Boolean get() = recognizer != null

    init {
        System.loadLibrary("sherpa-onnx-jni")
    }

    /**
     * Initialisiert die Engine mit dem Kroko-Modell.
     */
    fun initialize(modelName: String = DEFAULT_MODEL) {
        Log.i(TAG, "Initializing model: $modelName")

        val modelBase = context.filesDir.resolve("models/sherpa/$modelName").absolutePath
        val encoderPath = "$modelBase/encoder.onnx"
        val decoderPath = "$modelBase/decoder.onnx"
        val joinerPath = "$modelBase/joiner.onnx"
        val tokensPath = "$modelBase/tokens.txt"

        val missingFiles = listOf(
            "encoder.onnx" to encoderPath,
            "decoder.onnx" to decoderPath,
            "joiner.onnx" to joinerPath,
            "tokens.txt" to tokensPath,
        ).filter { !File(it.second).exists() }

        if (missingFiles.isNotEmpty()) {
            val details = missingFiles.joinToString(", ") { "${it.first} (${it.second})" }
            Log.w(TAG, "Model files missing at $modelBase: $details — download needed")
            return
        }

        Log.i(TAG, "Model files found, creating online recognizer")

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoderPath,
                decoder = decoderPath,
                joiner = joinerPath,
            ),
            tokens = tokensPath,
            numThreads = 2,
            provider = "cpu",
            debug = false,
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80,
                dither = 0.0f,
            ),
            modelConfig = modelConfig,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 0.4f,
                    minUtteranceLength = 0.0f,
                ),
                rule2 = EndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = 0.25f,
                    minUtteranceLength = 0.0f,
                ),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )

        recognizer = OnlineRecognizer(
            assetManager = null,
            config = config,
        )

        Log.i(TAG, "SherpaOnnxEngine initialized successfully")
    }

    /**
     * Startet eine neue Transkriptions-Session.
     */
    fun startSession() {
        stream?.release()
        stream = recognizer?.createStream()
        sessionActive = true
        lastPartialText = ""
        Log.i(TAG, "Session started, partial state reset")
    }

    /**
     * Verarbeitet einen Float-PCM-Frame (normiert auf -1..1, 16kHz).
     *
     * @param pcm FloatArray mit normierten Samples
     * @return TranscriptionResult, wenn ein Segment abgeschlossen ist
     */
    fun processFrame(pcm: FloatArray): TranscriptionResult? {
        if (!sessionActive) return null
        val rec = recognizer ?: return null
        val s = stream ?: return null

        // PCM an den Recognizer übergeben
        s.acceptWaveform(pcm, SAMPLE_RATE)

        // Solange decodieren, bis nichts mehr zu tun ist
        while (rec.isReady(s)) {
            rec.decode(s)
        }

        val isEndpoint = rec.isEndpoint(s)
        val text = rec.getResult(s).text.trim()

        return if (text.isNotEmpty()) {
            if (isEndpoint) {
                // Segment abgeschlossen: zurücksetzen für nächstes
                rec.reset(s)
                lastPartialText = ""
                TranscriptionResult(text = text, isFinal = true)
            } else {
                // Identischen Partial-Text nicht dauernd neu emittieren
                if (text == lastPartialText) {
                    null
                } else {
                    lastPartialText = text
                    TranscriptionResult(text = text, isFinal = false)
                }
            }
        } else {
            null
        }
    }

    /**
     * Beendet die Session.
     */
    fun stopSession() {
        stream?.release()
        stream = null
        sessionActive = false
        Log.i(TAG, "Session stopped")
    }

    fun release() {
        stopSession()
        recognizer?.release()
        recognizer = null
        Log.i(TAG, "Engine released")
    }
}
