package com.sherpa.transcript.domain.audio

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.5.70: Diagnose-Log in Datei – unabhängig vom flüchtigen logcat-Puffer.
 *
 * Xiaomi/HyperOS logcat wird von System-Logs (AiCrEngine etc.) überflutet;
 * App-Diagnose (normalizeAudio, Chunks, saveSegments) geht verloren, bevor
 * sie per adb gezogen wird (Log-Befund 0.5.69: Test ohne Log, App zeigte
 * nur 1 Sprecher, Host findet mit derselben WAV 2 – ohne App-Log keine
 * Ursachen-Diagnose möglich).
 *
 * Im Debug-Mode schreiben die kritischen Stellen (AudioCaptureManager,
 * DiarizationChunkWorker, LiveViewModel) ihre Diagnose-Zeilen zusätzlich
 * in eine .log-Datei neben die Testaufnahme-WAV.
 */
object TestLog {
    private const val TAG = "TestLog"
    private var writer: PrintWriter? = null
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Pfad der aktuellen Log-Datei (null wenn inaktiv). */
    val path: String? get() = file?.absolutePath

    /** Öffnet die Log-Datei (überschreibt eine evtl. offene). */
    fun open(dir: File, name: String): Boolean {
        close()
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "open: Ordner konnte nicht erstellt werden: ${dir.absolutePath}")
                return false
            }
            val f = File(dir, name)
            val w = PrintWriter(FileWriter(f))
            writer = w
            file = f
            log("=== TestLog gestartet: $name ===")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "open fehlgeschlagen: ${t.message}")
            false
        }
    }

    /** Schreibt eine Diagnose-Zeile (no-op wenn inaktiv). */
    fun log(line: String) {
        val w = writer ?: return
        try {
            w.println("${fmt.format(Date())} $line")
            w.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "log fehlgeschlagen: ${t.message}")
            close()
        }
    }

    fun close() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        file = null
    }
}
