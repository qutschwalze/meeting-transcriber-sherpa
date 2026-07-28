package com.qutschwalze.meetingtranscriber

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule

/**
 * Sherpa-ONNX Streaming ASR Engine.
 * Wraps OnlineRecognizer with streaming Zipformer transducer models.
 * Audio input: FloatArray at 16kHz sample rate.
 */
class SherpaEngine(
    private val encoderPath: String,
    private val decoderPath: String,
    private val joinerPath: String,
    private val tokensPath: String
) {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    /**
     * Convenience constructor from model directory — looks for standard filenames.
     */
    constructor(modelDir: String) : this(
        encoderPath = findFile(modelDir, listOf("encoder.onnx", "encoder.int8.onnx")),
        decoderPath = findFile(modelDir, listOf("decoder.onnx")),
        joinerPath = findFile(modelDir, listOf("joiner.onnx", "joiner.int8.onnx")),
        tokensPath = "$modelDir/tokens.txt"
    )

    fun init(): Boolean {
        val transducerConfig = OnlineTransducerModelConfig(
            encoder = encoderPath,
            decoder = decoderPath,
            joiner = joinerPath,
        )

        val modelConfig = OnlineModelConfig(
            transducer = transducerConfig,
            tokens = tokensPath,
            numThreads = 2,
        )

        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0.0f),
            rule2 = EndpointRule(true, 1.4f, 0.0f),
            rule3 = EndpointRule(false, 0.0f, 20.0f),
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )

        return try {
            recognizer = OnlineRecognizer(config = config)
            stream = recognizer!!.createStream()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int = 16000) {
        stream?.acceptWaveform(samples, sampleRate)
    }

    fun isReady(): Boolean {
        val r = recognizer ?: return false
        val s = stream ?: return false
        return r.isReady(s)
    }

    fun decode() {
        recognizer?.decode(stream!!)
    }

    fun isEndpoint(): Boolean {
        val r = recognizer ?: return false
        val s = stream ?: return false
        return r.isEndpoint(s)
    }

    fun getResult(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        return r.getResult(s).text
    }

    fun reset() {
        recognizer?.reset(stream!!)
    }

    fun release() {
        stream?.release()
        recognizer?.release()
        stream = null
        recognizer = null
    }

    companion object {
        private fun findFile(dir: String, candidates: List<String>): String {
            for (name in candidates) {
                val f = java.io.File(dir, name)
                if (f.exists()) return f.absolutePath
            }
            // Fallback: list directory and find first match
            val d = java.io.File(dir)
            if (d.exists()) {
                for (name in candidates) {
                    val match = d.listFiles()?.find { it.name == name || it.name.contains(name.removeSuffix(".onnx")) }
                    if (match != null) return match.absolutePath
                }
            }
            throw IllegalArgumentException("No matching file in $dir for: $candidates")
        }
    }
}
