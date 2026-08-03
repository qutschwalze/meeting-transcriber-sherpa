package com.sherpa.transcript.engine

import android.util.Log
import com.sherpa.transcript.domain.audio.AudioChunk
import com.sherpa.transcript.domain.audio.ChunkedAudioBuffer

/**
 * Abstraktion der Diarization-Engine für den ChunkWorker – testbar per Fake.
 * Die echte [SpeakerDiarizationEngine] erfüllt diese Signatur strukturell.
 */
fun interface ChunkDiarizer {
    fun process(samples: FloatArray): List<DiarizationSegment>
}

/**
 * Ergebnis eines Worker-Laufs.
 *
 * @param chunk               Der verarbeitete Chunk (Metadaten: startSec, endSec, overlapSec).
 * @param mappedSegments      Diarization-Segmente mit GLOBALEN Speaker-IDs und ABSOLUTEN Zeiten.
 * @param mapping             localSpeakerId → globale Session-ID (Diagnose).
 * @param newSpeakerIds       Lokale IDs, die zu neuen globalen Speakern wurden (Diagnose).
 * @param allGlobalSegments   Kompletter Bestand NACH diesem Lauf (für Persistenz/Diagnose).
 */
data class WorkerChunkResult(
    val chunk: AudioChunk,
    val mappedSegments: List<DiarizationSegment>,
    val mapping: Map<Int, Int>,
    val newSpeakerIds: Set<Int>,
    val allGlobalSegments: List<SpeakerTimeRange>,
)

/**
 * DiarizationChunkWorker – der Dirigent für Gleis 2 (Rolling-Reconciliation-Architektur).
 *
 * Orchestriert pro Lauf:
 *  1. Pull:     Chunk (chunkSec + overlapSec Kontext) aus dem [ChunkedAudioBuffer]
 *  2. Process:  Audio an die Diarization-Engine (liefert lokale Zeiten, relativ zum Chunk)
 *  3. Time-Shift: lokale Zeiten auf ABSOLUTE Session-Zeit hochrechnen (+ chunk.startSec)
 *  4. Reconcile: gegen den globalen Bestand in der Overlap-Zone matchen (Speaker-Drift lösen)
 *  5. State-Update: globalen Bestand fortschreiben (Zone ersetzen, Älteres behalten)
 *  6. Emit:     fertig benannte Segmente + Diagnose ans ViewModel
 *
 * Der Worker HÄLT den globalen Diarization-State (globalSegments), damit der
 * [RollingReconciler] zustandslos und der Buffer rein mechanisch bleiben.
 */
