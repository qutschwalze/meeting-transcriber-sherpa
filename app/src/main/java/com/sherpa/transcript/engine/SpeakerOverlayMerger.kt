package com.sherpa.transcript.engine

import com.sherpa.transcript.domain.model.TranscriptSegment

/**
 * 0.10.7: Führt Session-GIDs zusammen, deren Global-Profile Duplikate derselben
 * Stimme sind (Save-Zeit, nach [GlobalVoiceBank]-basierten Korrekturen).
 *
 * Hintergrund (Geräte-Befund 27.08.): Eine gedriftete Stimme matcht in der
 * globalen Bank mehrere Duplikat-Profile (dieselbe Person wurde über Sessions
 * als "neue" Profile eingelernt, sobald die Sim unter 0,62 driftete). Der
 * Worker reused Session-GIDs NUR INNERHALB eines Profils (DiarizationChunkWorker
 * `globalProfileBySessionId`) – zwei Duplikat-Profile derselben Stimme erzeugen
 * daher zwei überlebende Speaker im Export.
 *
 * Konservativ:
 * - nur GIDs MIT Profil-Zuordnung werden betrachtet (Profil-lose Pending-GIDs
 *   bleiben unangetastet – sie laufen über die Nearest-Confirmed-Auflösung)
 * - nur Paare mit Profil-Sim >= [DUP_MERGE_THRESHOLD] werden gemerged
 * - primärer GID = meiste Redezeit im Overlay (stabiler Label-Anker)
 * - transitiv (Union-Find): A~B und B~C ⇒ A, B, C eine Gruppe
 *
 * Diagnose: TestLog-Zeile `VB_DUP_MERGE gids=[...] → primary=N`.
 */
object SpeakerOverlayMerger {

    /** Einzige Stellschraube: Profil-Duplikat-Schwelle (konservativ über der
     *  0.35-Pending-Schwelle, unter der 0.62-Identify-Schwelle). */
    const val DUP_MERGE_THRESHOLD = 0.60f

    /**
     * @param overlay Save-Overlay (Segmente tragen Orig-GIDs als "speaker_N").
     * @param profileByGid Session-GID → Global-Profil-ID (Worker-Map).
     * @param profileSimilarity Cosine-Sim zweier Profile (injektiv, z. B. via
     *   [GlobalVoiceBank.profileSimilarity]).
     * @return Overlay mit zusammengeführten GIDs (nur speakerId geändert).
     */
    fun mergeDuplicateProfileGids(
        overlay: List<TranscriptSegment>,
        profileByGid: Map<Int, String>,
        profileSimilarity: (String, String) -> Float?,
    ): List<TranscriptSegment> {
        if (profileByGid.isEmpty() || overlay.size < 2) return overlay

        val activeGids = overlay.mapNotNull { it.speakerId?.removePrefix("speaker_")?.toIntOrNull() }.distinct()
        val gidsWithProfile = activeGids.filter { profileByGid.containsKey(it) }
        if (gidsWithProfile.size < 2) return overlay

        // Union-Find über Profil-Ähnlichkeit
        val parent = gidsWithProfile.associateWith { it }.toMutableMap()
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]!!
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        var pairs = 0
        for (i in gidsWithProfile.indices) {
            for (j in i + 1 until gidsWithProfile.size) {
                val a = gidsWithProfile[i]
                val b = gidsWithProfile[j]
                val sim = profileSimilarity(profileByGid.getValue(a), profileByGid.getValue(b)) ?: continue
                if (sim >= DUP_MERGE_THRESHOLD) {
                    union(a, b)
                    pairs++
                }
            }
        }
        if (pairs == 0) return overlay

        // Gruppen → primärer GID (meiste Redezeit im Overlay)
        val durationByGid = overlay
            .filter { it.speakerId != null }
            .groupBy { it.speakerId!!.removePrefix("speaker_").toIntOrNull() }
            .mapValues { (_, segs) -> segs.sumOf { it.endTimeMs - it.startTimeMs } }

        val gidToPrimary = mutableMapOf<Int, Int>()
        val groups = gidsWithProfile.groupBy { find(it) }
        for ((_, members) in groups) {
            if (members.size < 2) continue
            val primary = members.maxByOrNull { durationByGid[it] ?: 0L } ?: continue
            members.forEach { gidToPrimary[it] = primary }
            android.util.Log.i("SpeakerOverlayMerger",
                "VB_DUP_MERGE gids=${members.sorted()} → primary=$primary (Profil-Duplikate, Sim >= ${DUP_MERGE_THRESHOLD})")
        }
        if (gidToPrimary.isEmpty()) return overlay

        return overlay.map { seg ->
            val gid = seg.speakerId?.removePrefix("speaker_")?.toIntOrNull() ?: return@map seg
            val primary = gidToPrimary[gid] ?: return@map seg
            if (primary == gid) seg else seg.copy(speakerId = "speaker_$primary")
        }
    }
}