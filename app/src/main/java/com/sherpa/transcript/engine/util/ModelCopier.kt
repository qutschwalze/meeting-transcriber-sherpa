package com.sherpa.transcript.engine.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Hilfsklasse zum Kopieren von ML-Modellen aus den App-Assets
 * in den internen Speicher (weil Sherpa-ONNX Dateipfade braucht).
 */
object ModelCopier {
    private const val TAG = "ModelCopier"

    /**
     * Kopiert eine Datei aus assets in den internen Speicher,
     * falls sie dort noch nicht existiert.
     *
     * @return absoluter Pfad zur kopierten Datei
     */
    fun copyAssetToInternalStorage(context: Context, assetPath: String): String {
        val targetRoot = context.filesDir
        val outFile = File(targetRoot, assetPath)

        if (outFile.exists()) {
            return outFile.absolutePath
        }

        try {
            outFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied $assetPath to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Asset $assetPath not found: ${e.message}")
        }

        return outFile.absolutePath
    }

    /**
     * Kopiert eine Liste von durch Komma getrennten Asset-Pfaden.
     */
    fun copyAssetListToInternalStorage(context: Context, paths: String): String {
        if (paths.isBlank()) return paths
        return paths.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { copyAssetToInternalStorage(context, it) }
            .joinToString(",")
    }

    /**
     * Prüft, ob eine Datei in assets existiert.
     */
    fun assetExists(assets: android.content.res.AssetManager, path: String): Boolean {
        val dir = path.substringBeforeLast('/', "")
        val fileName = path.substringAfterLast('/', "")
        val files = assets.list(dir) ?: return false
        return files.contains(fileName)
    }
}
