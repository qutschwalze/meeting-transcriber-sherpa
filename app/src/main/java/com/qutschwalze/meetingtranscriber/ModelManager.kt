package com.qutschwalze.meetingtranscriber

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages Sherpa-ONNX model downloads and storage.
 * Models are downloaded from HuggingFace as ZIP files and stored in context.filesDir/models/
 */
object ModelManager {
    private const val TAG = "ModelManager"

    /**
     * Sherpa-ONNX streaming Zipformer models from HuggingFace.
     * Each model contains: encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt
     */
    data class ModelInfo(
        val name: String,
        val displayName: String,
        val url: String,
        val zipName: String,
        val type: String = "zipformer"
    )

    val models = mapOf(
        "de" to ModelInfo(
            name = "sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
            displayName = "Deutsch (Streaming Zipformer)",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06/resolve/main/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06.tar.bz2",
            zipName = "sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06.tar.bz2"
        ),
        "en" to ModelInfo(
            name = "sherpa-onnx-streaming-zipformer-en-2023-06-26",
            displayName = "English (Streaming Zipformer)",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
            zipName = "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
        ),
        "fr" to ModelInfo(
            name = "sherpa-onnx-streaming-zipformer-fr-2023-04-14",
            displayName = "Français (Streaming Zipformer)",
            url = "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/main/sherpa-onnx-streaming-zipformer-fr-2023-04-14.tar.bz2",
            zipName = "sherpa-onnx-streaming-zipformer-fr-2023-04-14.tar.bz2"
        ),
        "es" to ModelInfo(
            name = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
            displayName = "Español (Streaming Zipformer)",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06/resolve/main/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2",
            zipName = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2"
        ),
        "it" to ModelInfo(
            name = "sherpa-onnx-streaming-zipformer-it-2024-06-16",
            displayName = "Italiano (Streaming Zipformer)",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-it-2024-06-16/resolve/main/sherpa-onnx-streaming-zipformer-it-2024-06-16.tar.bz2",
            zipName = "sherpa-onnx-streaming-zipformer-it-2024-06-16.tar.bz2"
        ),
        "ru" to ModelInfo(
            name = "sherpa-onnx-streaming-zipformer-ru-2024-06-16",
            displayName = "Русский (Streaming Zipformer)",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ru-2024-06-16/resolve/main/sherpa-onnx-streaming-zipformer-ru-2024-06-16.tar.bz2",
            zipName = "sherpa-onnx-streaming-zipformer-ru-2024-06-16.tar.bz2"
        ),
        "pt" to ModelInfo(
            name = "sherpa-onnx-streaming-zipformer-pt-2024-06-16",
            displayName = "Português (Streaming Zipformer)",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-pt-2024-06-16/resolve/main/sherpa-onnx-streaming-zipformer-pt-2024-06-16.tar.bz2",
            zipName = "sherpa-onnx-streaming-zipformer-pt-2024-06-16.tar.bz2"
        ),
        "nl" to ModelInfo(
            name = "sherpa-onnx-streaming-zipformer-nl-2024-06-16",
            displayName = "Nederlands (Streaming Zipformer)",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-nl-2024-06-16/resolve/main/sherpa-onnx-streaming-zipformer-nl-2024-06-16.tar.bz2",
            zipName = "sherpa-onnx-streaming-zipformer-nl-2024-06-16.tar.bz2"
        )
    )

    fun getModelDir(context: Context): File {
        return File(context.filesDir, "models")
    }

    fun isModelAvailable(context: Context, langCode: String): Boolean {
        val info = models[langCode] ?: return false
        val modelDir = File(getModelDir(context), info.name)
        return modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true
    }

    fun getModelPath(context: Context, langCode: String): String? {
        val info = models[langCode] ?: return null
        val modelDir = File(getModelDir(context), info.name)
        return if (modelDir.exists()) modelDir.absolutePath else null
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
        val modelDir = File(modelsDir, info.name)

        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            onProgress("Modell bereits vorhanden")
            return modelDir
        }

        val downloadFile = File(modelsDir, info.zipName)

        try {
            modelsDir.mkdirs()
            onProgress("Download: ${info.name}…")

            val connection = URL(info.url).openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            val totalSize = connection.contentLength

            BufferedInputStream(connection.getInputStream()).use { input ->
                FileOutputStream(downloadFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalSize > 0) {
                            val progress = (totalRead * 100 / totalSize).toInt()
                            onProgress("Download: $progress%")
                        }
                    }
                }
            }

            onProgress("Entpacke Modell…")
            when {
                info.zipName.endsWith(".tar.bz2") -> untarBz2(downloadFile, modelsDir)
                info.zipName.endsWith(".zip") -> unzip(downloadFile, modelsDir)
                else -> unzip(downloadFile, modelsDir)
            }
            downloadFile.delete()

            if (modelDir.exists()) {
                onProgress("Modell bereit")
                return modelDir
            }

        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            onProgress("Download fehlgeschlagen: ${e.message}")
            downloadFile.delete()
        }

        return null
    }

    private fun untarBz2(archiveFile: File, targetDir: File) {
        // Use system tar to extract .tar.bz2
        val process = ProcessBuilder(
            "tar", "xjf", archiveFile.absolutePath, "-C", targetDir.absolutePath
        ).start()
        process.waitFor()
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        zip.copyTo(fos)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
