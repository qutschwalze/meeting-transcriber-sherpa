package com.sherpa.transcript.engine

import com.sherpa.transcript.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.10.7: Testet die Save-Zeit-Konsolidierung von Session-GIDs, deren
 * Global-Profile Duplikate derselben Stimme sind (VB_DUP_MERGE).
 */
class SpeakerOverlayMergerTest {

    private fun seg(id: String, speaker: String?, start: Long, end: Long) =
        TranscriptSegment(segmentId = id, text = "t", startTimeMs = start, endTimeMs = end, speakerId = speaker)

    /** Fake-Similarity: zwei Profile sind "Duplikate", wenn sie dieselbe Stimme markieren. */
    private fun fakeSim(dupPairs: Set<Pair<String, String>>): (String, String) -> Float? = { a, b ->
        if (a == b) 1f
        else if (dupPairs.contains(a to b) || dupPairs.contains(b to a)) 0.70f
        else 0.30f
    }

    @Test
    fun `zwei GIDs auf Duplikat-Profilen werden auf den redezeit-staerksten gemerged`() {
        val overlay = listOf(
            seg("a", "speaker_7", 0, 10_000),
            seg("b", "speaker_7", 10_000, 18_000),   // gid 7: 18s
            seg("c", "speaker_20", 40_000, 45_000),  // gid 20: 5s → primary 7
        )
        val profileByGid = mapOf(7 to "P-B", 20 to "P-B2")
        val result = SpeakerOverlayMerger.mergeDuplicateProfileGids(overlay, profileByGid, fakeSim(setOf("P-B" to "P-B2")))
        assertEquals(listOf("speaker_7", "speaker_7", "speaker_7"), result.map { it.speakerId })
    }

    @Test
    fun `GIDs unterhalb der Schwelle werden nicht gemerged`() {
        val overlay = listOf(
            seg("a", "speaker_7", 0, 10_000),
            seg("c", "speaker_4", 40_000, 45_000),
        )
        val profileByGid = mapOf(7 to "P-B", 4 to "P-X")
        // keine Duplikat-Paare → fakeSim liefert 0.30
        val result = SpeakerOverlayMerger.mergeDuplicateProfileGids(overlay, profileByGid, fakeSim(emptySet()))
        assertEquals(listOf("speaker_7", "speaker_4"), result.map { it.speakerId })
    }

    @Test
    fun `transitive Gruppen werden zusammengefuehrt (A~B und B~C)`() {
        val overlay = listOf(
            seg("a", "speaker_1", 0, 10_000),
            seg("b", "speaker_7", 20_000, 30_000),
            seg("c", "speaker_20", 40_000, 50_000),
        )
        val profileByGid = mapOf(1 to "P-A", 7 to "P-B", 20 to "P-C")
        // alle drei gleich lang → primary = erster in Sortierreihenfolge (1)
        val result = SpeakerOverlayMerger.mergeDuplicateProfileGids(
            overlay, profileByGid, fakeSim(setOf("P-A" to "P-B", "P-B" to "P-C"))
        )
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_1"), result.map { it.speakerId })
    }

    @Test
    fun `GID ohne Profil-Zuordnung bleibt unangetastet`() {
        val overlay = listOf(
            seg("a", "speaker_1", 0, 10_000),   // profil-los (Sammel-GID)
            seg("b", "speaker_7", 20_000, 30_000),
            seg("c", "speaker_20", 40_000, 50_000),
        )
        val profileByGid = mapOf(7 to "P-B", 20 to "P-B2")
        val result = SpeakerOverlayMerger.mergeDuplicateProfileGids(overlay, profileByGid, fakeSim(setOf("P-B" to "P-B2")))
        assertEquals(listOf("speaker_1", "speaker_7", "speaker_7"), result.map { it.speakerId })
    }

