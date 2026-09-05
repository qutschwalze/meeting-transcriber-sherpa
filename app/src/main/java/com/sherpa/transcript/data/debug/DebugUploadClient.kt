package com.sherpa.transcript.data.debug

import android.content.Context
import android.os.Build
import android.util.Log
import com.sherpa.transcript.BuildConfig
import com.sherpa.transcript.SherpaTranscriptApp
import com.sherpa.transcript.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug-upload client for shipping WAV recordings, logs, and transcript JSONs
 * to the companion Python debug server (runs on port 8520 on the host).
 *
 * Uses plain java.net.HttpURLConnection – no OkHttp dependency.
 * All I/O runs on Dispatchers.IO.
 */
object DebugUploadClient {

    private const val TAG = "DebugUploadClient"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 120_000 // large WAV files

    // ── Server URL management ────────────────────────────────────────────

    /** Read the stored server URL from SettingsStore (the single source of truth). */
    fun getServerUrl(context: Context): String {
        return runBlocking {
            SettingsStore.current.debugServerUrl.first()
        }
    }

    /** Persist a custom server URL (delegates to SettingsStore). */
    fun setServerUrl(context: Context, url: String) {
        SettingsStore.current.setDebugServerUrl(url)
    }

    // ── Single-file upload ───────────────────────────────────────────────

    /**
     * Upload a single file to the debug server.
     *
     * @param file     the local file to send
     * @param fileType MIME / semantic type (e.g. "audio/wav", "text/plain", "application/json")
     * @param sessionId optional session identifier
     * @return Result with the server response body on success
     * 0.12.0: Sends X-API-Key header for server authentication (Threat Model T5/T18).
     */
    suspend fun uploadFile(
        file: File,
        fileType: String,
        sessionId: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(file.exists()) { "File does not exist: ${file.absolutePath}" }

            val ctx = SherpaTranscriptApp.instance
            val serverUrl = getServerUrl(ctx)
            val apiKey = runBlocking { SettingsStore.current.debugApiKey.first() }
            val url = URL("$serverUrl/upload")

            Log.i(TAG, "Uploading ${file.name} (${file.length()} bytes) → $url")

            val boundary = "----SherpaBoundary${System.currentTimeMillis()}"

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                // 0.12.0: API-Key für Server-Auth
                if (apiKey.isNotBlank()) {
                    setRequestProperty("X-API-Key", apiKey)
                }
            }

