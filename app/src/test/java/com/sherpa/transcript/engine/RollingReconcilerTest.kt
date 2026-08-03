package com.sherpa.transcript.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests für RollingReconciler (Permutation-Problem / Speaker-Drift).
 *
 * Setup: Session-Zeit in Sekunden, Overlap-Zone z.B. [15s, 20s)
 * (letzte 5s von Chunk A = Kontext für Chunk B).
 */
class RollingReconcilerTest {

    /** Erzeugt ein bestätigtes globales Segment (Chunk A / Bestand). */
    private fun globalSeg(speaker: Int, startSec: Float, endSec: Float) =
        SpeakerTimeRange(startSec = startSec, endSec = endSec, speakerId = speaker)

    /** Erzeugt ein lokales Diarization-Segment aus Chunk B (absolute Zeiten). */
    private fun localSeg(speaker: Int, startSec: Float, endSec: Float) =
        DiarizationSegment(startSec = startSec, endSec = endSec, speaker = speaker)

    private val zone = TimeRange(startSec = 15f, endSec = 20f)

    @Test
    fun `1 zu 1 Match - gleiche ID wird beibehalten`() {
        val reconciler = RollingReconciler()
        val previous = listOf(
            globalSeg(speaker = 0, startSec = 5f, endSec = 20f),
        )
        val local = listOf(
            localSeg(speaker = 0, startSec = 15f, endSec = 35f),
        )

        val result = reconciler.reconcile(local, zone, previous, debug = false)

        assertEquals("Mapping 0→0", mapOf(0 to 0), result.mapping)
        assertTrue("keine neuen Speaker", result.newSpeakerIds.isEmpty())
        assertEquals("Segment bleibt Speaker 0", 0, result.mappedSegments[0].speaker)
    }

    @Test
    fun `neuer Sprecher in Chunk B wird neue globale ID`() {
        val reconciler = RollingReconciler()
        val previous = listOf(
            globalSeg(speaker = 0, startSec = 5f, endSec = 20f),
        )
        val local = listOf(
            localSeg(speaker = 0, startSec = 15f, endSec = 22f), // matcht global 0
            localSeg(speaker = 1, startSec = 25f, endSec = 30f), // außerhalb Zone → neu
        )

        val result = reconciler.reconcile(local, zone, previous, debug = false)

        assertEquals("Mapping 0→0, 1→1", mapOf(0 to 0, 1 to 1), result.mapping)
        assertEquals("lokale ID 1 ist neu", setOf(1), result.newSpeakerIds)
        assertEquals("Segment 2 → Speaker 1", 1, result.mappedSegments[1].speaker)
    }

    @Test
    fun `Sprecherwechsel genau im Overlap - Permutation wird aufgeloest`() {
        // Drift-Szenario: Die Engine hat in Chunk B die IDs VERTAUSCHT.
        // Global: Speaker 0 = Person A (früh), Speaker 1 = Person B (spät)
        // Lokal:  Speaker 0 = Person B (Engine nennt B jetzt 0), Speaker 1 = Person A
        val reconciler = RollingReconciler()
        val previous = listOf(
            globalSeg(speaker = 0, startSec = 10f, endSec = 17.5f), // Person A
            globalSeg(speaker = 1, startSec = 17.5f, endSec = 22f), // Person B
        )
        val local = listOf(
            localSeg(speaker = 1, startSec = 15f, endSec = 17.5f), // Person A (lokal als 1)
            localSeg(speaker = 0, startSec = 17.5f, endSec = 22f), // Person B (lokal als 0)
        )

        val result = reconciler.reconcile(local, zone, previous, debug = false)

        // Permutation korrekt aufgelöst: lokal 0 → global 1, lokal 1 → global 0
        assertEquals("Mapping 0→1, 1→0", mapOf(0 to 1, 1 to 0), result.mapping)
        assertTrue("keine neuen Speaker bei Permutation", result.newSpeakerIds.isEmpty())
        // mappedSegments[0] = Person A (lokal als 1) → global 0
        assertEquals("Person A bleibt global 0", 0, result.mappedSegments[0].speaker)
        // mappedSegments[1] = Person B (lokal als 0) → global 1
        assertEquals("Person B bleibt global 1", 1, result.mappedSegments[1].speaker)
    }

