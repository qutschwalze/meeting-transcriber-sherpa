package com.sherpa.transcript.domain.export

import com.sherpa.transcript.data.local.SegmentEntity

/**
 * 0.11.0: Sprecher-Statistik für den Detail-Screen (reine Berechnung, JVM-testbar).
 *
 * Gruppiert alle Segmente eines Transkripts nach Anzeige-Name
 * (speakerName, sonst speakerLabel; ungelabelte = "Unbekannt") und
 * zählt Redezeit + Segmentzahl. Sortiert nach Redezeit absteigend.
 */
data class SpeakerStat(
    val label: String,
    val totalMs: Long,
    val segmentCount: Int,
    /** Anteil an der Gesamt-Redezeit, 0..100 (gerundet). */
    val percent: Int,
)

object SpeakerStats {

    /** Anzeige-Name eines Segments (Profilname > Label > Unbekannt). */
    fun displayName(seg: SegmentEntity): String =
        seg.speakerName?.takeIf { it.isNotBlank() }
            ?: seg.speakerLabel?.takeIf { it.isNotBlank() }
            ?: "Unbekannt"

    fun compute(segments: List<SegmentEntity>): List<SpeakerStat> {
        val byName = LinkedHashMap<String, LongArray>() // label → [totalMs, count]
        for (seg in segments) {
            val dur = (seg.endTimeMs - seg.startTimeMs).coerceAtLeast(0L)
            if (dur == 0L) continue
            val name = displayName(seg)
            val acc = byName.getOrPut(name) { LongArray(2) }
            acc[0] += dur
            acc[1] += 1
        }
        val total = byName.values.sumOf { it[0] }
        if (total <= 0L) return emptyList()
        return byName.entries
            .map { (name, acc) ->
                SpeakerStat(
                    label = name,
                    totalMs = acc[0],
                    segmentCount = acc[1].toInt(),
                    percent = ((acc[0] * 100 + total / 2) / total).toInt().coerceIn(0, 100),
                )
            }
            .sortedByDescending { it.totalMs }
    }

    /** Kompakte Zeitdarstellung: "7 min" bzw. "42 s" bei kurzen Sessions. */
    fun formatDurationMs(ms: Long): String {
        val sec = ms / 1000
        return if (sec >= 60) "${sec / 60} min" else "$sec s"
    }
}