package com.sherpa.transcript.engine

import android.util.Log
import com.sherpa.transcript.domain.model.TranscriptSegment

/**
 * TimelineComposer – zwei getrennte Aufgaben:
 *
 * 1. assignSpeakersToRawSegments()
 *    Weist pro ASR-Segment den dominanten Sprecher zu.
 *    KEIN Merge – Segmentanzahl bleibt gleich.
 *
 * 2. mergeSegmentsForDisplay()
 *    Führt benachbarte Segmente mit gleichem Sprecher
 *    und kurzer Pause zusammen – NUR für die UI.
 */
object TimelineComposer {

    private const val TAG = "TimelineComposer"

    private const val MIN_OVERLAP_MS = 80L
    private const val MIN_CONFIDENCE_OVERLAP_MS = 300L
    private const val MIN_CONFIDENCE_OVERLAP_RATIO = 0.35f
    private const val MERGE_PAUSE_MS = 1200L
    private const val MIN_FRAGMENT_DURATION_MS = 600L
    private const val MAX_FRAGMENT_CHARS = 2

    /**
     * Weist jedem ASR-Segment den Sprecher mit der größten Überlappung zu.
     * Segmentanzahl bleibt unverändert.
     *
     * Confidence-Gate: Ein Segment bekommt nur ein Label, wenn
     * der Overlap >= 300ms ODER > 35% der Segmentdauer beträgt.
     */
    fun assignSpeakersToRawSegments(
        asrSegments: List<TranscriptSegment>,
        diarizationSegments: List<DiarizationSegment>,
        debug: Boolean = false,
    ): List<TranscriptSegment> {
        if (asrSegments.isEmpty() || diarizationSegments.isEmpty()) return asrSegments

        val diarCoverageSec = diarizationSegments.sumOf { (it.endSec - it.startSec).toDouble() }
        val diarSpeakers = diarizationSegments.map { it.speaker }.distinct().size

        val result = asrSegments.map { asr ->
            val asrStartSec = asr.startTimeMs / 1000f
            val asrEndSec = maxOf(
                asr.endTimeMs.coerceAtLeast(asr.startTimeMs + 300L) / 1000f,
                asrStartSec + 0.3f,
            )

            val overlapsBySpeaker = mutableMapOf<Int, Float>()
            diarizationSegments.forEach { ds ->
                val overlapStart = maxOf(asrStartSec, ds.startSec)
                val overlapEnd = minOf(asrEndSec, ds.endSec)
                val overlap = overlapEnd - overlapStart
                if (overlap > 0f) {
                    overlapsBySpeaker[ds.speaker] = (overlapsBySpeaker[ds.speaker] ?: 0f) + overlap
                }
            }

            val best = overlapsBySpeaker.maxByOrNull { it.value }
            val bestSpeaker = best?.key
            val bestOverlapMs = ((best?.value ?: 0f) * 1000f).toLong()
            val asrDurationMs = asrEndSec * 1000f - asrStartSec * 1000f
            val overlapRatio = if (asrDurationMs > 0f) bestOverlapMs / asrDurationMs else 0f

            // Confidence-Gate: nur labeln wenn ausreichend Overlap (>=300ms ODER >=35%)
            val hasConfidence = bestSpeaker != null
                    && bestOverlapMs >= MIN_OVERLAP_MS
                    && (bestOverlapMs >= MIN_CONFIDENCE_OVERLAP_MS || overlapRatio >= MIN_CONFIDENCE_OVERLAP_RATIO)

            // Diagnose: Overlap-Details pro Segment (nur im DBG-Modus)
            if (debug) {
                val overlapsStr = overlapsBySpeaker.entries
                    .sortedByDescending { it.value }
                    .joinToString(",") { (spk, sec) -> "$spk:${"%.2f".format(sec)}s" }
                val reason = when {
                    bestSpeaker == null -> "noOverlap"
                    bestOverlapMs < MIN_OVERLAP_MS -> "belowMinOverlap(${bestOverlapMs}ms<${MIN_OVERLAP_MS}ms)"
                    !hasConfidence -> "lowConfidence(${bestOverlapMs}ms<${MIN_CONFIDENCE_OVERLAP_MS}ms,${"%.0f".format(overlapRatio * 100)}%<${(MIN_CONFIDENCE_OVERLAP_RATIO * 100).toInt()}%)"
                    else -> "label"
                }
                Log.d(TAG, "ASSIGN_DBG id=${asr.segmentId.take(8)} t=${asr.startTimeMs}-${asr.endTimeMs} dur=${"%.1f".format(asrDurationMs / 1000f)}s overlaps=[$overlapsStr] best=$bestSpeaker bestMs=$bestOverlapMs ratio=${"%.0f".format(overlapRatio * 100)}% → $reason")
            }

            if (!hasConfidence) {
                asr.copy(speakerId = null, speakerLabel = null)
            } else {
                asr.copy(
                    speakerId = "speaker_$bestSpeaker",
                    speakerLabel = "Sprecher ${bestSpeaker + 1}",
                )
            }
        }

        // Diagnostik
        val labeled = result.count { !it.speakerId.isNullOrBlank() }
        val unlabeled = result.size - labeled
        val longSegments = asrSegments.count {
            (it.endTimeMs - it.startTimeMs) > 8000L
        }
        val noOverlapLabeled = result.indices.count { i ->
            result[i].speakerId == null && diarizationSegments.any { ds ->
                val asrStart = asrSegments[i].startTimeMs / 1000f
                val asrEnd = maxOf(
                    asrSegments[i].endTimeMs.coerceAtLeast(asrSegments[i].startTimeMs + 300L) / 1000f,
                    asrStart + 0.3f,
                )
                ds.startSec < asrEnd && ds.endSec > asrStart
            }
        }
        // noOverlapLabeled = Segmente, die zwar Overlap haben, aber unter MIN_OVERLAP_MS liegen

        Log.d(TAG, "assignSpeakers: ${result.size} ASR segs, $labeled labeled, $unlabeled unlabeled, " +
                "$longSegments >8s, ${diarizationSegments.size} diar segs, $diarSpeakers diar speakers, " +
                "diarCoverage=${"%.1f".format(diarCoverageSec)}s, " +
                "lowOverlap=$noOverlapLabeled")

        // Diagnose: Gründe-Verteilung (nur im DBG-Modus)
        if (debug) {
            var byNoOverlap = 0
            var byBelowMin = 0
            var byLowConfidence = 0
            var byLabel = 0
            for (i in result.indices) {
                val asr = asrSegments[i]
                val asrStartSec = asr.startTimeMs / 1000f
                val asrEndSec = maxOf(
                    asr.endTimeMs.coerceAtLeast(asr.startTimeMs + 300L) / 1000f,
                    asrStartSec + 0.3f,
                )
                val overlaps = diarizationSegments.fold(mutableMapOf<Int, Float>()) { acc, ds ->
                    val o = minOf(asrEndSec, ds.endSec) - maxOf(asrStartSec, ds.startSec)
                    if (o > 0f) acc[ds.speaker] = (acc[ds.speaker] ?: 0f) + o
                    acc
                }
                val bestMs = ((overlaps.maxByOrNull { it.value }?.value ?: 0f) * 1000f).toLong()
                when {
                    result[i].speakerId != null -> byLabel++
                    overlaps.isEmpty() -> byNoOverlap++
                    bestMs < MIN_OVERLAP_MS -> byBelowMin++
                    else -> byLowConfidence++
                }
            }
            Log.d(TAG, "ASSIGN_DBG summary: label=$byLabel noOverlap=$byNoOverlap belowMinOverlap=$byBelowMin lowConfidence=$byLowConfidence")
        }

        return result
    }

