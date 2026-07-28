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
 * Downloads individual files with retry logic.
 */
object ModelManager {
    private const val TAG = "ModelManager"
    private const val MAX_RETRIES = 3
    private const val CONNECT_TIMEOUT = 30000
    private const val READ_TIMEOUT = 180000  // 3 minutes per file

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
            dirName = "sherpa-onnx-de-kroko",
            displayName = "Deutsch",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06/resolve/main",
            encoder = "encoder.onnx",
            decoder = "decoder.onnx",
            joiner = "joiner.onnx",
            tokens = "tokens.txt"
        ),
        "en" to ModelFiles(
            dirName = "sherpa-onnx-en-2023",
            displayName = "English",
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main",
            encoder = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            decoder = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
            joiner = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            tokens = "tokens.txt"
        ),
        "fr" to ModelFiles(
            dirName = "sherpa-onnx-fr-2023",
            displayName = "Français",
            baseUrl = "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/main",
            encoder = "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
            decoder = "decoder-epoch-29-avg-9-with-averaged-model.onnx",
            joiner = "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
            tokens = "tokens.txt"
        )
    )

    fun getModelDir(context: Context): File = File(context.filesDir, "models")

    fun isModelAvailable(context: Context, langCode: String): Boolean {
        val info = models[langCode] ?: return false
        val modelDir = File(getModelDir(context), info.dirName)
        if (!modelDir.exists()) return false
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
                if (targetFile.exists() && targetFile.length() > 1000) {
                    Log.i(TAG, "Skipping $label (already downloaded)")
                    continue
                }

                val fileUrl = "${info.baseUrl}/$filename"
                var lastError: Exception? = null

                for (attempt in 1..MAX_RETRIES) {
                    try {
                        onProgress("Download $label (Versuch $attempt/$MAX_RETRIES)…")
                        Log.i(TAG, "Downloading $fileUrl (attempt $attempt)")

                        downloadFile(fileUrl, targetFile) { progress ->
                            onProgress("$label: $progress%")
                        }

                        if (targetFile.exists() && targetFile.length() > 0) {
                            Log.i(TAG, "Download OK: $label (${targetFile.length()} bytes)")
                            lastError = null
                            break
                        } else {
                            throw Exception("Datei leer nach Download")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Download attempt $attempt failed for $label: ${e.message}")
                        lastError = e
                        targetFile.delete()
                        if (attempt < MAX_RETRIES) {
                            Thread.sleep(1000L * attempt)  // Exponential backoff
                        }
                    }
                }

                if (lastError != null) {
                    throw Exception("Download fehlgeschlagen nach $MAX_RETRIES Versuchen: $label (${lastError.message})")
                }
            }

            onProgress("Modell bereit ✓")
            return modelDir

        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${e.message}", e)
            onProgress("Fehler: ${e.message}")
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
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("User-Agent", "MeetingTranscriber/2.0")
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
            connection.disconnect()
            throw Exception("HTTP $responseCode — $errorBody")
        }

        val totalSize = connection.contentLength.toLong()
        Log.i(TAG, "Starting download: $urlStr (${if (totalSize > 0) "${totalSize / 1024}KB" else "unknown size"})")

        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(32768)  // 32KB buffer
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

                output.flush()
                Log.i(TAG, "Download complete: ${targetFile.name} ($totalRead bytes)")
            }
        }

        connection.disconnect()
    }
}
