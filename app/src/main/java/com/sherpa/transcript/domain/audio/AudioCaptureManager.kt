package com.sherpa.transcript.domain.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.sherpa.transcript.SherpaTranscriptApp
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Verwaltet die Mikrofonaufnahme über Android AudioRecord.
 * Liefert einen kontinuierlichen Flow von 10ms-PCM-Frames (16bit, Mono, 16kHz).
 */
class AudioCaptureManager(
    val sampleRate: Int = 16000,
    /** 10ms bei 16kHz = 160 Samples */
    private val frameSize: Int = 160,
    /** 10ms puffer-wecker für VAD/ASR */
    private val bufferIntervalFrames: Int = 10,  // 10 Frames = 100ms
) {

    private var audioRecord: AudioRecord? = null

    /** Hardware-AGC (Automatic Gain Control) auf der AudioRecord-Session. */
    private var agc: AutomaticGainControl? = null

    /**
     * 0.5.68: Wenn true (Debug-Mode), wird die ROH-Aufnahme (16 kHz mono PCM,
     * exakt wie die App sie bekommt) als WAV gespeichert – für Host-Analyse:
     * Pegel/Spektrum/Embedding-Separation der echten App-Aufnahme messen und
     * die Pipeline damit reproduzieren (statt aus Logs zu raten).
     * Pfad: /sdcard/Android/data/com.sherpa.transcript/files/Download/testaufnahmen/
     */
    var saveRawWav: Boolean = false
    private var wavStream: DataOutputStream? = null
    private var wavFile: File? = null
    private var wavDataBytes: Long = 0L

    companion object {
        private const val TAG = "AudioCaptureManager"

        /** Nach wie vielen Drops wird ein Warngesamt-Log geschrieben. */
        private const val DROP_LOG_INTERVAL = 50
    }

    /**
     * Startet die Aufnahme und liefert einen Flow von Float PCM-Frames.
     * Jeder Frame ist ein FloatArray der Länge [frameSize].
     * Frames werden auf einem IO-Dispatcher produziert.
     *
     * @throws SecurityException wenn keine RECORD_AUDIO-Berechtigung
     */
    fun startCapture(): Flow<FloatArray> = callbackFlow {
        val context = SherpaTranscriptApp.instance
        val permission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Puffer groß genug für mindestens 200ms
        val bufferSize = (sampleRate / 5).coerceAtLeast(minBufferSize) * 2

        audioRecord = AudioRecord(
            // 0.5.67: CAMCORDER statt MIC – Log-Befund 0.5.66: AGC nicht verfügbar
            // (isAvailable=false), Pegel konstant zu leise (RMS 0,0008-0,035 →
            // Boost 10x Limit). Der HyperOS3-Rekorder nutzt einen HAL-Pfad mit
            // AGC. CAMCORDER verwendet auf Xiaomi oft den Vorverstärker-Pfad
            // mit eigenem Gain/AGC – messbar über das CAPTURE_RMS-Diagnose-Log.
            MediaRecorder.AudioSource.CAMCORDER,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            close(Exception("AudioRecord failed to initialize"))
            return@callbackFlow
        }

        // ── 0.5.66: Hardware-AGC aktivieren (falls das Gerät es unterstützt) ──
        // Log-Befund 0.5.63-0.5.65: Die App nimmt konstant ZU LEISE auf
        // (RMS 0,0006-0,02 → Boost 10x Limit in normalizeAudio), der
        // HyperOS3-Rekorder bei identischen akustischen Bedingungen nicht
        // (eigene AGC) → Titanet-Embeddings auf der App-Aufnahme verrauscht
        // → Monologue-Splits / Fehlzuordnungen, obwohl das Signal trennbar ist.
        // AGC regelt den Pegel vor der App → weniger Rausch-Boost → saubere
        // Embeddings. Messbar im Log: normalizeAudio zeigt dann RMS ~0,1 statt
        // 0,005 und Boost ~1x statt 10x.
        if (AutomaticGainControl.isAvailable()) {
            try {
                val created = AutomaticGainControl.create(audioRecord?.audioSessionId ?: 0)
                if (created != null) {
                    created.enabled = true
                    agc = created
                    Log.i(TAG, "AGC aktiviert (session=${audioRecord?.audioSessionId}, enabled=${created.enabled})")
                } else {
                    Log.w(TAG, "AGC create lieferte null – Gerät unterstützt es nicht (weiter ohne AGC)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AGC create fehlgeschlagen: ${t.message}")
            }
        } else {
            Log.d(TAG, "AGC nicht verfügbar auf diesem Gerät – weiter ohne AGC")
        }

        audioRecord?.startRecording()

        // 0.5.68: Testaufnahme (Debug-Mode) – Roh-PCM als WAV für Host-Analyse
        if (saveRawWav) startWavCapture()

        withContext(Dispatchers.IO) {
            val pcmShort = ShortArray(frameSize)
            val pcmFloat = FloatArray(frameSize)
            var droppedFrames = 0L
            var totalFrames = 0L
            // 0.5.67: RMS-Diagnose 1x/Sekunde – misst den Eingangspegel der
            // Capture-Kette (vor normalizeAudio). Log-Befund 0.5.66: RMS
            // 0,0008-0,035 (MIC ohne AGC). Ziel: mit CAMCORDER/AGC im Bereich
            // ~0,05-0,3 (Rekorder-Niveau), dann ist der Pegel nicht mehr die
            // Ursache für verrauschte Titanet-Embeddings.
            var rmsAccum = 0.0
            var rmsCount = 0

            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(pcmShort, 0, frameSize) ?: -1
                if (bytesRead > 0) {
                    // ShortArray → normiertes FloatArray (-1..1)
                    for (i in 0 until bytesRead) {
                        pcmFloat[i] = pcmShort[i] / 32768.0f
                        rmsAccum += pcmFloat[i] * pcmFloat[i]
                    }
                    rmsCount += bytesRead
                    if (rmsCount >= sampleRate) {
                        val rms = sqrt(rmsAccum / rmsCount)
                        Log.d(TAG, "CAPTURE_RMS rms=${"%.4f".format(rms)} peakLevel=" +
                                "${"%.3f".format(pcmFloat.maxOf { kotlin.math.abs(it) })} (source=CAMCORDER)")
                        rmsAccum = 0.0
                        rmsCount = 0
                    }
                    // 0.5.68: Roh-Samples in die WAV schreiben (Debug-Mode)
                    val wavOut = wavStream
                    if (wavOut != null) {
                        try {
                            for (i in 0 until bytesRead) {
                                val s = (pcmFloat[i] * 32767f).toInt().coerceIn(-32768, 32767)
                                wavOut.writeByte(s and 0xFF)
                                wavOut.writeByte((s shr 8) and 0xFF)
                            }
                            wavDataBytes += bytesRead * 2L
                        } catch (t: Throwable) {
                            Log.w(TAG, "WAV-Write fehlgeschlagen: ${t.message}")
                            try { wavOut.close() } catch (_: Exception) {}
                            wavStream = null
                        }
                    }
                    val frame = if (bytesRead < frameSize) pcmFloat.copyOf(bytesRead) else pcmFloat
                    totalFrames++
                    // KRITISCH (0.5.60): trySend-Ergebnis prüfen! callbackFlow hat
                    // nur 64 Frames Puffer (640ms). Der Collector macht synchron
                    // ASR-Inferenz (engine.processFrame); wenn Pyannote + Voice-Bank
                    // parallel die CPU belegen, läuft der Puffer voll und trySend
                    // droppt still Frames → Audio geht verloren (Log-Beweis:
                    // Chunk [55,75] in 0.5.58 fast leer trotz normaler Aufnahme).
                    // Der Zähler macht die Drops sichtbar und quantifiziert sie.
                    if (!trySend(frame).isSuccess) {
                        droppedFrames++
                        if (droppedFrames % DROP_LOG_INTERVAL == 1L || droppedFrames == 1L) {
                            Log.w(TAG, "FLOW DROP: $droppedFrames Frames verworfen " +
                                    "(Puffer voll, total=$totalFrames, " +
                                    "verlorenes Audio ≈ ${droppedFrames * frame.size / sampleRate}s)")
                        }
                    }
                }
            }
            if (droppedFrames > 0) {
                Log.w(TAG, "Capture beendet: $droppedFrames/$totalFrames Frames gedroppt " +
                        "(≈ ${droppedFrames * frameSize / sampleRate}s Audio verloren)")
            }
        }

        awaitClose { stopCapture() }
    }

    fun stopCapture() {
        closeWavCapture()
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        } catch (_: Exception) {}
        audioRecord = null
        try {
            agc?.release()
        } catch (_: Exception) {}
        agc = null
    }

    /** 0.5.68: WAV-Header öffnen (16 kHz mono 16-bit PCM, Little-Endian). */
    private fun startWavCapture() {
        try {
            val base = SherpaTranscriptApp.instance.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return
            val dir = File(base, "testaufnahmen")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "WAV: Ordner konnte nicht erstellt werden: ${dir.absolutePath}")
                return
            }
            val name = "testaufnahme_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav"
            val file = File(dir, name)
            val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))
            out.writeBytes("RIFF")
            writeLeInt(out, 36)          // Chunk-Size (wird am Ende gepatcht)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeLeInt(out, 16)          // fmt-Chunk-Größe
            writeLeShort(out, 1)         // PCM
            writeLeShort(out, 1)         // Mono
            writeLeInt(out, 16000)       // Sample-Rate
            writeLeInt(out, 16000 * 2)   // Byte-Rate
            writeLeShort(out, 2)         // Block-Align
            writeLeShort(out, 16)        // Bits pro Sample
            out.writeBytes("data")
            writeLeInt(out, 0)           // Data-Size (wird am Ende gepatcht)
            wavStream = out
            wavFile = file
            wavDataBytes = 0L
            Log.i(TAG, "WAV-Testaufnahme startet (Debug-Mode): ${file.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "WAV-Start fehlgeschlagen: ${t.message}")
            wavStream = null
            wavFile = null
        }
    }

    /** 0.5.68: WAV schließen und Header-Größen patchen (Little-Endian). */
    private fun closeWavCapture() {
        val out = wavStream ?: return
        try {
            out.flush()
            out.close()
            val file = wavFile
            if (file != null) {
                val riffSize = (36 + wavDataBytes).toInt()
                val dataSize = wavDataBytes.toInt()
                val raf = RandomAccessFile(file, "rw")
                raf.seek(4); raf.write(riffSize and 0xFF); raf.write((riffSize shr 8) and 0xFF)
                raf.write((riffSize shr 16) and 0xFF); raf.write((riffSize shr 24) and 0xFF)
                raf.seek(40); raf.write(dataSize and 0xFF); raf.write((dataSize shr 8) and 0xFF)
                raf.write((dataSize shr 16) and 0xFF); raf.write((dataSize shr 24) and 0xFF)
                raf.close()
                val durSec = wavDataBytes / 2 / 16000
                Log.i(TAG, "WAV-Testaufnahme fertig: ${file.absolutePath} (${durSec}s, ${wavDataBytes / 1024} KiB)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WAV-Close fehlgeschlagen: ${t.message}")
        }
        wavStream = null
        wavFile = null
        wavDataBytes = 0L
    }

    private fun writeLeInt(out: DataOutputStream, v: Int) {
        out.writeByte(v and 0xFF)
        out.writeByte((v shr 8) and 0xFF)
        out.writeByte((v shr 16) and 0xFF)
        out.writeByte((v shr 24) and 0xFF)
    }

    private fun writeLeShort(out: DataOutputStream, v: Int) {
        out.writeByte(v and 0xFF)
        out.writeByte((v shr 8) and 0xFF)
    }

    fun isRecording(): Boolean =
        audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
}
