package com.sherpa.transcript.engine

import com.sherpa.transcript.domain.audio.ChunkedAudioBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests für DiarizationChunkWorker (Rolling-Reconciliation-Architektur).
 *
 * Setup: 16 kHz, 1 Frame = 160 Samples = 10 ms, Chunk = 20s + 5s Overlap.
 */
class DiarizationChunkWorkerTest {

    private val sampleRate = 16000
    private val frameSamples = 160 // 10ms

    private fun frameOf(index: Int): FloatArray = FloatArray(frameSamples) { index.toFloat() }

    private fun ChunkedAudioBuffer.pushFrames(count: Int, startMs: Long = 0L) {
        for (i in 0 until count) {
            push(frameOf(i), startMs + i * 10L)
        }
    }

    /** Fake-Diarizer: liefert vordefinierte Ergebnisse in Reihenfolge. */
    private class FakeDiarizer(
        private val results: ArrayDeque<List<DiarizationSegment>>,
    ) : ChunkDiarizer {
        override fun process(samples: FloatArray): List<DiarizationSegment> {
            assertTrue("FakeDiarizer: Samples vorhanden", samples.isNotEmpty())
            return results.removeFirst()
        }
    }

    private fun localSeg(speaker: Int, startSec: Float, endSec: Float) =
        DiarizationSegment(startSec = startSec, endSec = endSec, speaker = speaker)

