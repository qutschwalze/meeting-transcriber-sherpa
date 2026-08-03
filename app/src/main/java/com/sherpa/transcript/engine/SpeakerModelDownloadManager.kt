package com.sherpa.transcript.engine

import android.util.Log
import com.sherpa.transcript.SherpaTranscriptApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Lädt Speaker-Modelle herunter: pyannote segmentation + WESPEAKER.
 */
object SpeakerModelDownloadManager {
    private const val TAG = "SpeakerModelDownload"

    data class SpeakerModelSpec(
        val fileName: String,
        val url: String,
        val sizeMb: Int,
        val isTarBz2: Boolean = false,
    )

    val REQUIRED_MODELS = listOf(
        SpeakerModelSpec(
            fileName = "segmentation.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-reverb-diarization-v1.tar.bz2",
            sizeMb = 11,
            isTarBz2 = true,
        ),
        SpeakerModelSpec(
            fileName = "embedding.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx",
            sizeMb = 40,
        ),
    )

    /**
     * Lädt alle benötigten Modelle herunter.
     */
    suspend fun downloadModels(
        onProgress: (fileName: String, downloaded: Long, total: Long) -> Unit = { _, _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val modelDir = SherpaTranscriptApp.instance.filesDir.resolve("models/speaker")
        modelDir.mkdirs()

        // Alte ungenutzte Modelle löschen
        listOf("segmentation.onnx", "segmentation.onnx.tar.bz2").forEach { name ->
            modelDir.resolve(name)?.let { if (it.exists()) { it.delete(); Log.i(TAG, "Cleaned up $name") } }
        }

        // Alte Modelle löschen (falsche Architektur)
        listOf("embedding.onnx").forEach { name ->
            modelDir.resolve(name)?.let { f ->
                if (f.exists()) {
                    Log.i(TAG, "Deleting old embedding model for replacement")
                    f.delete()
                }
            }
        }

        for (spec in REQUIRED_MODELS) {
            val targetFile = modelDir.resolve(spec.fileName)
            val archiveFile = modelDir.resolve(spec.fileName + ".tar.bz2")

            // Altes Archiv löschen falls Ziel fehlt
            if (!targetFile.exists() && archiveFile.exists()) {
                archiveFile.delete()
                Log.i(TAG, "Deleted stale archive for ${spec.fileName}")
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                Log.i(TAG, "${spec.fileName} already exists (${targetFile.length() / 1024} KB)")
                continue
            }

            if (spec.isTarBz2) {
                if (!downloadFile(spec.url, archiveFile, spec.fileName, onProgress)) {
                    return@withContext false
                }
                if (!extractTarBz2(archiveFile, modelDir, spec.fileName)) {
                    return@withContext false
                }
                archiveFile.delete()
            } else {
                if (!downloadFile(spec.url, targetFile, spec.fileName, onProgress)) {
                    return@withContext false
                }
            }
        }

        Log.i(TAG, "All speaker models downloaded")
        true
    }

    private fun downloadFile(
        url: String,
        target: File,
        fileName: String,
        onProgress: (String, Long, Long) -> Unit,
    ): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "SherpaTranscript/0.1")
            connection.instanceFollowRedirects = true
            connection.connect()

            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        onProgress(fileName, totalRead, totalBytes)
                    }
                }
            }
            Log.i(TAG, "Downloaded $fileName (${target.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download $fileName: ${e.message}")
            target.delete()
            false
        }
    }

    fun areModelsDownloaded(): Boolean {
        val modelDir = SherpaTranscriptApp.instance.filesDir.resolve("models/speaker")
        if (!modelDir.exists()) return false
        return REQUIRED_MODELS.all { modelDir.resolve(it.fileName).exists() }
    }

    private fun extractTarBz2(
        archive: File,
        outputDir: File,
        renameTo: String,
    ): Boolean {
        return try {
            var extracted = false
            BZip2CompressorInputStream(archive.inputStream()).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryName = entry.name
                            val entryFileName = entryName.substringAfterLast('/')
                            if (entryFileName == "model.onnx") {
                                Log.i(TAG, "Found $entryName in archive (${entry.size} bytes)")
                                val out = outputDir.resolve(renameTo)
                                FileOutputStream(out).use { output -> tar.copyTo(output) }
                                extracted = true
                                Log.i(TAG, "Extracted $entryName → $renameTo (${out.length() / 1024} KB)")
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            if (!extracted) Log.e(TAG, "model.onnx not found in archive")
            extracted
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}")
            false
        }
    }
}
