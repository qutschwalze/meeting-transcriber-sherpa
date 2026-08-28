package com.sherpa.transcript.domain.export

import com.sherpa.transcript.data.local.SegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.11.0: Sprecher-Statistik – Gruppierung, Prozent, Sortierung, Unbekannt. */
class SpeakerStatsTest {

    private fun seg(label: String?, name: String?, start: Long, end: Long) =
        SegmentEntity(
            segmentId = "s$start",
            transcriptId = "t",
            startTimeMs = start,
            endTimeMs = end,
            text = "x",
            speakerId = if (label != null) "speaker_1" else null,
            speakerLabel = label,
            speakerName = name,
        )

    @Test
    fun `gruppiert nach Profilname und berechnet Prozente`() {
        val segments = listOf(
            seg("Sprecher 1", "Anna", 0, 30_000),   // 30s
            seg("Sprecher 1", "Anna", 40_000, 60_000), // 20s → Anna 50s
            seg("Sprecher 2", "Ben", 70_000, 90_000),  // 20s → Ben 20s
        )
        val stats = SpeakerStats.compute(segments)
        assertEquals(2, stats.size)
        assertEquals("Anna", stats[0].label)
        assertEquals(50_000L, stats[0].totalMs)
        assertEquals(2, stats[0].segmentCount)
        assertEquals(71, stats[0].percent)   // 50/70 = 71%
        assertEquals("Ben", stats[1].label)
        assertEquals(29, stats[1].percent)   // 20/70 = 29%
    }

    @Test
    fun `ungelabelte Segmente werden als Unbekannt ausgewiesen`() {
        val segments = listOf(
            seg("Sprecher 1", null, 0, 10_000),
            seg(null, null, 10_000, 30_000), // unbekannt, 20s
        )
        val stats = SpeakerStats.compute(segments)
        assertEquals(2, stats.size)
        assertEquals("Unbekannt", stats[0].label)
        assertEquals(67, stats[0].percent)
    }

    @Test
    fun `sortiert nach Redezeit absteigend`() {
        val segments = listOf(
            seg("A", null, 0, 5_000),
            seg("B", null, 10_000, 60_000),
            seg("C", null, 60_000, 65_000),
        )
        assertEquals(listOf("B", "A", "C"), SpeakerStats.compute(segments).map { it.label })
    }

    @Test
    fun `leere oder Nulldauer-Segmente liefern leere Liste`() {
        assertEquals(emptyList<SpeakerStat>(), SpeakerStats.compute(emptyList()))
        assertEquals(emptyList<SpeakerStat>(), SpeakerStats.compute(listOf(seg("A", null, 5, 5))))
    }

    @Test
    fun `Formatierung min vs sekunden`() {
        assertEquals("7 min", SpeakerStats.formatDurationMs(420_000))
        assertEquals("42 s", SpeakerStats.formatDurationMs(42_000))
    }
}