            conn.outputStream.use { os ->
                val dos = DataOutputStream(os)

                // -- file field: stream directly from file (no full-RAM read) --
                writeField(dos, boundary, "file", file.name, file, fileType)

                // -- metadata fields --
                writeFormField(dos, boundary, "file_type", fileType)
                writeFormField(dos, boundary, "device_model", Build.MODEL)
                writeFormField(dos, boundary, "app_version", BuildConfig.VERSION_NAME)
                writeFormField(dos, boundary, "app_version_code", BuildConfig.VERSION_CODE.toString())

                if (!sessionId.isNullOrBlank()) {
                    writeFormField(dos, boundary, "session_id", sessionId)
                }

                // -- closing boundary --
                dos.writeBytes("--$boundary--\r\n")
                dos.flush()
            }

            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                throw RuntimeException("HTTP $code: $err")
            }

            Log.i(TAG, "Upload OK (${file.name}): $body")
            Result.success(body)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${file.name}: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Bundle upload ────────────────────────────────────────────────────

    /**
     * Upload WAV, log, MD, and JSON files from [sessionDir].
     *
     * @param sessionDir directory containing the debug files
     * @param sessionId  session identifier to attach to every upload
     * @param maxFiles   max files to upload (0 = all). Takes the newest N by filename (timestamp-sorted).
     * @param skipChunks skip the chunks/ subdirectory (recommended – chunk WAVs are huge and not useful)
     * @return Result with a summary string listing successes/failures
     */
    suspend fun uploadDebugBundle(
        sessionDir: File,
        sessionId: String,
        maxFiles: Int = 0,
        skipChunks: Boolean = true,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(sessionDir.isDirectory) { "Not a directory: ${sessionDir.absolutePath}" }

            Log.i(TAG, "uploadDebugBundle: scanning ${sessionDir.absolutePath}")
            Log.i(TAG, "  exists=${sessionDir.exists()} canRead=${sessionDir.canRead()} isDir=${sessionDir.isDirectory}")

            // 0.6.16: Erst alle Dateien auflisten (Debug)
            val allFiles = sessionDir.listFiles()
            Log.i(TAG, "  listFiles() returned ${allFiles?.size ?: "null"} entries")
            allFiles?.take(10)?.forEach { f ->
                Log.d(TAG, "  entry: name='${f.name}' ext='${f.extension}' isFile=${f.isFile} isDir=${f.isDirectory} len=${f.length()} path=${f.absolutePath}")
            }
            // Debug: alle Extensions zeigen
            val exts = allFiles?.map { it.extension.lowercase() }?.groupingBy { it }?.eachCount()
            Log.i(TAG, "  extensions: $exts")

            // 0.6.16-Bugfix: listFiles(filter) ist auf manchen Android-Versionen unzuverlässig.
            // Alle Dateien auflisten und in-memory filtern.
            // ACHTUNG: f.extension liefert OHNE Punkt ("wav", nicht ".wav")!
            val extensions = setOf("wav", "log", "md", "json")
            val files = (allFiles?.toList() ?: emptyList()).filter { f ->
                f.isFile &&
                    f.extension.lowercase() in extensions &&
                    (!skipChunks || !f.parentFile?.name.equals("chunks", ignoreCase = true))
            }
            Log.i(TAG, "  after filter: ${files.size} files")

            // 0.6.21: Nur die neuesten N Sessions.
            // Session = Dateien mit gleichem Zeitstempel im Namen
            // (testaufnahme_20260821_142334.wav + .log + evtl. .md = 1 Session).
            // ACHTUNG: Alte Exporte heißen transcript_<uuid>.md (kein Zeitstempel) –
            // die zählen NICHT als eigene Session (sie blockierten die Top-N-Auswahl,
            // weil "transcript_*" lexikografisch nach "testaufnahme_*" sortiert).
            val selected = if (maxFiles > 0) {
                val tsPattern = Regex("\\d{8}_\\d{6}")
                val sessionMap = mutableMapOf<String, MutableList<File>>()
                for (f in files) {
                    val ts = tsPattern.find(f.nameWithoutExtension)?.value
                    if (ts != null) {
                        sessionMap.getOrPut(ts) { mutableListOf() }.add(f)
                    }
                    // Dateien OHNE Zeitstempel (Altexporte transcript_<uuid>.md)
                    // werden im maxFiles-Modus übersprungen – keine Session-Zuordnung.
                }
                sessionMap.values
                    .sortedByDescending { sess -> sess.maxOfOrNull { it.name } ?: "" }
                    .take(maxFiles)
                    .flatten()
            } else {
                files.sortedBy { it.name }
            }

            Log.i(TAG, "uploadDebugBundle: ${selected.size}/${files.size} selected files")

            if (selected.isEmpty()) {
                val msg = "No matching files in ${sessionDir.name} (total entries: ${allFiles?.size ?: "null"}, abs path: ${sessionDir.absolutePath})"
                Log.w(TAG, msg)
                return@withContext Result.success(msg)
            }

            Log.i(TAG, "Bundle upload: ${selected.size}/${files.size} files from ${sessionDir.name}" +
                if (maxFiles > 0) " (maxFiles=$maxFiles)" else "")

            val successes = mutableListOf<String>()
            val failures = mutableListOf<String>()

            for (file in selected) {
                val fileType = when {
                    file.name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                    file.name.endsWith(".log", ignoreCase = true) -> "text/plain"
                    file.name.endsWith(".md", ignoreCase = true) -> "text/markdown"
                    file.name.endsWith(".json", ignoreCase = true) -> "application/json"
                    else -> "application/octet-stream"
                }

                val result = uploadFile(file, fileType, sessionId)
                if (result.isSuccess) {
                    successes.add(file.name)
                    // Quelle nach erfolgreichem Upload löschen (verhindert
                    // unbegrenztes Speicherwachstum auf dem Gerät UND den
                    // Re-Upload der kompletten Bibliothek bei jedem Trigger).
                    // Nur bei Erfolg: bei Server-Aus bleibt die Datei für den
                    // nächsten Retry erhalten.
                    if (file.delete()) {
                        Log.i(TAG, "  DebugUpload: ${file.name} hochgeladen + gelöscht")
                    } else {
                        Log.w(TAG, "  DebugUpload: ${file.name} hochgeladen, Löschen fehlgeschlagen")
                    }
                } else {
                    failures.add("${file.name}: ${result.exceptionOrNull()?.message}")
                }
            }

            // Chunk-Diagnose-WAVs (testaufnahmen/chunks/) werden bewusst NIE
            // hochgeladen (skipChunks – riesig, für Host-Analyse unnötig) und
            // wären sonst ewiger Speichermüll auf dem Gerät. Erst löschen, wenn
            // die komplette Bibliothek erfolgreich beim Server angekommen ist –
            // bei Teilerfolg/Fail bleibt alles für den Retry erhalten.
            if (failures.isEmpty()) {
                val chunksDir = File(sessionDir, "chunks")
                val deleted = chunksDir.listFiles()?.filter { it.isFile }?.count { it.delete() } ?: 0
                if (deleted > 0) {
                    Log.i(TAG, "DebugUpload: $deleted Chunk-Diagnose-WAVs gelöscht (Bibliothek vollständig hochgeladen)")
                }
            }

            val summary = buildString {
                appendLine("Bundle upload complete: ${successes.size} OK, ${failures.size} failed")
                if (successes.isNotEmpty()) {
                    appendLine("  OK: ${successes.joinToString(", ")}")
                }
                if (failures.isNotEmpty()) {
                    appendLine("  FAIL:")
                    failures.forEach { appendLine("    $it") }
                }
            }

            Log.i(TAG, summary.trim())
            Result.success(summary.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Bundle upload failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Multipart helpers ────────────────────────────────────────────────

    /** Write a file part (streamed – no full-RAM copy for large WAVs). */
    private fun writeField(
        dos: DataOutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        file: File,
        contentType: String,
    ) {
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
        dos.writeBytes("Content-Type: $contentType\r\n")
        dos.writeBytes("\r\n")
        // Stream in 8 KB chunks – keeps peak RAM low for 50+ MB WAVs
        file.inputStream().buffered(8192).use { fis ->
            val buf = ByteArray(8192)
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                dos.write(buf, 0, read)
            }
        }
        dos.writeBytes("\r\n")
    }

    /** Write a plain text form field. */
    private fun writeFormField(
        dos: DataOutputStream,
        boundary: String,
        fieldName: String,
        value: String,
    ) {
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"\r\n")
        dos.writeBytes("Content-Type: text/plain; charset=UTF-8\r\n")
        dos.writeBytes("\r\n")
        dos.writeBytes("$value\r\n")
    }
}
