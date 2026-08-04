package com.sherpa.transcript.engine

import android.util.Log

/**
 * Zeitbereich in absoluten Session-Sekunden (wie DiarizationSegment).
 */
data class TimeRange(val startSec: Float, val endSec: Float) {
    val durationSec: Float get() = (endSec - startSec).coerceAtLeast(0f)

    /** Überlappung dieses Bereichs mit [other] in Sekunden (0 wenn keine). */
    fun overlapSec(other: TimeRange): Float {
        val s = maxOf(startSec, other.startSec)
        val e = minOf(endSec, other.endSec)
        return (e - s).coerceAtLeast(0f)
    }
}

/**
 * Ein zeitlich verorteter Sprecher-Abschnitt mit GLOBALER (Session-)Speaker-ID.
 * Der Bestand, gegen den der RollingReconciler neue Chunks abgleicht.
 * Bewusst ASR-unabhängig (kein Text, keine segmentId) – reine Diarization-Ebene.
 */
data class SpeakerTimeRange(
    val startSec: Float,
    val endSec: Float,
    val speakerId: Int,
)

/**
 * Ergebnis eines RollingReconciler-Laufs.
 *
 * @param mappedSegments   Die lokalen Segmente mit auf globale Session-IDs gemappten Speakern.
 * @param mapping          localSpeakerId → globale Session-Speaker-ID.
 * @param votes            Diagnose: pro lokaler Engine-ID die Overlap-Votes (globalId → Sekunden).
 * @param newSpeakerIds    Lokale IDs, die zu NEUEN globalen Speakern wurden (kein Match in Zone).
 * @param zoneOverlapSec   Tatsächlich in der Zone überlappte Gesamtdauer (Diagnose).
 */
data class ReconcilerResult(
    val mappedSegments: List<DiarizationSegment>,
    val mapping: Map<Int, Int>,
    val votes: Map<Int, Map<Int, Float>>,
    val newSpeakerIds: Set<Int>,
    val zoneOverlapSec: Float,
)

/**
 * RollingReconciler – löst das Permutation-Problem (Speaker-Drift) zwischen
 * aufeinanderfolgenden Diarization-Chunks.
 *
 * Kernidee (Rolling-Reconciliation-Architektur, experiment/neue-idee):
 * Chunk B wird mit Overlap-Kontext von Chunk A verarbeitet. In der Overlap-Zone
 * existieren zwei "Wahrheiten":
 *   - bestätigte globale Segmente aus Chunk A (previousGlobalSegments)
 *   - frische, engine-lokale Segmente aus Chunk B (localSegments)
 *
 * Der Reconciler berechnet die zeitliche Schnittmenge (Temporal Intersection)
 * pro (localSpeaker, globalSpeaker)-Paar INNERHALB der Overlap-Zone, aggregiert
 * per Engine-ID (Majority-Voting – verhindert künstliche Speaker-Inflation) und
 * matcht greedily: das Paar mit dem größten Overlap gewinnt zuerst.
 *
 * Confidence-Gate: erst ab MIN_MATCH_OVERLAP_SEC Overlap gilt ein Match.
 * Darunter wird der lokale Speaker konservativ als NEUER globaler Sprecher
 * deklariert, statt ihn falsch zuzuordnen.
 *
 * Zustandslos: alle Eingaben als Parameter, keine internen Mutable States.
 */
