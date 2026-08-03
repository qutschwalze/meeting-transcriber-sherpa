package com.sherpa.transcript.engine

import android.util.Log
import com.sherpa.transcript.domain.model.TranscriptSegment

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
    /** Mindest-Overlap (Sekunden) in der Zone für einen gültigen Match. */
    private val minMatchOverlapSec: Float = 0.5f,
) {

    companion object {
        private const val TAG = "RollingReconciler"
    }

    /**
     * Gleicht lokale Speaker eines neuen Chunks gegen bestätigte globale Speaker ab.
     *
     * @param localSegments          Diarization-Segmente aus Chunk B (Zeiten ABSOLUT, offset-korrigiert).
     * @param overlapZone            Overlap-Zone [startSec, endSec] – nur hier wird gematcht.
     * @param previousGlobalSegments Bestätigte Segmente aus dem Bestand (Chunk A) mit speakerId "speaker_N".
     * @param debug                  Aktiviert ASSIGN_DBG-artige Logs.
     */
    fun reconcile(
        localSegments: List<DiarizationSegment>,
        overlapZone: TimeRange,
        previousGlobalSegments: List<TranscriptSegment>,
        debug: Boolean = false,
    ): ReconcilerResult {
        if (localSegments.isEmpty()) {
            return ReconcilerResult(emptyList(), emptyMap(), emptyMap(), emptySet(), 0f)
        }

        // ── 1. Globale Speaker + ihre Zeitbereiche aus dem Bestand extrahieren ──
        val globalRanges = mutableMapOf<Int, MutableList<TimeRange>>()
        var maxGlobalId = -1
        for (seg in previousGlobalSegments) {
            val globalId = seg.speakerId
                ?.removePrefix("speaker_")
                ?.toIntOrNull()
                ?: continue
            if (seg.endTimeMs <= seg.startTimeMs) continue
            globalRanges.getOrPut(globalId) { mutableListOf() }.add(
                TimeRange(seg.startTimeMs / 1000f, seg.endTimeMs / 1000f)
            )
            if (globalId > maxGlobalId) maxGlobalId = globalId
        }
        val knownGlobalIds = globalRanges.keys.toSet()

        // ── 2. Overlap-Votes pro lokaler Engine-ID sammeln (nur innerhalb der Zone) ──
        // localId → (globalId → Overlap-Sekunden)
        val votes = mutableMapOf<Int, MutableMap<Int, Float>>()
        var zoneOverlapTotal = 0f

        for (localSeg in localSegments) {
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
        val allLocalIds = localSegments.map { it.speaker }.distinct().sorted()
        for (localId in allLocalIds) {
            if (localId !in assignedLocals) {
                mapping[localId] = nextNewId
                newSpeakerIds.add(localId)
                nextNewId++
            }
        }

        // ── 5. Mapping auf alle lokalen Segmente anwenden ──
        val mappedSegments = localSegments.map { seg ->
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
