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
            // removeFirstOrNull: Chunk-Retry (2. Engine-Versuch) liefert leer,
            // wenn keine weiteren Ergebnisse vordefiniert sind
            return results.removeFirstOrNull() ?: emptyList()
        }
    }

    /** Fake-Embedding-Computer: Sample-Wert bestimmt die "Stimme" (1f=A, 2f=B). */
    private class ValueComputer : SpeakerEmbeddingComputer {
        override fun computeEmbedding(samples: FloatArray): FloatArray? {
            if (samples.isEmpty()) return null
            val v = samples[0]
            return when {
                v <= 1.5f -> floatArrayOf(1f, 0f, 0f) // Stimme A
                v <= 2.5f -> floatArrayOf(0f, 1f, 0f) // Stimme B
                else -> floatArrayOf(0f, 0f, 1f)
            }
        }
    }

    private fun valueFrame(value: Float): FloatArray = FloatArray(frameSamples) { value }

    private fun ChunkedAudioBuffer.pushValueFrames(value: Float, count: Int, startMs: Long = 0L) {
        for (i in 0 until count) {
            push(valueFrame(value), startMs + i * 10L)
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
        // Chunk 2: [15,40] → Spk 1 in [15,20] (Zone, matcht global 1), Spk 0 in [20,40] (neu)
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 10f), localSeg(1, 10f, 20f)),
            listOf(localSeg(1, 0f, 5f), localSeg(0, 5f, 25f)),
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        val first = worker.processNextChunk(debug = false)
        assertNotNull(first)
        // Bestand nach Chunk 1: [0,10]→0, [10,20]→1
        assertEquals("Bestand nach Chunk 1: 2 Segmente", 2, worker.globalSegments.size)

        val second = worker.processNextChunk(debug = false)
        assertNotNull(second)

        // Bestand nach Chunk 2 (Zone [15,20]):
        // - [0,10] Spk 0 bleibt unverändert (endet vor Zone)
        // - [10,20] Spk 1 wird an der Zonengrenze auf [10,15] ZUGESCHNITTEN
        // - neu: [15,20]→1 (Zone, matcht global 1), [20,40]→2 (neuer Speaker)
        val bestand = worker.globalSegments
        assertEquals("4 Segmente im Bestand (2 alt/zugeschnitten + 2 neu)", 4, bestand.size)
        assertEquals("altes Segment [0,10] bleibt", 0f, bestand[0].startSec, 0.001f)
        assertEquals("altes Segment Speaker 0", 0, bestand[0].speakerId)
        assertEquals("zugeschnittenes Segment endet an Zonengrenze 15s", 15f, bestand[1].endSec, 0.01f)
        assertEquals("zugeschnittenes Segment Speaker 1", 1, bestand[1].speakerId)
        assertEquals("Zonen-Segment startet bei 15s", 15f, bestand[2].startSec, 0.001f)
        assertEquals("Zonen-Segment gematcht auf global 1", 1, bestand[2].speakerId)
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

    @Test
    fun `processFinalChunk liefert konsolidierten Gesamtbestand statt nur Rest`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(3000) // 30s
        buffer.pushFrames(600, startMs = 30_000L) // Rest bis 36s

        // Chunk 1: [0,20] → Speaker 0
        // Final-Rest: [15,36] (5s Overlap + 6s neues Audio) → Speaker 0 bleibt
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 20f)),
            listOf(localSeg(0, 0f, 21f)), // absolut: [15,36]
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        worker.processNextChunk(debug = false)
        val final = worker.processFinalChunk(debug = false)
        assertNotNull("Final-Chunk verfügbar", final)
        final!!
        assertEquals("Mapping stabil 0→0", mapOf(0 to 0), final.mapping)
        // mappedSegments = KOMPLETTER Bestand: [0,15] (zugeschnitten) + [15,36]
        assertEquals("Gesamtbestand als mappedSegments", 2, final.mappedSegments.size)
        assertEquals("Segment 1 beginnt bei 0s", 0f, final.mappedSegments[0].startSec, 0.001f)
        assertEquals("Segment 1 endet an Zonengrenze 15s", 15f, final.mappedSegments[0].endSec, 0.01f)
        assertEquals("Segment 2 reicht bis 36s", 36f, final.mappedSegments[1].endSec, 0.001f)
        assertEquals("Bestand wächst auf 2", 2, worker.globalSegments.size)
    }

    @Test
    fun `processFinalChunk verarbeitet mehrere volle Chunks bei langem Rest`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(6500) // 65s

        // Chunk 1: [0,20] → Spk 0
        // Chunk 2 (Final-Schleife): [15,40] → Spk 0 bleibt (Zone-Match)
        // Chunk 3 (Final-Rest):    [35,60] → Spk 0 bleibt (Zone-Match)
        // Es bleibt Rest bis 65s → [60,65] wird per takeRemainingChunk begrenzt
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 20f)),
            listOf(localSeg(0, 0f, 25f)), // absolut: [15,40]
            listOf(localSeg(0, 0f, 25f)), // absolut: [35,60]
            listOf(localSeg(0, 0f, 10f)), // absolut: [55,65] – Rest (Chunk [55,65])
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        worker.processNextChunk(debug = false)
        val final = worker.processFinalChunk(debug = false)
        assertNotNull("Final verfügbar", final)
        final!!
        // Bestand: [0,15] + [15,40] + [40,60] + [60,65] (jeweils Zonen-Zuschnitt)
        assertEquals("alle Chunks konsolidiert", 4, final.mappedSegments.size)
        assertEquals("letztes Segment endet bei 65s", 65f, final.mappedSegments.last().endSec, 0.01f)
        assertEquals("alle Speaker stabil 0", setOf(0), final.mappedSegments.map { it.speaker }.toSet())
    }

    @Test
    fun `processFinalChunk liefert null ohne neues Audio seit letztem Chunk`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(2000) // exakt 20s – Chunk 1 nimmt alles

        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 20f)),
        )))
        val worker = DiarizationChunkWorker(buffer, fake)
        worker.processNextChunk(debug = false)

        assertNull("nichts Neues → kein Final-Chunk", worker.processFinalChunk(debug = false))
    }

    // ── Hebel G: Voice-Bank-Integration ──

    @Test
    fun `0 Segmente vom ersten Engine-Lauf werden per Chunk-Retry gerettet`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(3000) // 30s – Chunk 1 = [0,20]

        // 1. Engine-Lauf: 0 Segmente (Pyannote-VAD-Aussetzer)
        // Retry-Offsets [3,5,7,10]: 3s und 5s liefern auch 0, erst 7s findet Sprecher
        // Segment [0,5] relativ zum Retry-Start (7s) → absolut [7,12]
        val fake = FakeDiarizer(ArrayDeque(listOf(
            emptyList(),                    // Original-Lauf
            emptyList(),                    // Retry 3s
            emptyList(),                    // Retry 5s
            listOf(localSeg(0, 0f, 5f)),    // Retry 7s → SUCCESS
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        val result = worker.processNextChunk(debug = false)
        assertNotNull("Retry liefert Ergebnis statt null", result)
        result!!
        assertEquals("Segment existiert nach Retry", 1, result.mappedSegments.size)
        // Time-Shift: Offset 7s muss herausgerechnet sein → absolut [7,12]
        assertEquals("Start nach Offset-Korrektur", 7f, result.mappedSegments[0].startSec, 0.01f)
        assertEquals("Ende nach Offset-Korrektur", 12f, result.mappedSegments[0].endSec, 0.01f)
    }

    @Test
    fun `Chunk-Retry liefert weiterhin 0 wenn alle Engine-Lauefe leer sind`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushFrames(3000) // 30s – Chunk 1 = [0,20]

        // Original + 4 Retry-Offsets (3/5/7/10s) – alle leer
        val fake = FakeDiarizer(ArrayDeque(listOf(
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )))
        val worker = DiarizationChunkWorker(buffer, fake)

        val result = worker.processNextChunk(debug = false)
        assertNotNull(result)
        result!!
        assertEquals("0 Segmente trotz aller Retries", 0, result.mappedSegments.size)
    }

    @Test
    fun `VoiceBank - Engine-Drift wird akustisch auf globalen Speaker zurueckgemappt`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        // 0-20s Stimme A, 15-40s weiterhin Stimme A (gleiche Person!)
        buffer.pushValueFrames(1f, 2000)                 // 0-20s
        buffer.pushValueFrames(1f, 2500, startMs = 15_000L) // 15-40s

        // Chunk 1 [0,20]: Engine findet Speaker 0 NUR in [0,10]
        // Chunk 2 [15,40]: Engine DRIFTET – nennt dieselbe Stimme lokal 1
        // → Zone [15,20] hat keinen Anker von global 0 (Bestand endet bei 10s)
        // → Reconciler würde neue ID vergeben, aber die BANK erkennt Stimme A
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 10f)),  // absolut [0,10]
            listOf(localSeg(1, 0f, 25f)),  // absolut [15,40], Drift!
        )))
        val voiceBank = SessionVoiceBank(ValueComputer())
        val worker = DiarizationChunkWorker(buffer, fake, voiceBank = voiceBank)

        val first = worker.processNextChunk(debug = false)
        assertNotNull(first)
        assertEquals("Speaker 0 nach 1. Kontakt: pending, noch nicht eingeschrieben", 0, voiceBank.speakerCount)
        assertEquals("1 pending Enrollment (2-Kontakt-Härtung)", 1, voiceBank.pendingCount)

        val second = worker.processNextChunk(debug = false)
        assertNotNull(second)
        second!!
        assertEquals("Drift aufgelöst: lokal 1 → global 0 (nicht neue ID 1)",
            mapOf(1 to 0), second.mapping)
        assertTrue("keine neue Speaker-ID nach Bank-Auflösung", second.newSpeakerIds.isEmpty())
        assertEquals("Segment auf global 0 gemappt", 0, second.mappedSegments[0].speaker)
        assertEquals("2. Kontakt bestätigt: Bank hat jetzt 1 Sprecher",
            1, voiceBank.speakerCount)
        assertEquals("pending durch Bestätigung aufgelöst", 0, voiceBank.pendingCount)
    }

    @Test
    fun `VoiceBank - wirklich neuer Sprecher wird eingeschrieben`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        // 0-20s Stimme A, 20-40s Stimme B (NEU) – Overlap-Zone [15,20] bleibt Stimme A
        buffer.pushValueFrames(1f, 2000)                 // 0-20s
        buffer.pushValueFrames(2f, 2000, startMs = 20_000L) // 20-40s

        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 10f)),  // absolut [0,10] Stimme A
            listOf(localSeg(1, 5f, 25f)),  // absolut [20,40] Stimme B (nach Zone!)
        )))
        val voiceBank = SessionVoiceBank(ValueComputer())
        val worker = DiarizationChunkWorker(buffer, fake, voiceBank = voiceBank)

        worker.processNextChunk(debug = false)
        val second = worker.processNextChunk(debug = false)
        assertNotNull(second)
        second!!

        assertEquals("B bleibt neue ID 1", mapOf(1 to 1), second.mapping)
        assertTrue("B ist neuer Speaker", second.newSpeakerIds == setOf(1))
        // 2-Kontakt-Härtung: B ist nach 1. Kontakt nur pending – kein Voiceprint
        assertEquals("kein bestätigter Sprecher nach 1. Kontakt", 0, voiceBank.speakerCount)
        assertEquals("2 pending Enrollments (A aus Chunk 1, B aus Chunk 2)", 2, voiceBank.pendingCount)
    }

    @Test
    fun `VoiceBank - zweiter Kontakt derselben Stimme bestaetigt Enrollment`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        // 0-20s Stimme A, 20-60s Stimme B (durchgehend)
        buffer.pushValueFrames(1f, 2000)                 // 0-20s
        buffer.pushValueFrames(2f, 4000, startMs = 20_000L) // 20-60s

        // Chunks (20s + 5s Overlap): [0,20], [15,40], [35,60]
        // Chunk 1: A in [0,10] → pending 0 (Stimme A)
        // Chunk 2: B in [20,35] (Zone [15,20] ohne Anker) → neue ID 1 → pending 1
        // Chunk 3: B in [35,55] (Zone [35,40] ohne Anker, Bestand endet bei 35)
        //   → Reconciler will neue ID 2, aber Bank matcht auf pending 1 → CONFIRMED
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 10f)),   // absolut [0,10] Stimme A
            listOf(localSeg(1, 5f, 20f)),   // absolut [20,35] Stimme B
            listOf(localSeg(0, 0f, 20f)),   // absolut [35,55] Stimme B erneut
        )))
        val voiceBank = SessionVoiceBank(ValueComputer())
        val worker = DiarizationChunkWorker(buffer, fake, voiceBank = voiceBank)

        worker.processNextChunk(debug = false)  // Chunk 1: A pending
        val second = worker.processNextChunk(debug = false) // Chunk 2: B pending
        assertNotNull(second)
        assertEquals("A + B nach Chunk 2 pending", 2, voiceBank.pendingCount)

        val third = worker.processNextChunk(debug = false)  // Chunk 3: B 2. Kontakt
        assertNotNull(third)
        third!!
        assertEquals("2. Kontakt von B bestätigt Enrollment", 1, voiceBank.speakerCount)
        assertTrue("B eingeschrieben", voiceBank.enrolledSpeakerIds == setOf(1))
        assertEquals("A bleibt pending (nur 1 Kontakt)", 1, voiceBank.pendingCount)
    }

    @Test
    fun `VoiceBank - kurze Segmente unter Enrollment-Gate werden nicht eingeschrieben`() {
        val buffer = ChunkedAudioBuffer(sampleRate = sampleRate)
        buffer.pushValueFrames(1f, 2000) // 0-20s Stimme A

        // Engine liefert nur ein 2s-Fragment → unter minEnrollmentSec (5s)
        val fake = FakeDiarizer(ArrayDeque(listOf(
            listOf(localSeg(0, 0f, 2f)), // absolut [0,2] – nur 2s
        )))
        val voiceBank = SessionVoiceBank(ValueComputer(), minEnrollmentSec = 5f)
        val worker = DiarizationChunkWorker(buffer, fake, voiceBank = voiceBank)

        worker.processNextChunk(debug = false)
        assertEquals("2s-Fragment wird nicht enrolled", 0, voiceBank.speakerCount)
    }
}