    /**
     * Fasst sehr kurze benachbarte ASR-Rohsegmente vor der Sprecherzuweisung zusammen.
     *
     * Folgende Segmente werden mit dem linken Nachbarn gemerged:
     * - Dauer < 600ms ODER nur 1–2 Zeichen (z. B. ".", "und", "der")
     * - Pause zum linken Nachbarn < 1200ms
     *
     * Dadurch entstehen robustere Segmente für die Diarization-Überlappung.
     * rawFinalSegments bleiben unverändert – arbeitet auf einer temporären Kopie.
     */
    fun compactRawSegmentsBeforeAssignment(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.size < 2) return segments

        val result = mutableListOf<TranscriptSegment>()
        var mergedCount = 0

        for (seg in segments) {
            val isTiny = (seg.endTimeMs - seg.startTimeMs) < MIN_FRAGMENT_DURATION_MS
                    || seg.text.trim().length <= MAX_FRAGMENT_CHARS

            if (isTiny && result.isNotEmpty()) {
                val last = result.last()
                val pauseToLast = seg.startTimeMs - last.endTimeMs
                if (pauseToLast in 0..MERGE_PAUSE_MS) {
                    // An linken Nachbarn anhängen
                    result[result.lastIndex] = last.copy(
                        text = buildString {
                            append(last.text.trim())
                            if (last.text.isNotBlank() && seg.text.isNotBlank()) append(" ")
                            append(seg.text.trim())
                        }.trim(),
                        endTimeMs = maxOf(last.endTimeMs, seg.endTimeMs),
                    )
                    mergedCount++
                    continue
                }
            }
            result.add(seg)
        }

