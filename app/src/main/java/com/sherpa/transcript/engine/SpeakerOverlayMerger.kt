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
     * 0.11.0: Mini-Segment-Regel (Geräte-Befund: 6-s-Fragment als eigene
     * „Stimme" im Export, obwohl die Bank die Segmente zweimal bestehenden
     * Stimmen zuordnen wollte). Ein Session-GID mit insgesamt WENIGER als
     * [MIN_FRAGMENT_TOTAL_MS] Redezeit UND ohne Global-Profil-Zuordnung
     * (= nie bank-bestätigt) ist ein Fragment → seine Segmente wandern zum
     * zeitlich nächsten Nachbar-Segment-Sprecher (vor oder nach dem Segment,
     * kleinerer Abstand gewinnt). Konservativ: keine Bestätigten, keine
     * bank-gemappten GIDs, keine Mindestlücken-Anforderungen nötig.
     * Diagnosezeile: `VB_MINI_MERGE`.
     */
    const val MIN_FRAGMENT_TOTAL_MS = 8_000L

    /**
     * 0.11.1: Fragment-Cluster-Merge (Geräte-Befund 01.09.: Meeting mit 23
     * Export-Speakern, davon ~10–12 bank-lose Fragmente WENIGER Stimmen – dichter
     * Ähnlichkeitsblock 0,60–0,73 in der Host-Matrix). Während [mergeMiniFragments]
     * nur Mini-Fragmente (< [MIN_FRAGMENT_TOTAL_MS]) auffängt, mergt dieser Schritt
     * GRÖSSERE bank-lose GIDs, deren Session-Voiceprints (confirmed, ohne
     * Global-Profil) sich gegenseitig ≥ [FRAGMENT_CLUSTER_THRESHOLD] ähnlich sind.
     * Transitiv (Union-Find); primary = meiste Redezeit. Diagnosezeile:
     * `VB_CLUSTER_MERGE`. Nutzt die Vektoren der SessionVoiceBank – kein WAV-Zugriff.
     */
    const val FRAGMENT_CLUSTER_THRESHOLD = 0.60f

    /**
     * @param overlay Save-Overlay (Orig-GIDs als "speaker_N").
     * @param profileByGid Session-GID → Global-Profil (bank-gemappte GIDs sind
     *   ausgeschlossen – die wickelt der Duplikat-Merge ab).
     * @param voiceprintByGid Session-Bank-Voiceprints (confirmed) je GID.
     * @return Overlay mit zusammengeführten Fragment-Clustern.
     */
    fun mergeFragmentClusters(
        overlay: List<TranscriptSegment>,
        profileByGid: Map<Int, String>,
        voiceprintByGid: Map<Int, FloatArray>,
    ): List<TranscriptSegment> {
        if (overlay.size < 2 || voiceprintByGid.isEmpty()) return overlay

        val activeGids = overlay.mapNotNull { it.speakerId?.removePrefix("speaker_")?.toIntOrNull() }.distinct()
        val candidates = activeGids.filter { gid ->
            gid !in profileByGid && voiceprintByGid.containsKey(gid)
        }
        if (candidates.size < 2) return overlay

        // Union-Find über Voiceprint-Ähnlichkeit
        val parent = candidates.associateWith { it }.toMutableMap()
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
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val a = candidates[i]
                val b = candidates[j]
                val sim = SessionVoiceBank.cosineSimilarity(
                    voiceprintByGid.getValue(a),
                    voiceprintByGid.getValue(b),
                )
                if (sim >= FRAGMENT_CLUSTER_THRESHOLD) {
                    union(a, b)
                    pairs++
                }
            }
        }
        if (pairs == 0) return overlay

        val durationByGid = overlay
            .filter { it.speakerId != null }
            .groupBy { it.speakerId!!.removePrefix("speaker_").toIntOrNull() }
            .mapValues { (_, segs) -> segs.sumOf { it.endTimeMs - it.startTimeMs } }

        val gidToPrimary = mutableMapOf<Int, Int>()
        val groups = candidates.groupBy { find(it) }
        for ((_, members) in groups) {
            if (members.size < 2) continue
            val primary = members.maxByOrNull { durationByGid[it] ?: 0L } ?: continue
            members.forEach { gidToPrimary[it] = primary }
            android.util.Log.i("SpeakerOverlayMerger",
                "VB_CLUSTER_MERGE gids=${members.sorted()} → primary=$primary (bank-lose Session-Voiceprints, Sim >= ${FRAGMENT_CLUSTER_THRESHOLD})")
        }
        if (gidToPrimary.isEmpty()) return overlay

        return overlay.map { seg ->
            val gid = seg.speakerId?.removePrefix("speaker_")?.toIntOrNull() ?: return@map seg
            val primary = gidToPrimary[gid] ?: return@map seg
            if (primary == gid) seg else seg.copy(speakerId = "speaker_$primary")
        }
    }

    /**
     * @param overlay Save-Overlay (Orig-GIDs als "speaker_N").
     * @param profileByGid Session-GID → Global-Profil (Bank-Bestätigung).
     * @return Overlay mit umgelabelten Fragment-Segmenten.
     */
    fun mergeMiniFragments(
        overlay: List<TranscriptSegment>,
        profileByGid: Map<Int, String>,
    ): List<TranscriptSegment> {
        if (overlay.size < 2) return overlay

        data class Row(val seg: TranscriptSegment, val gid: Int?)

        val rows = overlay.map { it to (it.speakerId?.removePrefix("speaker_")?.toIntOrNull()) }
        val totalByGid = rows
            .filter { it.second != null }
            .groupBy { it.second!! }
            .mapValues { (_, rs) -> rs.sumOf { (it.first.endTimeMs - it.first.startTimeMs).coerceAtLeast(0L) } }

        val fragmentGids = totalByGid.filter { (gid, total) ->
            total < MIN_FRAGMENT_TOTAL_MS && gid !in profileByGid
        }.keys
        if (fragmentGids.isEmpty()) return overlay

        var moved = 0
        val result = overlay.mapIndexed { i, seg ->
            val gid = seg.speakerId?.removePrefix("speaker_")?.toIntOrNull() ?: return@mapIndexed seg
            if (gid !in fragmentGids) return@mapIndexed seg
            // Zeitlich nächster Nachbar mit gültigem, NICHT-Fragment-Sprecher
            var best: TranscriptSegment? = null
            var bestDist = Long.MAX_VALUE
            for (j in overlay.indices) {
                if (j == i) continue
                val other = overlay[j]
                val otherGid = other.speakerId?.removePrefix("speaker_")?.toIntOrNull()
                    ?: continue
                if (otherGid in fragmentGids) continue
                val dist = minOf(
                    kotlin.math.abs(seg.startTimeMs - other.endTimeMs),
                    kotlin.math.abs(other.startTimeMs - seg.endTimeMs),
                )
                if (dist < bestDist) {
                    bestDist = dist
                    best = other
                }
            }
            val target = best ?: return@mapIndexed seg
            moved++
            seg.copy(speakerId = target.speakerId, speakerLabel = target.speakerLabel)
        }
        if (moved > 0) {
            android.util.Log.i("SpeakerOverlayMerger",
                "VB_MINI_MERGE fragments=$fragmentGids → $moved Segmente zum nächsten Sprecher (total < ${MIN_FRAGMENT_TOTAL_MS}ms, bank-los)")
        }
        return result
    }

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