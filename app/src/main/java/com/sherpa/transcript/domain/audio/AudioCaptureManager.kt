package com.sherpa.transcript.domain.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.sherpa.transcript.SherpaTranscriptApp
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
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            close(Exception("AudioRecord failed to initialize"))
            return@callbackFlow
        }

        audioRecord?.startRecording()

        withContext(Dispatchers.IO) {
            val pcmShort = ShortArray(frameSize)
            val pcmFloat = FloatArray(frameSize)

            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(pcmShort, 0, frameSize) ?: -1
                if (bytesRead > 0) {
                    // ShortArray → normiertes FloatArray (-1..1)
                    for (i in 0 until bytesRead) {
                        pcmFloat[i] = pcmShort[i] / 32768.0f
                    }
                    val frame = if (bytesRead < frameSize) pcmFloat.copyOf(bytesRead) else pcmFloat
                    trySend(frame)
                }
            }
        }

        awaitClose { stopCapture() }
    }

    fun stopCapture() {
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun isRecording(): Boolean =
        audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
}