        if (mergedCount > 0) {
            Log.d(TAG, "compactRawSegmentsBeforeAssignment: merged $mergedCount tiny segments " +
                    "(${segments.size} → ${result.size})")
        }
        return result
    }

    /**
     * Normalisiert Engine-lokale Speaker-IDs aus einem neuen Kandidaten-Lauf
     * auf stabile Session-ID durch temporale Überlappung mit bestehender Zuordnung.
     *
     * Beispiel: "speaker_0" aus Lauf 3 überschneidet sich zeitlich mit
     * "speaker_1" aus dem Bestand → wird zu "speaker_1" umgemappt.
     */
    fun normalizeSpeakerIds(
        candidate: List<TranscriptSegment>,
        existingAssignment: List<TranscriptSegment>,
    ): List<TranscriptSegment> {
        if (candidate.isEmpty() || existingAssignment.isEmpty()) return candidate

        // Bestehende Speaker-IDs ermitteln
        val existingSpeakerIds = existingAssignment
            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
            .distinct()
        val nextFreeNum = existingSpeakerIds.mapNotNull { id ->
            id.removePrefix("speaker_").toIntOrNull()
        }.maxOrNull()?.let { it + 1 } ?: 0
        val usedSessionIds = existingSpeakerIds.toMutableSet()
        var nextId = maxOf(nextFreeNum, existingSpeakerIds.size)
        val engineFallbackReason = mutableMapOf<Int, String>() // engineId → reason für Debug

        // Schritt 1: Für jede Engine-ID Overlap zu bestehenden Speakern sammeln
        // engineId → (existingSpeakerId → totalOverlapSec)
        val overlapVotes = mutableMapOf<Int, MutableMap<String, Float>>()

        for (seg in candidate) {
            val engineId = seg.speakerId?.removePrefix("speaker_")?.toIntOrNull() ?: continue
            val segStart = seg.startTimeMs / 1000f
            val segEnd = seg.endTimeMs / 1000f

            val votes = overlapVotes.getOrPut(engineId) { mutableMapOf() }
            for (ex in existingAssignment) {
                val exId = ex.speakerId?.takeIf { it.isNotBlank() } ?: continue
                val exStart = ex.startTimeMs / 1000f
                val exEnd = ex.endTimeMs / 1000f
                val o = maxOf(segStart, exStart).let { s -> minOf(segEnd, exEnd) - s }
                if (o > 0f) {
                    votes[exId] = (votes[exId] ?: 0f) + o
                }
            }
        }

        // Schritt 2a: Per Engine-ID den passendsten Session-Sprecher wählen (temporal overlap)
        val engineToSession = mutableMapOf<Int, String>()
        for ((engineId, votes) in overlapVotes) {
            val bestMatch = votes.maxByOrNull { it.value }
            val stableId = if (bestMatch != null && bestMatch.value > 0f) {
                engineFallbackReason[engineId] = "overlap=${"%.1f".format(bestMatch.value)}s"
                bestMatch.key
            } else {
                // Schritt 2b: Fallback per segmentId – wenn temporaler Overlap fehlschlägt
                val fallbackId = candidate
                    .filter { it.speakerId?.removePrefix("speaker_")?.toIntOrNull() == engineId }
                    .firstNotNullOfOrNull { seg ->
                        existingAssignment.firstOrNull { it.segmentId == seg.segmentId }
                            ?.speakerId?.takeIf { it.isNotBlank() }
                    }
                if (fallbackId != null) {
                    engineFallbackReason[engineId] = "idFallback"
                    fallbackId
                } else {
                    engineFallbackReason[engineId] = "NEW"
                    val newId = "speaker_$nextId"
                    usedSessionIds.add(newId)
                    nextId++
                    newId
                }
            }
            engineToSession[engineId] = stableId
        }

        // Schritt 2c: Reihenfolge-Fallback – wenn neue IDs erzeugt wurden, aber best
        // und candidate gleich viele Speaker haben → sequentiell nach erstem Auftreten mappen.
        // Verhindert Drift [0,1]→[2,3] bei fehlgeschlagenem Temporal-Overlap.
        val candEngineIds = candidate
            .mapNotNull { it.speakerId?.removePrefix("speaker_")?.toIntOrNull() }
            .distinct().sorted()
        if (candEngineIds.size == existingSpeakerIds.size && candEngineIds.size >= 2) {
            val hasUnmappedIds = engineToSession.values.any { it !in existingSpeakerIds }
            if (hasUnmappedIds) {
                val beforeMapping = engineToSession.toMap() // snapshot vor Fallback
                for ((i, engineId) in candEngineIds.withIndex()) {
                    if (i < existingSpeakerIds.size) {
                        engineToSession[engineId] = existingSpeakerIds[i]
                    }
                }
                // Welche IDs wurden durch den Fallback geändert?
                val overridden = candEngineIds.filter { engineToSession[it] != beforeMapping[it] }
                engineFallbackReason += (overridden.associateWith { "seqFallback" })
                Log.d(TAG, "normalizeSpeakerIds: sequentialFallback engineOrder=${
                    candEngineIds.joinToString(",")
                } bestOrder=${existingSpeakerIds.map { it.removePrefix("speaker_") }.joinToString(",")}")
            }
        }

        // Debug: Mapping loggen
        val mappingDetails = engineToSession.entries.joinToString(", ") { (engine, session) ->
            val engineSegments = candidate.count { seg ->
                seg.speakerId?.removePrefix("speaker_")?.toIntOrNull() == engine
            }
            val note = engineFallbackReason[engine] ?: "?"
            "$engine→${session.removePrefix("speaker_")}($engineSegments segs,$note)"
        }
        val bestSpeakers = existingSpeakerIds.map { it.removePrefix("speaker_") }.sorted().joinToString(",")
        val candLabels = candidate
            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
            .distinct().map { it.removePrefix("speaker_") }.sorted().joinToString(",")
        Log.d(TAG, "normalizeSpeakerIds: mapping=[$mappingDetails] | cand=$candLabels | best=$bestSpeakers")

        // Schritt 3: Alle Kandidaten-Segmente mit dem stabilen Mapping labeln
        return candidate.map { seg ->
            val engineId = seg.speakerId?.removePrefix("speaker_")?.toIntOrNull()
                ?: return@map seg
            val stableId = engineToSession[engineId] ?: return@map seg
            val num = stableId.removePrefix("speaker_").toIntOrNull() ?: 0
            seg.copy(speakerId = stableId, speakerLabel = "Sprecher ${num + 1}")
        }
    }

    /**
     * Zerlegt lange ASR-Segmente (> 8s) an Diarization-Sprecherwechsel-Grenzen.
     *
     * Text wird proportional zur Zeit auf die Sub-Segmente verteilt (wortbasiert).
     * rawFinalSegments bleiben unverändert – diese Methode arbeitet auf dem Overlay.
     *
     * @return Liste mit gesplitteten Sub-Segmenten + unveränderten Kurzsegmenten
     */
    fun splitLongSpeakerSegments(
        overlay: List<TranscriptSegment>,
        diarizationSegments: List<DiarizationSegment>,
    ): List<TranscriptSegment> {
        if (overlay.isEmpty() || diarizationSegments.isEmpty()) return overlay

        val result = mutableListOf<TranscriptSegment>()
        var totalSplitBefore = 0
        var totalSplitAfter = 0

        for (seg in overlay) {
            val durationMs = seg.endTimeMs - seg.startTimeMs
            // Splitte lange Segmente (> 8s) – auch OHNE gesetzte speakerId:
            // Ein rohes Whisper-Segment, das über einen Diarization-Wechsel hinweggeht,
            // muss vor dem Assignment getrennt werden, damit der Wechsel nicht vom
            // dominanten Sprecher geschluckt wird. Die Sub-Segmente erhalten ihre
            // Labels aus den überlappenden diarizationSegs (dominant pro Zeitfenster).
            if (durationMs < 8000L) {
                result.add(seg)
                continue
            }

            val asrStartSec = seg.startTimeMs / 1000f
            val asrEndSec = seg.endTimeMs / 1000f
            val asrDurationSec = asrEndSec - asrStartSec

            // Überlappende Diarization-Segmente finden
            val overlapping = diarizationSegments.filter { ds ->
                ds.startSec < asrEndSec && ds.endSec > asrStartSec
            }
            if (overlapping.size < 2) { result.add(seg); continue }

            val speakerIds = overlapping.map { it.speaker }.distinct()
            if (speakerIds.size < 2) { result.add(seg); continue }

            // Split-Punkte an Sprecherwechseln ermitteln
            val splits = mutableListOf(asrStartSec)
            for (i in 1 until overlapping.size) {
                if (overlapping[i].speaker != overlapping[i - 1].speaker) {
                    val pt = overlapping[i].startSec.coerceIn(asrStartSec, asrEndSec)
                    if (pt > splits.last() && pt < asrEndSec) splits.add(pt)
                }
            }
            splits.add(asrEndSec)
            if (splits.size <= 2) { result.add(seg); continue }

            val words = seg.text.trim().split("\\s+".toRegex())
            val totalWords = words.size

            totalSplitBefore++
            for (i in 0 until splits.size - 1) {
                val subStartSec = splits[i]
                val subEndSec = splits[i + 1]
                val subDurationSec = subEndSec - subStartSec
                if (subDurationSec <= 0f) continue

                // Dominanten Sprecher für dieses Zeitfenster bestimmen
                val speakerMap = mutableMapOf<Int, Float>()
                for (ds in overlapping) {
                    val oStart = maxOf(subStartSec, ds.startSec)
                    val oEnd = minOf(subEndSec, ds.endSec)
                    val o = oEnd - oStart
                    if (o > 0f) speakerMap[ds.speaker] = (speakerMap[ds.speaker] ?: 0f) + o
                }
                val best = speakerMap.maxByOrNull { it.value }
                val bestSpeakerId = best?.key
                val bestOverlapMs = if (best != null) ((best.value) * 1000f).toLong() else 0L

                // Text proportional zur Zeit verteilen
                val startRatio = (subStartSec - asrStartSec) / asrDurationSec
                val endRatio = (subEndSec - asrStartSec) / asrDurationSec
                val startWordIdx = (totalWords * startRatio).toInt().coerceIn(0, totalWords)
                val endWordIdx = (totalWords * endRatio).toInt().coerceIn(0, totalWords)
                val subText = if (startWordIdx < endWordIdx) {
                    words.slice(startWordIdx until endWordIdx).joinToString(" ")
                } else {
                    // Bei textloser Lücke: Platzhalter
                    "..."
                }

                val subStartMs = (subStartSec * 1000f).toLong()
                val subEndMs = (subEndSec * 1000f).toLong()

                // Verlustfreier Split: Auch wenn kein dominanter Sprecher über dem
                // Confidence-Gate liegt (z.B. Stille), wird das Sub-Segment erzeugt –
                // mit dem Original-Label des Eltern-Segments (bzw. null bei rohen
                // Segmenten). So gehen beim Split der Ground Truth keine Wörter verloren.
                val subSpeakerId = if (bestSpeakerId != null && bestOverlapMs >= MIN_OVERLAP_MS) {
                    "speaker_$bestSpeakerId"
                } else {
                    seg.speakerId
                }
                val subSpeakerLabel = if (bestSpeakerId != null && bestOverlapMs >= MIN_OVERLAP_MS) {
                    "Sprecher ${bestSpeakerId + 1}"
                } else {
                    seg.speakerLabel
                }

                result.add(TranscriptSegment(
                    segmentId = java.util.UUID.randomUUID().toString(),
                    text = subText.ifBlank { seg.text },
                    startTimeMs = subStartMs,
                    endTimeMs = subEndMs,
                    isFinal = true,
                    isNew = false,
                    speakerId = subSpeakerId,
                    speakerLabel = subSpeakerLabel,
                    timestamp = seg.timestamp,
                ))
                totalSplitAfter++
            }
        }

        if (totalSplitBefore > 0) {
            Log.i(TAG, "splitLongSpeakerSegments: $totalSplitBefore segments split into $totalSplitAfter sub-segments " +
                    "(total ${result.size} segments)")
        }
        return result
    }

    /**
     * Führt benachbarte finale Segmente mit gleichem Sprecher
     * und kurzer Pause zusammen – NUR für die Anzeige.
     */
    fun mergeSegmentsForDisplay(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.sortedBy { it.startTimeMs }
        val merged = mutableListOf<TranscriptSegment>()

        for (seg in sorted) {
            val last = merged.lastOrNull()
            // 0.6.5: Merge auch bei gleichem LABEL (nicht nur gleicher ID) – im
            // Rolling-Lauf kann derselbe akustische Sprecher durch Chunk-Wechsel
            // verschiedene speakerId-Werte haben (Drift); das Label ist nach
            // renumber die stabile Anzeige-Größe. Beide Segmente müssen gelabelt
            // sein (konservativ: unlabeled bleibt getrennt).
            val canMerge = last != null
                && last.isFinal
                && seg.isFinal
                && !last.speakerId.isNullOrBlank()
                && !seg.speakerId.isNullOrBlank()
                && (last.speakerId == seg.speakerId || last.speakerLabel == seg.speakerLabel)
                && seg.startTimeMs >= last.endTimeMs
                && (seg.startTimeMs - last.endTimeMs) <= MERGE_PAUSE_MS

            if (canMerge) {
                merged[merged.lastIndex] = last.copy(
                    text = buildString {
                        append(last.text.trim())
                        if (last.text.isNotBlank() && seg.text.isNotBlank()) append(" ")
                        append(seg.text.trim())
                    }.trim(),
                    endTimeMs = maxOf(last.endTimeMs, seg.endTimeMs),
                    isNew = last.isNew || seg.isNew,
                )
            } else {
                merged.add(seg)
            }
        }
        return merged
    }
}