    @Test
    fun `Confidence-Gate - Overlap unter 500ms wird neuer Speaker`() {
        val reconciler = RollingReconciler(minMatchOverlapSec = 0.5f)
        val previous = listOf(
            globalSeg(speaker = 0, startSec = 5f, endSec = 20f),
        )
        // Lokal 0 überlappt global 0 nur 0.2s (19.8–20.0) → unter Gate
        val local = listOf(
            localSeg(speaker = 0, startSec = 19.8f, endSec = 25f),
        )

        val result = reconciler.reconcile(local, zone, previous, debug = false)

        assertTrue("kein Match unter 500ms", result.mapping[0] != 0)
        assertEquals("wird neue globale ID 1", 1, result.mapping[0])
        assertTrue("als neuer Speaker markiert", 0 in result.newSpeakerIds)
    }

    @Test
    fun `Default-Gate 300ms - Overlap 400ms wird gematcht, 200ms nicht`() {
        val reconciler = RollingReconciler() // Default = 0.3f
        val previous = listOf(
            globalSeg(speaker = 0, startSec = 5f, endSec = 20f),
        )

        // 400ms Overlap (19.6–20.0) → über Default-Gate (300ms) → Match
        val match = reconciler.reconcile(
            listOf(localSeg(speaker = 0, startSec = 19.6f, endSec = 25f)),
            zone, previous, debug = false,
        )
        assertEquals("400ms Overlap matcht", 0, match.mapping[0])

        // 200ms Overlap (19.8–20.0) → unter Default-Gate → neuer Speaker
        val noMatch = reconciler.reconcile(
            listOf(localSeg(speaker = 1, startSec = 19.8f, endSec = 25f)),
            zone, previous, debug = false,
        )
        assertEquals("200ms Overlap matcht nicht", 1, noMatch.mapping[1])
        assertTrue("als neuer Speaker markiert", 1 in noMatch.newSpeakerIds)
    }

    @Test
    fun `Greedy - bei Kollision gewinnt der groesste Overlap`() {
        val reconciler = RollingReconciler()
        val previous = listOf(
            globalSeg(speaker = 0, startSec = 10f, endSec = 20f),
        )
        // Beide lokalen IDs voten auf global 0, aber lokal 0 hat mehr Overlap
        val local = listOf(
            localSeg(speaker = 0, startSec = 15f, endSec = 18f), // 3s Overlap
            localSeg(speaker = 1, startSec = 18.2f, endSec = 20f), // 1.8s Overlap
        )

        val result = reconciler.reconcile(local, zone, previous, debug = false)

        assertEquals("lokal 0 gewinnt global 0", 0, result.mapping[0])
        assertEquals("lokal 1 wird neu", 1, result.mapping[1])
        assertTrue("lokal 1 als neu markiert", 1 in result.newSpeakerIds)
    }

    @Test
    fun `erster Chunk ohne Bestand - alle IDs neu ab 0`() {
        val reconciler = RollingReconciler()
        val local = listOf(
            localSeg(speaker = 0, startSec = 0f, endSec = 10f),
            localSeg(speaker = 1, startSec = 10f, endSec = 20f),
        )

        val result = reconciler.reconcile(local, zone, emptyList(), debug = false)

        assertEquals("Mapping 0→0, 1→1 ohne Bestand", mapOf(0 to 0, 1 to 1), result.mapping)
        assertTrue("beide neu", result.newSpeakerIds == setOf(0, 1))
    }

    @Test
    fun `leere Eingaben - leeres Ergebnis`() {
        val reconciler = RollingReconciler()
        val result = reconciler.reconcile(emptyList(), zone, emptyList(), debug = false)
        assertTrue(result.mappedSegments.isEmpty())
        assertTrue(result.mapping.isEmpty())
        assertTrue(result.newSpeakerIds.isEmpty())
    }
}
