package com.sherpa.transcript.engine

import android.util.Log
import com.sherpa.transcript.domain.model.TranscriptSegment

/**
 * Finale Konsolidierung von Transkript-Segmenten vor dem Speichern.
 *
 * Läuft nur einmal am Ende einer Aufnahme (post-processing).
 * rawFinalSegments bleiben unverändert – diese Klasse arbeitet auf
 * einer Kopie (dem Overlay aus raw + assigned).
 *
 * Alle Schritte sind einzeln testbar und über Feature-Flags steuerbar.
 */
object FinalTranscriptComposer {

    private const val TAG = "FinalTranscriptComposer"

    // ── Schwellenwerte ──────────────────────────────────────────────

    /** Maximale Pause zwischen zwei Segmenten gleichen Sprechers für Merge (ms) */
    private const val MAX_PAUSE_FOR_COMPACT_MS = 1200L

    /** Max. Dauer eines Mikro-Fragments, das vererbt werden darf (ms) */
    private const val MAX_TINY_FRAGMENT_DURATION_MS = 800L

    /** Max. Zeichen eines Mikro-Fragments (z. B. ".", "ja", "ok") */
    private const val MAX_TINY_FRAGMENT_CHARS = 3

    /** Max. Dauer einer kurzen Sprecher-Insel (A-B-A), die geglättet wird (ms) */
    private const val MAX_SHORT_ISLAND_DURATION_MS = 2000L

    /** Max. Wortanzahl einer kurzen Sprecher-Insel */
    private const val MAX_SHORT_ISLAND_WORDS = 3

    /** Max. Pause zum Nachbarn für Label-Vererbung (ms) */
    private const val MAX_PAUSE_FOR_INHERIT_MS = 500L

    // ── Feature-Flags ───────────────────────────────────────────────

    /** Phase 3: Riskante Speaker-Consolidation (falsche Zusammenführungen möglich) */
    private const val ENABLE_SPEAKER_CONSOLIDATION = false

    // ── Hauptpipeline ───────────────────────────────────────────────

    /**
     * Führt alle Final-Konsolidierungsschritte auf dem Overlay aus.
     * Liefert die finale, speicherfertige Segmentliste.
     */
    fun enrichAssignmentForSave(overlay: List<TranscriptSegment>): List<TranscriptSegment> {
        if (overlay.isEmpty()) return overlay

        Log.i(TAG, "FinalConsolidation START: segments=${overlay.size}")

        // Schritt 1: Kurze Sprecher-Inseln glätten (A-B-A → A-A-A)
        val step1 = smoothShortSpeakerIslands(overlay)
        logStep("ISLAND_SMOOTH", overlay.size, step1)

        // Schritt 1b: Unlabeled Mikrosegmente zwischen zwei gleichen Speakern auffüllen (A-?-A)
        val step1b = fillUnlabeledBetweenSameSpeakers(step1)
        logStep("FILL_ISLANDS", step1.size, step1b)

        // Schritt 2: Kleine unlabeled Fragmente an Nachbar vererben
        val step2 = inheritSpeakerForTinyUnlabeledSegments(step1b)
        logStep("INHERIT", step1b.size, step2)

        // Schritt 3 (optional): Äquivalente Sprecher zusammenführen
        val step3 = if (ENABLE_SPEAKER_CONSOLIDATION) {
            val s = mergeEquivalentSpeakers(step2)
            logStep("MERGE_SPEAKERS", step2.size, s)
            s
        } else step2

        // Schritt 4: Benachbarte gleiche Sprecher für History kompaktieren
        val step4 = compactSegmentsForHistory(step3)
        logStep("COMPACT", step3.size, step4)

        // Schritt 5: Sprecher-Nummern nach erstem Auftreten
        val result = renumberSpeakersByFirstAppearance(step4)
        val speakerCount = result.mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }.distinct().size
        Log.i(TAG, "FinalConsolidation DONE: saved=${result.size} speakers=$speakerCount")

