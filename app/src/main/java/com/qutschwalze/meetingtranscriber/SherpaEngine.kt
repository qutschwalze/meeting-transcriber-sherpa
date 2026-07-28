package com.qutschwalze.meetingtranscriber

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import java.io.File

/**
 * Sherpa-ONNX Streaming ASR Engine.
 * Wraps OnlineRecognizer with streaming Zipformer transducer models.
 * Audio input: FloatArray at 16kHz sample rate.
 */
class SherpaEngine(
    private val modelDir: String,
    private val modelType: String = "zipformer"
) {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    /** Create recognizer from downloaded model files on disk */
    fun init(): Boolean {
        val encoder = File(modelDir, "encoder.onnx")
        val decoder = File(modelDir, "decoder.onnx")
        val joiner = File(modelDir, "joiner.onnx")
        val tokens = File(modelDir, "tokens.txt")

        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            // Try int8 variants
            val encoderInt8 = File(modelDir, "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx")
            val decoderOnnx = File(modelDir, "decoder-epoch-99-avg-1-chunk-16-left-128.onnx")
            val joinerInt8 = File(modelDir, "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx")
            val tokensTxt = File(modelDir, "tokens.txt")

            if (!encoderInt8.exists() || !decoderOnnx.exists() || !joinerInt8.exists() || !tokensTxt.exists()) {
                return false
            }

            return initFromFiles(encoderInt8.absolutePath, decoderOnnx.absolutePath, joinerInt8.absolutePath, tokensTxt.absolutePath)
        }

        return initFromFiles(encoder.absolutePath, decoder.absolutePath, joiner.absolutePath, tokens.absolutePath)
    }

    /** Create recognizer from explicit file paths */
    fun initFromFiles(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String
    ): Boolean {
        val transducerConfig = OnlineTransducerModelConfig(
            encoder = encoderPath,
            decoder = decoderPath,
            joiner = joinerPath,
        )

        val modelConfig = OnlineModelConfig(
            transducer = transducerConfig,
            tokens = tokensPath,
            numThreads = 2,
            modelType = modelType,
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
            false
        }
    }

    /** Feed audio samples (FloatArray, 16kHz) into the stream */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int = 16000) {
        stream?.acceptWaveform(samples, sampleRate)
    }

    /** Check if the recognizer is ready to decode */
    fun isReady(): Boolean {
        val r = recognizer ?: return false
        val s = stream ?: return false
        return r.isReady(s)
    }

    /** Decode one chunk */
    fun decode() {
        val r = recognizer ?: return
        val s = stream ?: return
        r.decode(s)
    }

    /** Check if an endpoint (end of utterance) was detected */
    fun isEndpoint(): Boolean {
        val r = recognizer ?: return false
        val s = stream ?: return false
        return r.isEndpoint(s)
    }

    /** Get the current partial/final result text */
    fun getResult(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        return r.getResult(s).text
    }

    /** Reset the stream after an endpoint (start new utterance) */
    fun reset() {
        val r = recognizer ?: return
        val s = stream ?: return
        r.reset(s)
    }

    /** Release resources */
    fun release() {
        stream?.release()
        recognizer?.release()
        stream = null
        recognizer = null
    }
}