class RollingReconciler(
    /**
     * Mindest-Overlap (Sekunden) in der Zone für einen gültigen Match.
     * 0.3s (300ms): konservativer Standard – senkt die Schwelle für kurze
     * Sprecher-Aktivität im Final Block, ohne Fehlzuordnungen zu riskieren.
     * (Abgestimmt auf TimelineComposer.MIN_CONFIDENCE_OVERLAP_MS = 300ms.)
     */
    private val minMatchOverlapSec: Float = 0.3f,
    /**
     * Winzige Fragmente unter dieser Dauer (Sekunden) werden VOR dem Matching
     * aus beiden Inputs entfernt (Hebel E – Tuning).
     *
     * Begründung: assignSpeakersToRawSegments verlangt >= 300ms Overlap ODER
     * >= 35% der ASR-Segmentdauer. Ein Fragment < 400ms kann ein ASR-Segment
     * von > 1.1s nie mit 35% abdecken – es erzeugt nur Votes-Rauschen im
     * Greedy-Matching und künstliche neue IDs, ohne je ein Label zu liefern.
     */
    private val minFragmentSec: Float = 0.4f,
) {

    companion object {
        private const val TAG = "RollingReconciler"

        /**
         * Mindest-Redezeit (Summe der Fragmente einer Engine-ID) für die
         * Fragment-Aggregation (0.5.64). Konsistent mit der Mindest-Redezeit
         * der Voice-Bank (minEnrollmentSec = 2s): Was die Bank einschreiben
         * könnte, darf der Reconciler nicht wegwerfen.
         */
        private const val MIN_AGGREGATE_SEC = 2f
    }

    /**
     * Aggregiert winzige Fragmente (< [minFragmentSec]) pro Engine-ID zu einem
     * Segment, wenn ihre Redezeit zusammen >= [MIN_AGGREGATE_SEC] beträgt.
     * Nur für den Fall, dass ALLE lokalen Segmente eines Chunks Fragmente sind
     * (sonst greift der normale Filter). Das aggregierte Segment deckt die
     * äußeren Grenzen ab ([min start, max end]) und trägt die Engine-ID.
     */
    private fun aggregateFragments(localSegments: List<DiarizationSegment>): List<DiarizationSegment> {
        if (localSegments.isEmpty()) return emptyList()
        val bySpeaker = localSegments.groupBy { it.speaker }
        val result = mutableListOf<DiarizationSegment>()
        for ((speaker, segs) in bySpeaker) {
            val totalDur = segs.sumOf { (it.endSec - it.startSec).toDouble() }
            if (totalDur < MIN_AGGREGATE_SEC) continue
            val start = segs.minOf { it.startSec }
            val end = segs.maxOf { it.endSec }
            result.add(DiarizationSegment(startSec = start, endSec = end, speaker = speaker))
        }
        return result
    }

    /**
     * Gleicht lokale Speaker eines neuen Chunks gegen bestätigte globale Speaker ab.
     *
     * @param localSegments          Diarization-Segmente aus Chunk B (Zeiten ABSOLUT, offset-korrigiert).
     * @param overlapZone            Overlap-Zone [startSec, endSec] – nur hier wird gematcht.
     * @param previousGlobalSegments Bestätigte globale Segmente aus dem Bestand (Chunk A).
     * @param debug                  Aktiviert ASSIGN_DBG-artige Logs.
     */
    fun reconcile(
        localSegments: List<DiarizationSegment>,
        overlapZone: TimeRange,
        previousGlobalSegments: List<SpeakerTimeRange>,
        debug: Boolean = false,
    ): ReconcilerResult {
        if (localSegments.isEmpty()) {
            return ReconcilerResult(emptyList(), emptyMap(), emptyMap(), emptySet(), 0f)
        }

        // ── 0. Fragment-Filter (Hebel E): winzige Fragmente aus BEIDEN Inputs ──
        // Sie können ASR-Segmente nie mit >= 35% abdecken → nur Votes-Rauschen
        // und künstliche neue IDs. Gefilterte lokale Segmente erscheinen auch
        // nicht in mappedSegments → der Worker-Bestand bleibt frei von Rauschen.
        val significantLocal = localSegments.filter { (it.endSec - it.startSec) >= minFragmentSec }
        val significantGlobal = previousGlobalSegments.filter { (it.endSec - it.startSec) >= minFragmentSec }
        if (significantLocal.isEmpty()) {
            // ── 0b. Fragment-Aggregation (0.5.64): Wenn ALLE lokalen Segmente unter
            // minFragmentSec liegen (zerstückelte VAD auf Mikrofon-Aufnahmen), aber
            // die Fragmente EINER Engine-ID zusammen substanzielle Redezeit haben,
            // werden sie zu EINEM Segment aggregiert (min start, max end, die ID).
            //
            // Log-Befund Geräte-Test 0.5.63: Chunk [55,75] lieferte nach Retry-
            // SUCCESS 7 Fragmente (< 0.4s) im Sprecher-B-Block (62-75s) → mapped=0
            // → B kam nie in den globalen Bestand und nie in die Voice-Bank →
            // Endzustand 3 Speaker statt 2, B-Block unlabeled. Ohne Aggregation
            // ist ein komplett fragmentierter Chunk für Reconciler + Bank unsichtbar.
            val aggregated = aggregateFragments(localSegments)
            if (aggregated.isEmpty()) {
                return ReconcilerResult(emptyList(), emptyMap(), emptyMap(), emptySet(), 0f)
            }
            if (debug) {
                Log.d(TAG, "reconcile: alle ${localSegments.size} lokalen Segmente <${minFragmentSec}s " +
                        "→ ${aggregated.size} aggregiertes Segment(e) (Redezeit >= ${MIN_AGGREGATE_SEC}s)")
            }
            return reconcile(aggregated, overlapZone, previousGlobalSegments, debug)
        }
        if (debug && significantLocal.size != localSegments.size) {
            Log.d(TAG, "reconcile: fragment filter removed ${localSegments.size - significantLocal.size} local + " +
                    "${previousGlobalSegments.size - significantGlobal.size} global tiny segments (<${minFragmentSec}s)")
        }

        // ── 1. Globale Speaker + ihre Zeitbereiche aus dem Bestand extrahieren ──
        val globalRanges = mutableMapOf<Int, MutableList<TimeRange>>()
        var maxGlobalId = -1
        for (seg in significantGlobal) {
            if (seg.endSec <= seg.startSec) continue
            globalRanges.getOrPut(seg.speakerId) { mutableListOf() }.add(
                TimeRange(seg.startSec, seg.endSec)
            )
            if (seg.speakerId > maxGlobalId) maxGlobalId = seg.speakerId
        }
        val knownGlobalIds = globalRanges.keys.toSet()

        // ── 2. Overlap-Votes pro lokaler Engine-ID sammeln (nur innerhalb der Zone) ──
        // localId → (globalId → Overlap-Sekunden)
        val votes = mutableMapOf<Int, MutableMap<Int, Float>>()
        var zoneOverlapTotal = 0f

        for (localSeg in significantLocal) {
            val localId = localSeg.speaker
            val localRange = TimeRange(localSeg.startSec, localSeg.endSec)
            val clippedZone = TimeRange(
                maxOf(localRange.startSec, overlapZone.startSec),
                minOf(localRange.endSec, overlapZone.endSec),
            )
            val zoneOverlap = overlapZone.overlapSec(localRange)
            if (zoneOverlap <= 0f) continue // Segment liegt komplett außerhalb der Zone
            zoneOverlapTotal += zoneOverlap

            val localVotes = votes.getOrPut(localId) { mutableMapOf() }
            for ((globalId, ranges) in globalRanges) {
                var acc = 0f
                for (gr in ranges) {
                    // Nur Überlappung INNERHALB der Zone zählen
                    acc += clippedZone.overlapSec(gr)
                }
                if (acc > 0f) {
                    localVotes[globalId] = (localVotes[globalId] ?: 0f) + acc
                }
            }
        }

        // ── 3. Greedy-Matching: größter Overlap zuerst, Confidence-Gate anwenden ──
        val mapping = mutableMapOf<Int, Int>()
        val assignedLocals = mutableSetOf<Int>()
        val assignedGlobals = mutableSetOf<Int>()

        val candidatePairs = votes.flatMap { (localId, globalVotes) ->
            globalVotes.map { (globalId, overlap) -> Triple(localId, globalId, overlap) }
        }.sortedByDescending { it.third }

        for ((localId, globalId, overlap) in candidatePairs) {
            if (overlap < minMatchOverlapSec) break // Rest ist kleiner → kein Match mehr
            if (localId in assignedLocals || globalId in assignedGlobals) continue
            mapping[localId] = globalId
            assignedLocals.add(localId)
            assignedGlobals.add(globalId)
        }

        // ── 4. Unmatched lokale IDs → neue globale Speaker ──
        val newSpeakerIds = mutableSetOf<Int>()
        val nextFreeId = maxGlobalId + 1
        var nextNewId = nextFreeId
        val allLocalIds = significantLocal.map { it.speaker }.distinct().sorted()
        for (localId in allLocalIds) {
            if (localId !in assignedLocals) {
                mapping[localId] = nextNewId
                newSpeakerIds.add(localId)
                nextNewId++
            }
        }

        // ── 5. Mapping auf alle signifikanten lokalen Segmente anwenden ──
        val mappedSegments = significantLocal.map { seg ->
            seg.copy(speaker = mapping[seg.speaker] ?: seg.speaker)
        }

        if (debug) {
            val votesStr = votes.entries.joinToString(" | ") { (localId, gv) ->
                val sorted = gv.entries.sortedByDescending { it.value }
                    .joinToString(",") { (gid, ov) ->
                        String.format("%d:%.2fs", gid, ov)
                    }
                "$localId→[$sorted]"
            }
            val mappingStr = mapping.entries.sortedBy { it.key }
                .joinToString(",") { (l, g) -> "$l→$g" }
            val newStr = newSpeakerIds.sorted().joinToString(",")
            Log.d(TAG, "reconcile: zone=${overlapZone.startSec}-${overlapZone.endSec}s " +
                    "localIds=[${allLocalIds.joinToString(",")}] globalIds=[${knownGlobalIds.sorted().joinToString(",")}] " +
                    "votes={$votesStr} mapping=[$mappingStr] new=[$newStr] " +
                    "zoneOverlap=${String.format("%.1f", zoneOverlapTotal)}s")
        }

        return ReconcilerResult(
            mappedSegments = mappedSegments,
            mapping = mapping,
            votes = votes,
            newSpeakerIds = newSpeakerIds,
            zoneOverlapSec = zoneOverlapTotal,
        )
    }
}
