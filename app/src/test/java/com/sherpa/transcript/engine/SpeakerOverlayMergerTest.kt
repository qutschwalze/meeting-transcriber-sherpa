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
}