class DiarizationChunkWorker(
    private val buffer: ChunkedAudioBuffer,
    private val diarizer: ChunkDiarizer,
    private val reconciler: RollingReconciler = RollingReconciler(),
    private val chunkSec: Float = 20f,
    private val overlapSec: Float = 5f,
) {

    companion object {
        private const val TAG = "DiarizationChunkWorker"

        /** Toleranz für Float-Grenzenvergleiche (Sekunden). */
        private const val EPS = 0.01f
    }

    /** Globaler Bestand: bestätigte Diarization-Segmente mit Session-weiten Speaker-IDs. */
    var globalSegments: List<SpeakerTimeRange> = emptyList()
        private set

    /** Reset für eine neue Aufnahme-Session. */
    fun reset() {
        globalSegments = emptyList()
    }

    /**
     * Verarbeitet den nächsten Chunk, falls genügend neues Audio vorliegt.
     *
     * @return null wenn noch kein neuer Chunk verfügbar ist; sonst das Worker-Ergebnis.
     */
    fun processNextChunk(debug: Boolean = false): WorkerChunkResult? {
        val chunk = buffer.takeChunk(chunkSec, overlapSec) ?: return null
        return processChunk(chunk, debug)
    }

    /**
     * Finaler Lauf (Stop): verarbeitet den REST des Buffers – auch wenn weniger
     * als chunkSec neues Audio seit dem letzten Chunk vorliegt. Damit gehen
     * die letzten Sekunden einer Aufnahme nicht verloren.
     *
     * @return null wenn seit dem letzten Chunk nichts Neues kam; sonst das Worker-Ergebnis.
     */
    fun processFinalChunk(debug: Boolean = false): WorkerChunkResult? {
        val chunk = buffer.takeRemainingChunk(chunkSec, overlapSec) ?: return null
        return processChunk(chunk, debug)
    }

    /** Gemeinsamer Kern für [processNextChunk] und [processFinalChunk]. */
    private fun processChunk(chunk: AudioChunk, debug: Boolean): WorkerChunkResult? {
        // ── 1+2: Engine – lokale Zeiten relativ zum Chunk-Anfang ──
        val engineSegments = diarizer.process(chunk.samples)

        // ── 3: Time-Shift auf absolute Session-Zeit ──
        val absoluteSegments = engineSegments.map { seg ->
            DiarizationSegment(
                startSec = seg.startSec + chunk.startSec,
                endSec = seg.endSec + chunk.startSec,
                speaker = seg.speaker,
            )
        }

        // ── 4: Reconcile gegen den globalen Bestand in der Overlap-Zone ──
        // Zone = [chunkStart, chunkStart+overlap] = die letzten overlapSec von Chunk A
        val overlapZone = TimeRange(chunk.startSec, chunk.startSec + chunk.overlapSec)
        val result = reconciler.reconcile(
            localSegments = absoluteSegments,
            overlapZone = overlapZone,
            previousGlobalSegments = globalSegments,
            debug = debug,
        )

        // ── 5: State-Update (nur bei nicht-leerem Engine-Ergebnis) ──
        if (result.mappedSegments.isNotEmpty()) {
            globalSegments = mergeIntoGlobalBestand(globalSegments, result.mappedSegments, overlapZone)
        }

        if (debug) {
            Log.d(TAG, "processNextChunk: chunk=${chunk.startSec}-${chunk.endSec}s overlap=${chunk.overlapSec}s " +
                    "engineSegs=${engineSegments.size} mapped=${result.mappedSegments.size} " +
                    "globalBestand=${globalSegments.size}")
        }

        return WorkerChunkResult(
            chunk = chunk,
            mappedSegments = result.mappedSegments,
            mapping = result.mapping,
            newSpeakerIds = result.newSpeakerIds,
            allGlobalSegments = globalSegments,
        )
    }

    /**
     * Schreibt den globalen Bestand fort:
     * - Segmente, die komplett VOR der Zone enden, bleiben unverändert
     * - Segmente, die in die Zone ragen, werden an der Zonengrenze ZUGESCHNITTEN
     *   (der Teil vor der Zone bleibt, der Teil in der Zone wird durch die neuen
     *   Segmente des Chunks ersetzt)
     * - Segmente, die komplett in/nach der Zone liegen, entfallen (werden ersetzt)
     *
     * Das hält die Timeline lückenlos und verliert keine älteren Segmente.
     */
    private fun mergeIntoGlobalBestand(
        bestand: List<SpeakerTimeRange>,
        newMapped: List<DiarizationSegment>,
        overlapZone: TimeRange,
    ): List<SpeakerTimeRange> {
        val zoneStart = overlapZone.startSec - EPS
        val zoneEnd = overlapZone.endSec + EPS

        // Bestand behalten, ggf. an der Zonengrenze zuschneiden
        // EPS nur in den Vergleichen; der Zuschnitt nutzt die EXAKTE Zonengrenze.
        val exactZoneStart = overlapZone.startSec
        val kept = bestand.mapNotNull { seg ->
            when {
                seg.endSec <= zoneStart -> seg // komplett vor der Zone → unverändert
                seg.startSec >= zoneEnd -> null // komplett in/nach der Zone → wird ersetzt
                seg.startSec < exactZoneStart ->
                    seg.copy(endSec = exactZoneStart) // ragt in die Zone → Teil davor behalten
                else -> null // beginnt in der Zone → wird ersetzt
            }
        }

        val added = newMapped.map { seg ->
            SpeakerTimeRange(startSec = seg.startSec, endSec = seg.endSec, speakerId = seg.speaker)
        }

        return (kept + added).sortedBy { it.startSec }
    }
}