    @Test
    fun `erster Chunk - Time-Shift ohne Verschiebung, Mapping neu`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(3000) // 30s → Chunk 1: [0,20]

        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 10f), localSeg(1, 10f, 20f)),
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        val result = worker.processNextChunk(debug = false)

        assertNotNull("Chunk 1 verfügbar", result)
        result!!
        assertEquals("Chunk [0,20]", 0f, result.chunk.startSec, 0.001f)
        assertEquals("kein Overlap im ersten Chunk", 0f, result.chunk.overlapSec, 0.001f)
        // Time-Shift: Offset 0 → Zeiten unverändert
        assertEquals("Seg 1 absolut [0,10]", 0f, result.mappedSegments[0].startSec, 0.001f)
        assertEquals("Seg 2 absolut [10,20]", 20f, result.mappedSegments[1].endSec, 0.001f)
        // Mapping neu: 0→0, 1→1 (kein Bestand)
        assertEquals("Mapping 0→0, 1→1", mapOf(0 to 0, 1 to 1), result.mapping)
        assertEquals("2 globale Segmente im Bestand", 2, result.allGlobalSegments.size)
    }

    @Test
    fun `zweiter Chunk - Time-Shift um 15s und Reconcile gegen Bestand`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(5000) // 50s

        // Chunk 1: [0,20], Speaker 0 durchgehend
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 20f)),
            // Chunk 2: [15,40], Engine nennt denselben Speaker wieder "0" (kein Drift)
            listOf(localSeg(0, 0f, 25f)), // absolut: [15,40]
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        val first = worker.processNextChunk(debug = false)
        assertNotNull(first)

        val second = worker.processNextChunk(debug = false)
        assertNotNull("Chunk 2 verfügbar", second)
        second!!
        assertEquals("Chunk 2 startet bei 15s (20-5 Overlap)", 15f, second.chunk.startSec, 0.001f)
        assertEquals("Overlap 5s", 5f, second.chunk.overlapSec, 0.001f)
        // Time-Shift: Engine-lokal [0,25] → absolut [15,40]
        assertEquals("Start absolut 15s", 15f, second.mappedSegments[0].startSec, 0.001f)
        assertEquals("Ende absolut 40s", 40f, second.mappedSegments[0].endSec, 0.001f)
        // Reconcile: Overlap-Zone [15,20] matcht Speaker 0 → bleibt 0, kein neuer Speaker
        assertEquals("Mapping 0→0", mapOf(0 to 0), second.mapping)
        assertTrue("kein neuer Speaker", second.newSpeakerIds.isEmpty())
    }

    @Test
    fun `Drift im zweiten Chunk wird aufgeloest - Speaker bleibt global stabil`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(5000)

        // Chunk 1: [0,20] → Speaker 0
        // Chunk 2: Engine VERTAUSCHT die ID: Speaker 1 ist derselbe Sprecher wie vorher 0
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 20f)),
            listOf(localSeg(1, 0f, 25f)), // Engine nennt ihn jetzt 1
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        worker.processNextChunk(debug = false)
        val second = worker.processNextChunk(debug = false)
        assertNotNull(second)
        second!!

        // Reconciler matcht lokal 1 über die Zone [15,20] auf global 0 → keine neue ID
        assertEquals("Drift aufgelöst: 1→0", mapOf(1 to 0), second.mapping)
        assertTrue("keine künstliche neue Speaker-ID", second.newSpeakerIds.isEmpty())
        assertEquals("mapped Speaker global 0", 0, second.mappedSegments[0].speaker)
    }

    @Test
    fun `neuer Speaker ausserhalb der Zone wird neue globale ID`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(5000)

        // Chunk 1: [0,20] → Speaker 0
        // Chunk 2: Speaker 0 bleibt, Speaker 2 ist NEU (beginnt nach der Zone bei absolut 20s+)
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 20f)),
            listOf(localSeg(0, 0f, 5f), localSeg(2, 5f, 25f)), // absolut: [15,20] u. [20,40]
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        worker.processNextChunk(debug = false)
        val second = worker.processNextChunk(debug = false)
        assertNotNull(second)
        second!!

        // Speaker 0 (Zone) → global 0; Speaker 2 (nach Zone) → neue ID 1
        assertEquals("Mapping 0→0, 2→1", mapOf(0 to 0, 2 to 1), second.mapping)
        assertTrue("neuer Speaker ist ID 2 (lokal)", second.newSpeakerIds == setOf(2))
        assertEquals("neuer globaler Speaker = 1", 1, second.mappedSegments[1].speaker)
    }

    @Test
    fun `State-Update - Zone wird ersetzt, aeltere Segmente bleiben`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(5000)

        // Chunk 1: [0,10] Spk 0, [10,20] Spk 1
        // Chunk 2: [15,40] → Spk 0 in [15,20] (Zone, matcht global 0), Spk 1 in [20,40]
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 10f), localSeg(1, 10f, 20f)),
            listOf(localSeg(0, 0f, 5f), localSeg(1, 5f, 25f)),
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        val first = worker.processNextChunk(debug = false)
        assertNotNull(first)
        // Bestand nach Chunk 1: [0,10]→0, [10,20]→1
        assertEquals("Bestand nach Chunk 1: 2 Segmente", 2, worker.globalSegments.size)

        val second = worker.processNextChunk(debug = false)
        assertNotNull(second)

        // Bestand nach Chunk 2:
        // - [0,10] Spk 0 bleibt (vor Zone)
        // - [10,20] Spk 1 wird durch Zone-Ersetzung entfernt (Zone [15,20] berührt ihn)
        // - Neu: [15,20]→0 (Zone), [20,40]→1
        val bestand = worker.globalSegments
        assertEquals("3 Segmente im Bestand (1 alt + 2 neu)", 3, bestand.size)
        assertEquals("altes Segment [0,10] bleibt", 0f, bestand[0].startSec, 0.001f)
        assertEquals("altes Segment Speaker 0", 0, bestand[0].speakerId)
        assertEquals("neues Segment startet bei 15s", 15f, bestand[1].startSec, 0.001f)
        assertEquals("keine Überlappung/Dopplung in Zone [15,20]", 15f, bestand[1].startSec, 0.001f)
        assertEquals("Timeline lückenlos sortiert", true,
            bestand.zipWithNext().all { (a, b) -> a.endSec <= b.startSec + 0.01f })
    }

    @Test
    fun `zu wenig Audio - null`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(1000) // 10s < 20s Chunk

        val fake = FakeDiarizer(ArrayDeque())
        val worker = DiarizationChunkWorker(buffer, fake)

        assertNull("kein Chunk bei < 20s Audio", worker.processNextChunk(debug = false))
    }

    @Test
    fun `Engine liefert 0 Segmente - Bestand bleibt unveraendert`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(5000)

        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 20f)),
            emptyList(), // Chunk 2: Engine degradiert → 0 Segmente
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        worker.processNextChunk(debug = false)
        val bestandVorher = worker.globalSegments.size

        val second = worker.processNextChunk(debug = false)
        assertNotNull("Chunk wird trotzdem geliefert", second)
        second!!
        assertTrue("mappedSegments leer", second.mappedSegments.isEmpty())
        assertEquals("Bestand unverändert", bestandVorher, worker.globalSegments.size)
    }

    @Test
    fun `reset leert den globalen Bestand`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(3000)

        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 20f)),
        )))
        val worker = DiarizationChunkWorker(buffer, fake)
        worker.processNextChunk(debug = false)
        assertEquals("Bestand gefüllt", 1, worker.globalSegments.size)

        worker.reset()
        assertTrue("Bestand leer nach reset", worker.globalSegments.isEmpty())
    }
}
