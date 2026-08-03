package com.sherpa.transcript.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lädt Sherpa-ONNX-Modelle beim ersten App-Start von HuggingFace herunter.
 *
 * Standard-Modell: "Kroko" Zipformer-Transducer für Deutsch,
 * entwickelt von Banafo (https://huggingface.co/spaces/Banafo/Kroko-Streaming-ASR-Wasm).
 *
 * Modell: csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06
 * - encoder.onnx (52 MB)
 * - decoder.onnx (4.7 MB)
 * - joiner.onnx (1.0 MB)
 * - tokens.txt (9 KB)
 * Gesamt: ~58 MB
 */
object ModelDownloadManager {
    private const val TAG = "ModelDownload"

    data class ModelSpec(
        val name: String,
        val hfRepo: String,
        val files: List<String>,
        val estimatedSizeMb: Int,
    )

    val AVAILABLE_MODELS = mapOf(
        "kroko-de" to ModelSpec(
            name = "Kroko Deutsch (Zipformer-Transducer)",
            hfRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
            files = listOf(
                "encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt",
            ),
            estimatedSizeMb = 58,
        ),
        "whisper-small-de" to ModelSpec(
            name = "Whisper Small DE",
            hfRepo = "csukuangfj/sherpa-onnx-whisper-small-de",
            files = listOf(
                "encoder.onnx", "decoder.onnx", "tokens.txt",
            ),
            estimatedSizeMb = 461,
        ),
    )

    /**
     * Lädt ein Modell von HuggingFace herunter.
     * @return true bei Erfolg, false bei Fehler
     */
    suspend fun downloadModel(
        context: Context,
        modelName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val spec = AVAILABLE_MODELS[modelName]
            ?: return@withContext false

        val modelDir = context.filesDir.resolve("models/sherpa/$modelName")
        modelDir.mkdirs()

        Log.i(TAG, "Downloading ${spec.name} to $modelDir")

        for (fileName in spec.files) {
            val fileUrl = "https://huggingface.co/${spec.hfRepo}/resolve/main/$fileName"
            val targetFile = modelDir.resolve(fileName)

            if (targetFile.exists() && targetFile.length() > 0) {
                Log.i(TAG, "$fileName already exists (${targetFile.length() / 1024} KB), skipping")
                continue
            }

            try {
                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                // HuggingFace braucht User-Agent
                connection.setRequestProperty("User-Agent", "SherpaTranscript/0.1")
                connection.connect()

                val totalBytes = connection.contentLengthLong
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    onProgress(totalRead, totalBytes)
                }

                outputStream.close()
                inputStream.close()
                Log.i(TAG, "Downloaded $fileName (${totalRead / 1024} KB)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download $fileName: ${e.message}")
                targetFile.delete()
                return@withContext false
            }
        }

        Log.i(TAG, "${spec.name} download complete")
        true
    }

    fun isModelDownloaded(context: Context, modelName: String): Boolean {
        val spec = AVAILABLE_MODELS[modelName] ?: return false
        val modelDir = context.filesDir.resolve("models/sherpa/$modelName")
        if (!modelDir.exists()) return false
        return spec.files.all { modelDir.resolve(it).exists() }
    }

    fun getModelSizeMb(modelName: String): Int =
        AVAILABLE_MODELS[modelName]?.estimatedSizeMb ?: 0
}
