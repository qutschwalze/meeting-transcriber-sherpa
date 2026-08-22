package com.sherpa.transcript.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests für ChunkedAudioBuffer (Rolling-Reconciliation-Architektur).
 *
 * Setup: 16 kHz, 1 Frame = 160 Samples = 10 ms.
 */
class ChunkedAudioBufferTest {

    private val sampleRate = 16000
    private val frameSamples = 160 // 10ms

    /** Erzeugt einen Frame mit einem eindeutigen Sample-Wert (Frame-Index). */
    private fun frameOf(index: Int): FloatArray = FloatArray(frameSamples) { index.toFloat() }

    /** Pusht n Frames ab Startzeit 0 (10ms Abstand). */
    private fun ChunkedAudioBuffer.pushFrames(count: Int, startMs: Long = 0L) {
        for (i in 0 until count) {
            push(frameOf(i), startMs + i * 10L)
        }
    }

    @Test
    fun `erster Chunk liefert chunkSec ohne Overlap`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(3000) // 30s

        val chunk = buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)

        assertNotNull("Chunk sollte verfügbar sein", chunk)
        chunk!!
        assertTrue("erster Chunk", chunk.isFirstChunk)
        assertEquals("kein Overlap beim ersten Chunk", 0f, chunk.overlapSec, 0.001f)
        assertEquals("Start = 0s", 0f, chunk.startSec, 0.001f)
        assertEquals("Ende = 20s", 20f, chunk.endSec, 0.001f)
        assertEquals("Samples = 20s", 20 * sampleRate, chunk.samples.size)
        assertEquals("erster Sample-Wert = Frame 0", 0f, chunk.samples[0], 0.001f)
    }

    @Test
    fun `zweiter Chunk enthaelt Overlap-Kontext vom ersten`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(5000) // 50s

        val first = buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull(first)

        val second = buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull("zweiter Chunk sollte verfügbar sein", second)
        second!!
        assertTrue("nicht erster Chunk", !second.isFirstChunk)
        assertEquals("Overlap = 5s", 5f, second.overlapSec, 0.001f)
        assertEquals("Start = 20s - 5s Overlap", 15f, second.startSec, 0.001f)
        assertEquals("Ende = 40s", 40f, second.endSec, 0.001f)
        assertEquals("Samples = 20s neu + 5s Overlap", 25 * sampleRate, second.samples.size)
        // Overlap-Zone [15s, 20s) entspricht Frame 1500..1999
        assertEquals("Overlap beginnt bei Frame 1500", 1500f, second.samples[0], 0.001f)
        assertEquals("Neues Audio beginnt bei Frame 2000", 2000f, second.samples[5 * sampleRate], 0.001f)
    }

    @Test
    fun `liefert null solange nicht genug neues Audio`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(2500) // 25s → erster Chunk (20s) ok

        val first = buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull(first)

        // Erst 5s neues Audio seit Chunk-Ende (20s) → nächster Chunk bräuchte 20s
        buffer.pushFrames(500, startMs = 25_000L)
        assertNull("5s reichen nicht für 20s-Chunk", buffer.takeChunk(chunkSec = 20f, overlapSec = 5f))

        buffer.pushFrames(1500, startMs = 30_000L)
        val second = buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull("nach 20s neuem Audio verfügbar", second)
    }

    @Test
    fun `alte Frames werden ueber maxWindowSec verworfen`() {
        val buffer = ChunkedAudioBuffer(maxWindowSec = 30f, sampleRate = sampleRate)
        buffer.pushFrames(1000) // 10s
        assertEquals("10s gepuffert", 10f, buffer.bufferedSec, 0.1f)

        buffer.pushFrames(4000, startMs = 10_000L) // insgesamt 50s
        // Fenster = 30s → älteste 20s verworfen
        assertEquals("Fenster auf 30s begrenzt", 30f, buffer.bufferedSec, 0.1f)
    }

    @Test
    fun `clear setzt Chunk-Fortschritt zurueck`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(3000)
        buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)
        buffer.clear()

        assertEquals("leer nach clear", 0f, buffer.bufferedSec, 0.1f)
        // Neuer erster Chunk nach clear → wieder isFirstChunk=true
        buffer.pushFrames(3000)
        val chunk = buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull(chunk)
        assertTrue("nach clear wieder erster Chunk", chunk!!.isFirstChunk)
    }

    @Test
    fun `chunks ohne Luecke ueberdecken die ganze Session`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(12000) // 120s

        var lastEnd = 0f
        var chunks = 0
        while (true) {
            val chunk = buffer.takeChunk(chunkSec = 20f, overlapSec = 5f) ?: break
            assertEquals("lückenlos", lastEnd, chunk.startSec + chunk.overlapSec, 0.01f)
            lastEnd = chunk.endSec
            chunks++
            if (chunks > 20) break // Sicherheitsnetz gegen Endlosschleife
        }
        assertEquals("6 Chunks à 20s bei 120s", 6, chunks)
        assertEquals("Session-Ende erreicht", 120f, lastEnd, 0.01f)
    }

    @Test
    fun `takeRemainingChunk liefert Rest nach dem letzten vollen Chunk`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(3000) // 30s

        val first = buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull(first)

        // 8s Rest-Audio (28s–36s, also 6s NEUES seit Chunk-Ende bei 20s)
        buffer.pushFrames(600, startMs = 30_000L)

        val rest = buffer.takeRemainingChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull("Rest-Chunk verfügbar", rest)
        rest!!
        assertEquals("Overlap ab 15s", 15f, rest.startSec, 0.001f)
        assertEquals("Ende = 36s", 36f, rest.endSec, 0.001f)
        assertEquals("Overlap 5s", 5f, rest.overlapSec, 0.001f)
    }

    @Test
    fun `takeRemainingChunk begrenzt auf max chunkSec+overlapSec - nie monolithisch`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(6000) // 60s
        buffer.takeChunk(chunkSec = 20f, overlapSec = 5f) // Chunk [0,20]

        // 40s Rest! Darf NICHT als 40s-Block kommen (pyannote würde kollabieren)
        val rest = buffer.takeRemainingChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull("Rest-Chunk verfügbar", rest)
        rest!!
        assertEquals("Start = 15s (Overlap)", 15f, rest.startSec, 0.001f)
        assertEquals("Ende = 40s (max chunkSec+overlapSec)", 40f, rest.endSec, 0.001f)
        assertEquals("Dauer <= 25s", true, rest.samples.size <= 25 * sampleRate)

        // Zweiter Aufruf holt den nächsten begrenzten Rest
        val rest2 = buffer.takeRemainingChunk(chunkSec = 20f, overlapSec = 5f)
        assertNotNull("zweiter Rest-Chunk", rest2)
        rest2!!
        assertEquals("zweiter Rest [35,60]", 35f, rest2.startSec, 0.001f)
        assertEquals("zweiter Rest Ende 60s", 60f, rest2.endSec, 0.001f)
    }

    @Test
    fun `takeRemainingChunk liefert null wenn nichts Neues seit letztem Chunk`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(2000) // exakt 20s – Chunk nimmt alles
        buffer.takeChunk(chunkSec = 20f, overlapSec = 5f)

        assertNull("nichts Neues nach vollem Chunk", buffer.takeRemainingChunk(chunkSec = 20f, overlapSec = 5f))
    }

    @Test
    fun `gedehnte Wall-Clock-Stempel erzeugen fast leere Chunks - Sample-Zeit nicht`() {
        // Regressions-Test für den 0.5.58-Fix:
        // Der Capture-Loop positionierte Frames mit Wall-Clock-Zeit. Stockt der Loop
        // (ASR-Inferenz + Pyannote unter Last), bekommen späte Frames ZU SPÄTE Stempel
        // → die Zeitachse dehnt sich → Chunk [55,75] zeigt auf fast leere Bereiche
        // (Log-Beweis: RMS 0.0005 bei normaler Quelle). Sample-basierte Stempel
        // (pushedSampleCountMs) bleiben exakt an der Audio-Position.
        val sampleRate = 16000
        val frameSamples = 160

        // 60s Audio, aber jeder Frame wird mit +20ms statt +10ms gestempelt
        // (Simulation: Loop braucht doppelt so lange wie die Frame-Dauer)
        val wallClockBuffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        for (i in 0 until 6000) {
            wallClockBuffer.push(FloatArray(frameSamples) { i.toFloat() }, i * 20L)
        }
        // Chunks: [0,20], [20,40], [40,60] – mit gedehnten Stempeln zeigt [40,60]
        // auf Frames bei 40-60s "Wall-Clock" = Frames 2000-3000 = echte 20-30s Audio
        wallClockBuffer.takeChunk(20f, 5f)
        wallClockBuffer.takeChunk(20f, 5f)
        val thirdWall = wallClockBuffer.takeChunk(20f, 5f)

        // Sample-basierte Stempel: exakt 10ms pro Frame
        val sampleBuffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        for (i in 0 until 6000) {
            sampleBuffer.push(FloatArray(frameSamples) { i.toFloat() }, i * 10L)
        }
        sampleBuffer.takeChunk(20f, 5f)
        sampleBuffer.takeChunk(20f, 5f)
        val thirdSample = sampleBuffer.takeChunk(20f, 5f)

        assertNotNull(thirdWall)
        assertNotNull(thirdSample)
        // Der gedehnte Chunk [40,60] enthält nur ~10s Samples (Hälfte),
        // der sample-basierte Chunk [40,60] enthält volle 20s
        assertTrue(
            "gedehnter Chunk hat deutlich weniger Samples als sample-basierter " +
                "(wall=${thirdWall!!.samples.size} vs sample=${thirdSample!!.samples.size})",
            thirdWall.samples.size < thirdSample.samples.size,
        )
        assertEquals("sample-basierter Chunk = volle 25s (20s + 5s Overlap)", 25 * sampleRate, thirdSample.samples.size)
    }

    @Test
    fun `readWindow - liefert Samples des Zeitfensters`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.push(FloatArray(1600) { 1f }, 0L)    // 0-100ms
        buffer.push(FloatArray(1600) { 2f }, 100L)  // 100-200ms

        val win = buffer.readWindow(120L, 160L)     // 40ms im zweiten Frame
        assertEquals("40ms x 16k = 640 Samples", 640, win.size)
        assertTrue("alle Samples aus dem 2. Frame", win.all { it == 2f })
    }

    @Test
    fun `readWindow - schneidet ueber Frame-Grenzen hinweg`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.push(FloatArray(1600) { 1f }, 0L)    // 0-100ms Wert 1
        buffer.push(FloatArray(1600) { 2f }, 100L)  // 100-200ms Wert 2

        val win = buffer.readWindow(90L, 110L)      // 10ms Frame1 + 10ms Frame2
        assertEquals("20ms x 16k = 320 Samples", 320, win.size)
        assertEquals("erste 160 aus Frame 1", 1f, win[0], 0f)
        assertEquals("letzte 160 aus Frame 2", 2f, win[win.size - 1], 0f)
    }

    @Test
    fun `readWindow - ausserhalb des Puffers liefert leeres Array`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.push(FloatArray(1600) { 1f }, 0L)
        assertTrue(buffer.readWindow(500L, 600L).isEmpty())
        assertTrue(buffer.readWindow(10L, 0L).isEmpty())   // invertiert
    }
}
