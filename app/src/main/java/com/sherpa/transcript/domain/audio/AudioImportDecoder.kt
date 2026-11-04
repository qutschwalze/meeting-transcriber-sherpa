package com.sherpa.transcript.domain.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Phase 9 (0.9.0): Dekodiert geteilte Audiodateien (Sprachnachrichten aus
 * WhatsApp/Telegram etc., content:// URI via Share-Intent) zu 16 kHz mono
 * PCM-float – die Eingabeform der Engine.
 *
 * Pipeline: MediaExtractor (Demux) → MediaCodec (Decode, meist Opus/AAC/AMR)
 * → Stereo→Mono-Downmix → AudioResampler auf 16 kHz.
 *
 * Keine Storage-Permission nötig: Die Leseberechtigung wird vom Share-Intent
 * mitgegeben (FLAG_GRANT_READ_URI_PERMISSION).
 */
object AudioImportDecoder {

    /** Maximale Importlänge (30 min × 60 s × 16 kHz) – Schutz vor Speicher-Explosion. */
    const val MAX_SAMPLES = 30 * 60 * 16_000

    /**
     * Dekodiert [uri] zu 16 kHz mono. @return null bei nicht dekodierbarem Format.
     * @throws IllegalArgumentException wenn die Datei länger als 30 min wäre.
     */
    fun decodeTo16kMono(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var srcMime = "?"
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
            }
            if (trackIndex < 0 || format == null) return null

            srcMime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // Grobe Obergrenze schon VOR dem Dekodieren prüfen (Dauer × Zielrate)
            if (durationUs > 0 && durationUs / 1_000_000L > 30L * 60) {
                throw IllegalArgumentException("Audiodatei länger als 30 Minuten")
            }

            extractor.selectTrack(trackIndex)
            val codec = MediaCodec.createDecoderByType(srcMime)
            codec.configure(format, null, null, 0)
            codec.start()

            // 0.12.12: Import-Diagnose ins TestLog (Debug-Upload) – MIME/Rate/Kanäle
            // und Decode-Dauer, damit mp3-/Format-Probleme per Upload analysierbar sind.
            val decodeStartMs = System.currentTimeMillis()
            TestLog.log("IMPORT DECODE start mime=$srcMime rate=$sampleRate ch=$channels dur=${durationUs / 1_000_000}s")

            // Chunks sammeln (O(n) gesamt) statt mono.copyOf pro Buffer (O(n²)):
            // bei mp3 (Stereo 44,1 kHz, mehrere Minuten) bewegte das Wachstum
            // zig GB Heap und der Import wirkte eingefroren.
            val out = ArrayList<FloatArray>(64)
            var totalSamples = 0
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                // Input füttern
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // Output lesen
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    // PCM16 little-endian → float (-1..1)
                    val shorts = readShortsLE(outBuf, info.offset, info.offset + info.size)
                    val frameSamples = info.size / 2 / channels
                    if (frameSamples > 0) {
                        val chunk = FloatArray(frameSamples)
                        for (i in 0 until frameSamples) {
                            var acc = 0f
                            for (c in 0 until channels) {
                                acc += shorts[i * channels + c] / 32768f
                            }
                            chunk[i] = acc / channels   // Downmix auf Mono
                        }
                        // 0.12.15: SOFORT auf 16 kHz resamplen statt erst das komplette
                        // Quellrate-Array zu bauen. Eine 14-min-mp3 @48 kHz = 42,5 M Samples
                        // = 170 MB FloatArray – zusammen mit den ONNX-Sessions (~100+ MB)
                        // riss das das 256-MB-Heap (Log-Befund: "169869328 byte allocation …
                        // OOM", Growth-Limit 256 MB). Nur das 16-kHz-Ergebnis sammeln:
                        // 14,2 M Samples = ~57 MB. Für 16-kHz-WAVs ist fromRate==toRate →
                        // Resampler gibt den Input unverändert zurück (keine Kopie).
                        val resampled = AudioResampler.resample(chunk, sampleRate, 16_000)
                        totalSamples += resampled.size
                        // Guard jetzt direkt in 16-kHz-Zielsamples (MAX_SAMPLES = 30 min × 16 k)
                        if (totalSamples > MAX_SAMPLES) {
                            codec.releaseOutputBuffer(outIdx, false)
                            codec.stop(); codec.release(); extractor.release()
                            throw IllegalArgumentException("Audiodatei länger als 30 Minuten")
                        }
                        out.add(resampled)
                    }
                    val eosFlag = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eosFlag) sawOutputEos = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Formatwechsel (selten) ignorieren – Kanalzahl/Rate bleiben die Startwerte
                }
            }
            codec.stop(); codec.release()
            extractor.release()
            if (out.isEmpty()) return null
            // 16-kHz-Chunks in EIN Array zusammenführen (einmalige O(n)-Kopie statt pro-Buffer)
            val mono = FloatArray(totalSamples)
            var off = 0
            for (c in out) {
                System.arraycopy(c, 0, mono, off, c.size)
                off += c.size
            }
            TestLog.log("IMPORT DECODE done samples=$totalSamples tookMs=${System.currentTimeMillis() - decodeStartMs}")
            return mono
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("AudioImport", "decode fehlgeschlagen: ${t.message}")
            try { extractor.release() } catch (_: Throwable) {}
            TestLog.log("IMPORT DECODE FAIL: mime=$srcMime msg=${t.message}")
            return null
        }
    }

    /** Liest [buf] als Little-Endian-Shorts zwischen [start] und [end]. */
    private fun readShortsLE(buf: java.nio.ByteBuffer, start: Int, end: Int): java.nio.ShortBuffer {
        val dup = buf.duplicate()
        dup.position(start)
        dup.limit(end)
        return dup.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    }
}