        return result
    }

    // ── Einzelschritte ──────────────────────────────────────────────

    /**
     * Baut aus raw + assigned das verlustfreie Overlay.
     * Gleiche Logik wie buildAssignedOverlayForAllRawSegments() im ViewModel,
     * aber als stateless-Funktion.
     */
    fun buildOverlayFromRawAndAssigned(
        rawSegments: List<TranscriptSegment>,
        assignedSegments: List<TranscriptSegment>,
    ): List<TranscriptSegment> {
        val bestById = assignedSegments.associateBy { it.segmentId }
        return rawSegments.map { raw ->
            val best = bestById[raw.segmentId]
            if (best != null && !best.speakerId.isNullOrBlank()) {
                raw.copy(speakerId = best.speakerId, speakerLabel = best.speakerLabel)
            } else {
                raw
            }
        }
    }

    /**
     * Glättet kurze Sprecher-Inseln (Muster A-B-A).
     *
     * Wenn ein Segment B zwischen zwei längeren Segmenten desselben Sprechers A
     * liegt und B sehr kurz ist (< 2s, < 3 Wörter), wird B auf A gesetzt.
     *
     * Heuristik: B muss ein „Ausreißer“ sein – kurz, wenige Wörter, umgeben
     * von konsistentem Sprecher A auf beiden Seiten.
     */
    fun smoothShortSpeakerIslands(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.size < 3) return segments

        // Guard: Wie oft kommt jeder Speaker insgesamt vor?
        // Ein Center wird nur geglättet, wenn sein Speaker dadurch nicht komplett
        // verschwindet (verhindert 2→1-Kollaps, wenn Sprecher 2 nur in Inseln vorkommt).
        val speakerTotalCount = segments
            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }
            .eachCount()

        return segments.mapIndexed { i, seg ->
            if (i == 0 || i == segments.size - 1) return@mapIndexed seg
            val left = segments[i - 1]
            val right = segments[i + 1]
            val center = seg

            // Alle drei müssen ein Label haben
            val leftSpeaker = left.speakerId?.takeIf { it.isNotBlank() } ?: return@mapIndexed seg
            val centerSpeaker = center.speakerId?.takeIf { it.isNotBlank() } ?: return@mapIndexed seg
            val rightSpeaker = right.speakerId?.takeIf { it.isNotBlank() } ?: return@mapIndexed seg

            // Links == Rechts, aber Center anders
            if (leftSpeaker != rightSpeaker || centerSpeaker == leftSpeaker) return@mapIndexed seg

            // Center ist kurz und wenige Wörter
            val centerDuration = center.endTimeMs - center.startTimeMs
            val centerWords = center.text.trim().split("\\s+".toRegex()).size
            if (centerDuration > MAX_SHORT_ISLAND_DURATION_MS || centerWords > MAX_SHORT_ISLAND_WORDS) return@mapIndexed seg

            // Prüfe ob Center wirklich ein Fragment ist im Vergleich zu beiden Nachbarn
            val leftDuration = left.endTimeMs - left.startTimeMs
            val rightDuration = right.endTimeMs - right.startTimeMs
            if (centerDuration * 3 > leftDuration && centerDuration * 3 > rightDuration) return@mapIndexed seg

            // Kollaps-Guard: Center ist das LETZTE Segment seines Speakers → nicht glätten
            val remainingAfterSmooth = (speakerTotalCount[centerSpeaker] ?: 0) - 1
            if (remainingAfterSmooth <= 0) {
                Log.d(TAG, "smoothShortSpeakerIslands: skipped last occurrence of $centerSpeaker (would collapse speaker set)")
                return@mapIndexed seg
            }

            // Glätten: Center bekommt Sprechernamen von links/rechts
            seg.copy(speakerId = left.speakerId, speakerLabel = left.speakerLabel)
        }
    }

    /**
     * Füllt unlabeled Mikrosegmente zwischen zwei Segmenten mit DEMSELBEN Sprecher auf (A-?-A).
     *
     * Bedingungen (konservativ):
     * - Center hat KEIN Label
     * - Links und rechts haben ein Label, und zwar das GLEICHE
     * - Center ist kurz (<= 2000ms ODER <= 3 Wörter)
     *
     * Ambivalente Fälle (links != rechts) bleiben bewusst unangetastet,
     * weil sie echte Sprecherwechsel enthalten könnten.
     */
    fun fillUnlabeledBetweenSameSpeakers(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.size < 3) return segments

        var filled = 0
        val result = segments.mapIndexed { i, seg ->
            if (i == 0 || i == segments.size - 1) return@mapIndexed seg
            if (!seg.speakerId.isNullOrBlank()) return@mapIndexed seg // schon gelabelt

            val left = segments[i - 1]
            val right = segments[i + 1]
            val leftSpeaker = left.speakerId?.takeIf { it.isNotBlank() } ?: return@mapIndexed seg
            val rightSpeaker = right.speakerId?.takeIf { it.isNotBlank() } ?: return@mapIndexed seg
            if (leftSpeaker != rightSpeaker) return@mapIndexed seg // A-?-B → nicht eindeutig

            // Kurzes Mikrosegment: <= 2s ODER <= 3 Wörter
            val dur = seg.endTimeMs - seg.startTimeMs
            val words = seg.text.trim().split("\\s+".toRegex()).size
            if (dur > MAX_SHORT_ISLAND_DURATION_MS && words > MAX_SHORT_ISLAND_WORDS) return@mapIndexed seg

            filled++
            Log.d(TAG, "fillUnlabeledBetweenSameSpeakers: filled id=${seg.segmentId.take(8)} with $leftSpeaker (${"%.1f".format(dur / 1000f)}s, ${words} words)")
            seg.copy(speakerId = leftSpeaker, speakerLabel = left.speakerLabel)
        }
        if (filled > 0) {
            Log.i(TAG, "fillUnlabeledBetweenSameSpeakers: filled $filled unlabeled island(s)")
        }
        return result
    }

    /**
     * Vererbt Speaker-Label an sehr kurze unlabeled Segmente.
     *
     * Ein unlabeled Segment bekommt den Sprecher des linken Nachbarn, wenn:
     * - es sehr kurz ist (< 800 ms) ODER nur 1–2 Zeichen hat
     * - die Pause zum linken Nachbarn < 500 ms beträgt
     * - der linke Nachbar ein Label hat
     *
     * Wenn kein linker Nachbar existiert oder dieser kein Label hat → rechten
     * Nachbar versuchen. Wenn beide Seiten unterschiedliche Sprecher haben
     * und das Fragment nicht eindeutig ist → unlabeled lassen (sicherer).
     */
    fun inheritSpeakerForTinyUnlabeledSegments(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        return segments.mapIndexed { i, seg ->
            // Nur unlabeled Segmente betrachten
            if (!seg.speakerId.isNullOrBlank()) return@mapIndexed seg

            val duration = seg.endTimeMs - seg.startTimeMs
            val charCount = seg.text.trim().length

            // Ist es ein Mikro-Fragment?
            val isTiny = duration <= MAX_TINY_FRAGMENT_DURATION_MS || charCount <= MAX_TINY_FRAGMENT_CHARS
            if (!isTiny) return@mapIndexed seg

            // Linken Nachbarn prüfen
            if (i > 0) {
                val left = segments[i - 1]
                val leftSpeaker = left.speakerId?.takeIf { it.isNotBlank() }
                if (leftSpeaker != null) {
                    val pauseToLeft = seg.startTimeMs - left.endTimeMs
                    if (pauseToLeft in 0..MAX_PAUSE_FOR_INHERIT_MS) {
                        return@mapIndexed seg.copy(speakerId = left.speakerId, speakerLabel = left.speakerLabel)
                    }
                }
            }

            // Rechten Nachbarn prüfen
            if (i < segments.size - 1) {
                val right = segments[i + 1]
                val rightSpeaker = right.speakerId?.takeIf { it.isNotBlank() }
                if (rightSpeaker != null) {
                    val pauseToRight = right.startTimeMs - seg.endTimeMs
                    if (pauseToRight in 0..MAX_PAUSE_FOR_INHERIT_MS) {
                        return@mapIndexed seg.copy(speakerId = right.speakerId, speakerLabel = right.speakerLabel)
                    }
                }
            }

            // Kein eindeutiger Nachbar – unlabeled lassen
            seg
        }
    }

    /**
     * Führt Sprecher zusammen, die vermutlich dieselbe Person sind.
     *
     * AKTUELL: Nur aktiv, wenn ENABLE_SPEAKER_CONSOLIDATION = true.
     *
     * Heuristik:
     * - Ein Sprecher, der in genau 1 kurzen Segment (< 2s) auftaucht,
     *   umgeben von einem anderen Sprecher → mit diesem zusammenführen
     * - Ein Sprecher mit nur 1 Segment insgesamt → mit nächstem Nachbarn mergen
     *
     * RISIKEN:
     * - Kann echte Sprecher fälschlich zusammenführen
     * - Nur für Test-/Optimierungszwecke gedacht
     */
    fun mergeEquivalentSpeakers(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        // Zähle Segmente pro Speaker
        val speakerCounts = mutableMapOf<String, Int>()
        val speakerDurations = mutableMapOf<String, Long>()
        for (seg in segments) {
            val sid = seg.speakerId?.takeIf { it.isNotBlank() } ?: continue
            speakerCounts[sid] = (speakerCounts[sid] ?: 0) + 1
            speakerDurations[sid] = (speakerDurations[sid] ?: 0L) + (seg.endTimeMs - seg.startTimeMs)
        }

        // Identifiziere Kandidaten: nur 1 Segment ODER sehr kurze Gesamtdauer
        val mergeCandidates = speakerCounts.filter { (sid, count) ->
            count == 1 || (speakerDurations[sid] ?: 0L) < 2000L
        }.keys

        if (mergeCandidates.isEmpty()) return segments

        // Für jeden Kandidaten: finde besten Ersatzsprecher (nächster Nachbar)
        val mergeMap = mutableMapOf<String, String>() // old → new
        val mergeLabelMap = mutableMapOf<String, String>() // old → new label

        for (sid in mergeCandidates) {
            val indices = segments.indices.filter { segments[it].speakerId == sid }
            if (indices.isEmpty()) continue

            val idx = indices.first()
            // Nach links suchen
            var replacement: String? = null
            var replacementLabel: String? = null
            for (j in (idx - 1) downTo 0) {
                val s = segments[j].speakerId?.takeIf { it.isNotBlank() && it != sid }
                if (s != null) { replacement = s; replacementLabel = segments[j].speakerLabel; break }
            }
            // Nach rechts suchen, falls links nichts
            if (replacement == null) {
                for (j in (idx + 1) until segments.size) {
                    val s = segments[j].speakerId?.takeIf { it.isNotBlank() && it != sid }
                    if (s != null) { replacement = s; replacementLabel = segments[j].speakerLabel; break }
                }
            }
            if (replacement != null) {
                Log.d(TAG, "Consolidation MERGE: speaker=$sid -> $replacement (1 segment)")
                mergeMap[sid] = replacement
                mergeLabelMap[sid] = replacementLabel ?: "Sprecher ${replacement.last()}"

                // Auch wenn der Ersatz selbst ein Kandidat ist: in die gleiche Richtung mergen
                val replacementCount = speakerCounts[replacement] ?: 0
                if (replacementCount <= 1) {
                    // Zwei Einzelgänger: beide zum nächsthäufigeren mergen
                }
            }
        }

        if (mergeMap.isEmpty()) return segments

        return segments.map { seg ->
            val sid = seg.speakerId ?: return@map seg
            val replacement = mergeMap[sid] ?: return@map seg
            seg.copy(speakerId = replacement, speakerLabel = mergeLabelMap[sid])
        }
    }

    /**
     * Fasst benachbarte Segmente mit gleichem Sprecher zusammen.
     *
     * Analog zu TimelineComposer.mergeSegmentsForDisplay(), aber für den
     * finalen Save (und mit eigenen Schwellenwerten).
     */
    fun compactSegmentsForHistory(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.sortedBy { it.startTimeMs }
        val merged = mutableListOf<TranscriptSegment>()

        for (seg in sorted) {
            val last = merged.lastOrNull()
            val canMerge = last != null
                    && last.isFinal
                    && seg.isFinal
                    && !last.speakerId.isNullOrBlank()
                    && last.speakerId == seg.speakerId
                    && seg.startTimeMs >= last.endTimeMs
                    && (seg.startTimeMs - last.endTimeMs) <= MAX_PAUSE_FOR_COMPACT_MS

            if (canMerge) {
                merged[merged.lastIndex] = last.copy(
                    text = buildString {
                        append(last.text.trim())
                        if (last.text.isNotBlank() && seg.text.isNotBlank()) append(" ")
                        append(seg.text.trim())
                    }.trim(),
                    endTimeMs = maxOf(last.endTimeMs, seg.endTimeMs),
                )
            } else {
                merged.add(seg)
            }
        }
        return merged
    }

    /**
     * Nummeriert Sprecher nach erstem Auftreten im finalen Verlauf um.
     *
     * Beispiel: speaker_3, speaker_1, speaker_3 → Sprecher 1, Sprecher 2, Sprecher 1
     * (wenn speaker_3 zuerst auftritt, wird es zu Sprecher 1)
     */
    fun renumberSpeakersByFirstAppearance(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        val order = linkedMapOf<String, Int>() // speakerId → index (1-based)
        val labels = linkedMapOf<String, String>() // speakerId → label

        for (seg in segments) {
            val sid = seg.speakerId?.takeIf { it.isNotBlank() } ?: continue
            if (sid !in order) {
                val num = order.size + 1
                order[sid] = num
                labels[sid] = "Sprecher $num"
            }
        }

        if (order.isEmpty()) return segments

        return segments.map { seg ->
            val sid = seg.speakerId?.takeIf { it.isNotBlank() } ?: return@map seg
            val newLabel = labels[sid] ?: return@map seg
            seg.copy(speakerLabel = newLabel)
        }
    }

    // ── Hilfsfunktionen ─────────────────────────────────────────────

    private fun countSpeakers(segments: List<TranscriptSegment>): Int =
        segments.mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }.distinct().size

    private fun logStep(stepName: String, before: Int, after: List<TranscriptSegment>) {
        val speakersAfter = after.mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }.distinct().size
        if (before != after.size || speakersAfter < countSpeakers(after)) {
            Log.i(TAG, "FinalConsolidation $stepName: $before -> ${after.size} (speakers=$speakersAfter)")
        } else {
            Log.d(TAG, "FinalConsolidation $stepName: $before (unchanged, speakers=$speakersAfter)")
        }
    }
}