    @Test
    fun `leere Profil-Map oder Overlay ohne GIDs aendert nichts`() {
        val overlay = listOf(seg("a", "speaker_0", 0, 1000), seg("b", "speaker_1", 1000, 2000))
        assertEquals(overlay, SpeakerOverlayMerger.mergeDuplicateProfileGids(overlay, emptyMap(), fakeSim(emptySet())))
        val noGids = listOf(seg("a", null, 0, 1000))
        assertEquals(noGids, SpeakerOverlayMerger.mergeDuplicateProfileGids(noGids, mapOf(0 to "P"), fakeSim(emptySet())))
    }

    @Test
    fun `fehlende Profile in der Bank liefern null und werden uebersprungen`() {
        val overlay = listOf(seg("a", "speaker_0", 0, 10_000), seg("b", "speaker_1", 20_000, 30_000))
        val result = SpeakerOverlayMerger.mergeDuplicateProfileGids(
            overlay, mapOf(0 to "P-0", 1 to "P-1"), { _, _ -> null }
        )
        assertTrue(result.map { it.speakerId } == listOf("speaker_0", "speaker_1"))
    }

    // ── 0.11.0: Mini-Segment-Regel ──────────────────────────────────────

    @Test
    fun `bank-loses Fragment unter 8s wandert zum zeitlich naechsten Sprecher`() {
        val overlay = listOf(
            seg("a", "speaker_0", 0, 10_000),
            seg("b", "speaker_6", 12_000, 16_000), // Fragment 4s, Profil-los
            seg("c", "speaker_4", 18_000, 40_000),
        )
        val result = SpeakerOverlayMerger.mergeMiniFragments(overlay, mapOf(0 to "P-A", 4 to "P-B"))
        // b liegt zwischen spk0 und spk4 – naeher an spk0 (2s vs 2s → spk0 gewinnt bei gleichem Abstand, hier erster Treffer)
        assertEquals("speaker_0", result[1].speakerId)
    }

    @Test
    fun `Fragment mit Profil-Zuordnung bleibt unangetastet`() {
        val overlay = listOf(
            seg("a", "speaker_0", 0, 10_000),
            seg("b", "speaker_6", 12_000, 16_000),
            seg("c", "speaker_4", 18_000, 40_000),
        )
        // gid 6 hat ein Profil → KEIN Fragment
        val result = SpeakerOverlayMerger.mergeMiniFragments(overlay, mapOf(0 to "P-A", 6 to "P-6", 4 to "P-B"))
        assertEquals("speaker_6", result[1].speakerId)
    }

    @Test
    fun `Sprecher mit ueber 8s Gesamtredezeit bleibt unangetastet`() {
        val overlay = listOf(
            seg("a", "speaker_6", 0, 5_000),
            seg("b", "speaker_6", 6_000, 10_000), // gid 6 gesamt 9s → kein Fragment
            seg("c", "speaker_0", 12_000, 20_000),
        )
        val result = SpeakerOverlayMerger.mergeMiniFragments(overlay, mapOf(0 to "P-A"))
        assertEquals(listOf("speaker_6", "speaker_6", "speaker_0"), result.map { it.speakerId })
    }

    @Test
    fun `Fragment am Anfang wandert zum einzigen Nachbarn`() {
        val overlay = listOf(
            seg("a", "speaker_6", 0, 4_000),
            seg("b", "speaker_0", 10_000, 20_000),
        )
        val result = SpeakerOverlayMerger.mergeMiniFragments(overlay, mapOf(0 to "P-A"))
        assertEquals("speaker_0", result[0].speakerId)
    }

    @Test
    fun `nur Fragmente ohne jeden gueltigen Nachbarn bleiben`() {
        val overlay = listOf(
            seg("a", "speaker_6", 0, 4_000),  // Fragment
            seg("b", "speaker_7", 10_000, 12_000), // Fragment (2s, bank-los)
        )
        val result = SpeakerOverlayMerger.mergeMiniFragments(overlay, emptyMap())
        // beide sind Fragmente, keiner ist gueltiger Nachbar → unveraendert
        assertEquals(listOf("speaker_6", "speaker_7"), result.map { it.speakerId })
    }
}