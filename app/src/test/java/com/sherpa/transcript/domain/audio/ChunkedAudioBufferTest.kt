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
}
