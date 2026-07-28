package com.qutschwalze.meetingtranscriber

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Sherpa-ONNX model downloads from HuggingFace.
 * Each model consists of 4 files: encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt
 * Downloaded individually — no archive extraction needed.
 */
object ModelManager {
    private const val TAG = "ModelManager"

    data class ModelFiles(
        val dirName: String,
        val displayName: String,
        val baseUrl: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String
    )

    val models = mapOf(
        "de" to ModelFiles(
            dirName = "sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
            displayName = "Deutsch",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06/resolve/main",
            encoder = "encoder.onnx",
            decoder = "decoder.onnx",
            joiner = "joiner.onnx",
            tokens = "tokens.txt"
        ),
        "en" to ModelFiles(
            dirName = "sherpa-onnx-streaming-zipformer-en-2023-06-26",
            displayName = "English",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main",
            encoder = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            decoder = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
            joiner = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            tokens = "tokens.txt"
        ),
        "fr" to ModelFiles(
            dirName = "sherpa-onnx-streaming-zipformer-fr-2023-04-14",
            displayName = "Français",
            baseUrl = "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/main",
            encoder = "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
            decoder = "decoder-epoch-29-avg-9-with-averaged-model.onnx",
            joiner = "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
            tokens = "tokens.txt"
        ),
        "es" to ModelFiles(
            dirName = "sherpa-onnx-streaming-zipformer-es-2024-06-16",
            displayName = "Español",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-es-2024-06-16/resolve/main",
            encoder = "encoder-epoch-99-avg-1.int8.onnx",
            decoder = "decoder-epoch-99-avg-1.onnx",
            joiner = "joiner-epoch-99-avg-1.int8.onnx",
            tokens = "tokens.txt"
        ),
        "it" to ModelFiles(
            dirName = "sherpa-onnx-streaming-zipformer-it-2024-06-16",
            displayName = "Italiano",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-it-2024-06-16/resolve/main",
            encoder = "encoder-epoch-99-avg-1.int8.onnx",
            decoder = "decoder-epoch-99-avg-1.onnx",
            joiner = "joiner-epoch-99-avg-1.int8.onnx",
            tokens = "tokens.txt"
        ),
        "ru" to ModelFiles(
            dirName = "sherpa-onnx-streaming-zipformer-ru-2024-06-16",
            displayName = "Русский",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ru-2024-06-16/resolve/main",
            encoder = "encoder-epoch-99-avg-1.int8.onnx",
            decoder = "decoder-epoch-99-avg-1.onnx",
            joiner = "joiner-epoch-99-avg-1.int8.onnx",
            tokens = "tokens.txt"
        ),
        "pt" to ModelFiles(
            dirName = "sherpa-onnx-streaming-zipformer-pt-2024-06-16",
            displayName = "Português",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-pt-2024-06-16/resolve/main",
            encoder = "encoder-epoch-99-avg-1.int8.onnx",
            decoder = "decoder-epoch-99-avg-1.onnx",
            joiner = "joiner-epoch-99-avg-1.int8.onnx",
            tokens = "tokens.txt"
        ),
        "nl" to ModelFiles(
            dirName = "sherpa-onnx-streaming-zipformer-nl-2024-06-16",
            displayName = "Nederlands",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-nl-2024-06-16/resolve/main",
            encoder = "encoder-epoch-99-avg-1.int8.onnx",
            decoder = "decoder-epoch-99-avg-1.onnx",
            joiner = "joiner-epoch-99-avg-1.int8.onnx",
            tokens = "tokens.txt"
        )
    )

    fun getModelDir(context: Context): File {
        return File(context.filesDir, "models")
    }

    fun isModelAvailable(context: Context, langCode: String): Boolean {
        val info = models[langCode] ?: return false
        val modelDir = File(getModelDir(context), info.dirName)
        if (!modelDir.exists()) return false
        // Check all 4 required files exist
        return File(modelDir, info.encoder).exists() &&
                File(modelDir, info.decoder).exists() &&
                File(modelDir, info.joiner).exists() &&
                File(modelDir, info.tokens).exists()
    }

    fun getModelPath(context: Context, langCode: String): String? {
        val info = models[langCode] ?: return null
        val modelDir = File(getModelDir(context), info.dirName)
        return if (isModelAvailable(context, langCode)) modelDir.absolutePath else null
    }

    /** Get exact file paths for creating a SherpaEngine */
    fun getModelFiles(context: Context, langCode: String): Triple<String, String, String>? {
        val info = models[langCode] ?: return null
        val modelDir = File(getModelDir(context), info.dirName)
        if (!isModelAvailable(context, langCode)) return null
        return Triple(
            File(modelDir, info.encoder).absolutePath,
            File(modelDir, info.decoder).absolutePath,
            File(modelDir, info.joiner).absolutePath
        )
    }

    /**
     * Download model files individually from HuggingFace.
     * No archive extraction — files are downloaded directly to the model directory.
     */
    fun downloadModel(
        context: Context,
        langCode: String,
        onProgress: (String) -> Unit = {}
    ): File? {
        val info = models[langCode] ?: run {
            onProgress("Unbekannte Sprache: $langCode")
            return null
        }

        val modelsDir = getModelDir(context)
        val modelDir = File(modelsDir, info.dirName)

        if (isModelAvailable(context, langCode)) {
            onProgress("Modell bereits vorhanden")
            return modelDir
        }

        try {
            modelDir.mkdirs()

            val files = listOf(
                info.encoder to "encoder",
                info.decoder to "decoder",
                info.joiner to "joiner",
                info.tokens to "tokens.txt"
            )

            for ((filename, label) in files) {
                val targetFile = File(modelDir, filename)
                if (targetFile.exists() && targetFile.length() > 0) {
                    continue // Skip already downloaded files
                }

                val fileUrl = "${info.baseUrl}/$filename"
                onProgress("Download: $label…")

                downloadFile(fileUrl, targetFile) { progress ->
                    onProgress("$label: $progress%")
                }

                if (!targetFile.exists() || targetFile.length() == 0L) {
                    throw Exception("Download fehlgeschlagen: $filename")
                }
            }

            onProgress("Modell bereit")
            return modelDir

        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${e.message}", e)
            onProgress("Download fehlgeschlagen: ${e.message}")
            // Clean up partial downloads
            modelDir.listFiles()?.forEach { it.delete() }
            modelDir.delete()
        }

        return null
    }

    private fun downloadFile(
        urlStr: String,
        targetFile: File,
        onProgress: (Int) -> Unit = {}
    ) {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.connect()

        if (connection.responseCode != 200) {
            throw Exception("HTTP ${connection.responseCode} for $urlStr")
        }

        val totalSize = connection.contentLength.toLong()

        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    if (totalSize > 0) {
                        val progress = (totalRead * 100 / totalSize).toInt()
                        onProgress(progress)
                    }
                }
            }
        }

        connection.disconnect()
    }
}
