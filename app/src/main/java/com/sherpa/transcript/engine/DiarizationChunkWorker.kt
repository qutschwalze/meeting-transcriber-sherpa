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
    /** Hebel G: akustische Voice-Bank gegen Engine-Drift (optional, testbar). */
    private val voiceBank: SessionVoiceBank? = null,
    private val chunkSec: Float = 20f,
    private val overlapSec: Float = 5f,
) {

    companion object {
        private const val TAG = "DiarizationChunkWorker"

        /** Toleranz für Float-Grenzenvergleiche (Sekunden). */
        private const val EPS = 0.01f

        /** Sample-Rate des Audio-Streams (16 kHz) – für Segment-Audio-Extraktion. */
        private const val SAMPLE_RATE = 16000
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
     * Finaler Lauf (Stop): verarbeitet ALLES verbleibende Audio.
     *
     * 1. Erst alle vollen Chunks via [processNextChunk] (können bei langem Stop
     *    mehrere sein – pyannote darf nie > chunkSec+overlapSec auf einmal sehen).
     * 2. Dann den Rest (< chunkSec) als letzten Chunk.
     * 3. Ergebnis: mappedSegments = der KOMPLETTE konsolidierte globale Bestand
     *    (alle Speaker, alle Zeiten absolut) – damit der Save-Pfad alle ASR-
     *    Segmente gegen die volle Timeline labeln kann, nicht nur den letzten Chunk.
     *
     * @return null wenn seit dem letzten Chunk nichts Neues kam; sonst das Worker-Ergebnis.
     */
    fun processFinalChunk(debug: Boolean = false): WorkerChunkResult? {
        // 1) Alle vollen Chunks verarbeiten (können bei langem Stop mehrere sein)
        var lastResult = processNextChunk(debug)
        while (true) {
            val next = processNextChunk(debug) ?: break
            lastResult = next
        }

        // 2) Rest (< chunkSec) als letzten Chunk
        val restChunk = buffer.takeRemainingChunk(chunkSec, overlapSec)
        if (restChunk != null) {
            val restResult = processChunk(restChunk, debug)
            if (restResult != null) lastResult = restResult
        }

        // 3) Kompletten konsolidierten Bestand als mappedSegments liefern
        val finalResult = lastResult ?: return null
        val fullBestand = globalSegments.map { seg ->
            DiarizationSegment(startSec = seg.startSec, endSec = seg.endSec, speaker = seg.speakerId)
        }
        if (fullBestand.isEmpty()) return finalResult

        if (debug) {
            val speakers = fullBestand.map { it.speaker }.distinct().sorted().joinToString(",")
            Log.d(TAG, "processFinalChunk: fullBestand=${fullBestand.size} segments, speakers=[$speakers]")
        }
        return finalResult.copy(mappedSegments = fullBestand)
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

        // ── 4b (Hebel G): Voice-Bank-Fallback für "neue" IDs ──
        // Wenn der Reconciler eine ID als NEU deklariert hat (Anker-Lücke in der
        // Zone), fragt die Bank akustisch nach: Ist das vielleicht ein bekannter
        // Sprecher, dessen Stimme die Engine nur neu geclustert hat?
        // - Match über Threshold → Drift aufgelöst, ID wird zurückgemappt
        // - Kein Match → wirklich neuer Sprecher → wird eingeschrieben (enroll)
        var finalMapping = result.mapping
        var finalNewSpeakerIds = result.newSpeakerIds
        var driftResolvedCount = 0
        if (voiceBank != null && result.newSpeakerIds.isNotEmpty()) {
            for (localId in result.newSpeakerIds) {
                val segsOfSpeaker = absoluteSegments.filter { it.speaker == localId }
                val best = segsOfSpeaker.maxByOrNull { it.endSec - it.startSec } ?: continue
                val samples = extractSegmentSamples(chunk, best)
                if (samples.isEmpty()) continue
                val durationMs = ((best.endSec - best.startSec) * 1000f).toLong()

                val matchedGlobalId = voiceBank.identify(samples)
                if (matchedGlobalId != null) {
                    // Drift aufgelöst: lokale ID → bestehende globale ID
                    finalMapping = finalMapping + (localId to matchedGlobalId)
                    finalNewSpeakerIds = finalNewSpeakerIds - localId
                    driftResolvedCount++
                    Log.d(TAG, "VOICE_BANK resolve: local=$localId → global=$matchedGlobalId " +
                            "(dur=${durationMs}ms, statt neue ID ${result.mapping[localId]})")
                } else {
                    // Wirklich neuer Sprecher → in die Bank einschreiben
                    val newGlobalId = result.mapping[localId] ?: continue
                    voiceBank.enroll(newGlobalId, samples, durationMs)
                }
            }
            if (driftResolvedCount > 0) {
                Log.d(TAG, "VOICE_BANK: $driftResolvedCount Drift-ID(s) aufgelöst (Bank=${voiceBank.speakerCount} Sprecher)")
            }
        }
        // Mapping auf die (evtl. korrigierten) globalen IDs anwenden
        val correctedSegments = if (finalMapping == result.mapping) {
            result.mappedSegments
        } else {
            absoluteSegments.map { seg ->
                seg.copy(speaker = finalMapping[seg.speaker] ?: seg.speaker)
            }
        }

        // ── 5: State-Update (nur bei nicht-leerem Engine-Ergebnis) ──
        if (correctedSegments.isNotEmpty()) {
            globalSegments = mergeIntoGlobalBestand(globalSegments, correctedSegments, overlapZone)
        }

        if (debug) {
            Log.d(TAG, "processNextChunk: chunk=${chunk.startSec}-${chunk.endSec}s overlap=${chunk.overlapSec}s " +
                    "engineSegs=${engineSegments.size} mapped=${correctedSegments.size} " +
                    "globalBestand=${globalSegments.size}")
        }

        return WorkerChunkResult(
            chunk = chunk,
            mappedSegments = correctedSegments,
            mapping = finalMapping,
            newSpeakerIds = finalNewSpeakerIds,
            allGlobalSegments = globalSegments,
        )
    }

    /**
     * Extrahiert das Audio eines Diarization-Segments aus dem Chunk-Audio.
     * Segment-Zeiten sind absolut (Session), Chunk-Samples relativ zum Chunk-Anfang.
     */
    private fun extractSegmentSamples(chunk: AudioChunk, seg: DiarizationSegment): FloatArray {
        val relStart = (seg.startSec - chunk.startSec).coerceIn(0f, chunk.endSec - chunk.startSec)
        val relEnd = (seg.endSec - chunk.startSec).coerceIn(0f, chunk.endSec - chunk.startSec)
        val startIdx = (relStart * SAMPLE_RATE).toInt().coerceIn(0, chunk.samples.size)
        val endIdx = (relEnd * SAMPLE_RATE).toInt().coerceIn(startIdx, chunk.samples.size)
        if (endIdx <= startIdx) return FloatArray(0)
        return chunk.samples.copyOfRange(startIdx, endIdx)
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
