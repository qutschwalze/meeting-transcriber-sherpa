package com.sherpa.transcript.engine

import android.os.Environment
import android.util.Log
import com.sherpa.transcript.SherpaTranscriptApp
import com.sherpa.transcript.domain.audio.AudioChunk
import com.sherpa.transcript.domain.audio.ChunkedAudioBuffer
import com.sherpa.transcript.domain.audio.TestLog
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

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
    /** Hebel-G-Diagnose: wie oft die Voice-Bank aufgelöst / eingeschrieben / übersprungen hat. */
    val voiceBankResolvedCount: Int = 0,
    val voiceBankEnrolledCount: Int = 0,
    val voiceBankSkipCount: Int = 0,
    val voiceBankSize: Int = 0,
    /** Phase 7 (Global-Bank): wie oft ein lokales Segment über ein GLOBALES Profil aufgelöst wurde. */
    val globalResolvedCount: Int = 0,
    /** Phase 7: Größe der Session→Profil-Zuordnungstabelle (Diagnose). */
    val globalProfileMapSize: Int = 0,
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
    /** Phase 7: persistente Speaker-Profile (optional – null = Verhalten unverändert). */
    private val globalBank: GlobalVoiceBank? = null,
    private val chunkSec: Float = 20f,
    private val overlapSec: Float = 5f,
) {

    companion object {
        private const val TAG = "DiarizationChunkWorker"

        /** Toleranz für Float-Grenzenvergleiche (Sekunden). */
        private const val EPS = 0.01f

        /** Sample-Rate des Audio-Streams (16 kHz) – für Segment-Audio-Extraktion. */
        private const val SAMPLE_RATE = 16000

        /** Mindest-Samples für einen Retry-Versuch (10s Audio). */
        private const val MIN_RETRY_SAMPLES = 10 * SAMPLE_RATE

        /**
         * Noise Gate (0.5.57): RELATIV zum Signal-RMS (0.5.59).
         *
         * 0.5.57: Absolutes Gate (|x| < 0.001 → 0). Log-Befund 0.5.58: Die
         * Mikrofon-Aufnahme ist deutlich leiser als die MP3-Quelle – Chunk
         * [55,75] hat dort RMS 0.0005, das Gate löschte fast ALLE Samples →
         * totale Stille → Pyannote: 0 Segmente (Regression durch das Gate!).
         *
         * 0.5.59: Schwelle = Anteil des Signal-RMS. Leise Sprache (RMS 0.0005)
         * überlebt (Schwelle 0.00005), echtes Rauschen unterhalb der relativen
         * Schwelle wird trotzdem gekillt. Der Pegel skaliert mit.
         */
        private const val noiseGateRatio = 0.1f

        /** Absolute Untergrenze der Gate-Schwelle (Schutz vor Divisionseffekten). */
        private const val noiseFloorAbs = 0.00001f

        /**
         * Gain-Limit (0.5.57): Harte Obergrenze für den RMS-Boost.
         * Log-Beweis 0.5.56: Boost 197x (RMS 0.0005) riss den Noise Floor hoch →
         * Pyannote sah nur Rauschen, alles kollabierte auf 1 Sprecher.
         * 10x = +20 dB, genug um leise Sprecher hörbar zu machen.
         */
        private const val maxBoostFactor = 10f

        /** Phase 10 (Fix 1): Max. Lücke (Sekunden) für den Kontinuitätserbe –
         * ein Block ohne Bank-Match erbt die ID des Vorgängers, wenn er direkt
         * anschließt. Host-Befund: Vorgänger-Sim mean 0.819 (vs. 0.479 zur
         * Referenz) → 2 s ist konservativ und deckt Monolog-Blöcke ab. */
        private const val CONTINUITY_GAP_SEC = 2f

        /**
         * Chunk-Retry (0.5.54): Mehrere Versätze für den 2. Engine-Versuch.
         * Log-Befund 0.5.53: Chunk [55,75] liefert reproduzierbar 0 Segmente –
         * ein einzelner 5s-Versatz reicht nicht, mehrere Fenster werden probiert.
         */
        private val RETRY_OFFSETS_SEC = floatArrayOf(3f, 5f, 7f, 10f)
    }

    /** Globaler Bestand: bestätigte Diarization-Segmente mit Session-weiten Speaker-IDs. */
    var globalSegments: List<SpeakerTimeRange> = emptyList()
        private set

    /**
     * Phase 7: Session-GID → globales Profil. Stabile Identität über die
     * Session: Kontakte derselben globalen Stimme (auch bei Drift) landen
     * auf derselben Session-ID.
     */
    private val globalProfileBySessionId = mutableMapOf<Int, String>()

    /** Reset für eine neue Aufnahme-Session. */
    fun reset() {
        globalSegments = emptyList()
        globalProfileBySessionId.clear()
    }

    /**
     * Phase 7a (0.7.2): Session-GID → Profil-ID (read-only Kopie) für die
     * Namens-Auflösung im ViewModel (Anzeige/Export, nie für raw/assigned).
     */
    fun globalProfileBySessionId(): Map<Int, String> = globalProfileBySessionId.toMap()

    /**
     * Phase 9d (0.9.5): Session-GID manuell einem Profil zuordnen – aufgerufen
     * vom ViewModel nach `assignSpeakerToSegment` (manueller ENROLL), damit die
     * Namens-Auflösung (Anzeige + Export) den neuen Fingerprint SOFORT kennt
     * und nicht erst beim nächsten automatischen Global-Match.
     */
    fun registerProfileMapping(sessionId: Int, profileId: String) {
        globalProfileBySessionId[sessionId] = profileId
    }

    /**
     * Phase 10 (0.9.9): Originale Session-GID → Anzeige-ID (nach VM-Renumber).
     *
     * Problem: renumberLiveSpeakerIds() im ViewModel nummeriert die IDs der
     * ANZEIGE um (Bank-8 → speaker_1), aber die Bank/der Save-Pfad vergleichen
     * gegen BANK-Nummern. Diese Brücke liefert pro Anzeige-ID die ursprüngliche
     * GID aus dem globalen Bestand: Die Reihenfolge des Bestands (erste
     * Auftrittszeit je ID) entspricht exakt der Renumber-Ordnung des ViewModels.
     */
    fun originalGidToDisplayId(): Map<Int, Int> {
        val orderedGids = globalSegments
            .groupBy { it.speakerId }
            .map { (gid, segs) -> gid to (segs.minOfOrNull { it.startSec } ?: Float.MAX_VALUE) }
            .sortedBy { it.second }
            .mapIndexed { idx, (gid, _) -> gid to idx }
        return orderedGids.toMap()
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
        // Hebel C (0.5.55): Audio normalisieren, bevor es an Pyannote geht.
        // Log-Befund 0.5.54: Chunk [55,75] liefert auch nach 4 Retry-Offsets 0
        // Segmente – Whisper transkribiert dort aber Text → zu leise für die
        // Pyannote-VAD. RMS-Boost hebt leise Passagen auf einen gesunden Pegel.
        // WICHTIG: Nur der Engine-Pfad nutzt die normalisierten Samples – die
        // Voice-Bank-Extraktion (extractSegmentSamples) arbeitet weiter mit dem
        // Original-Audio, sonst würden die Embeddings durch den Boost verzerrt.
        val normalizedSamples = normalizeAudio(chunk.samples)
        var engineSegments = diarizer.process(normalizedSamples)

        // 0.5.74: Chunk-Diagnose-WAV (Debug-Mode) – die tatsächlichen Samples,
        // die die Engine bekommt, für Host-Vergleich speichern. Log-Befund
        // 0.5.73: App-Chunk [55,75] segmentiert als "A bis 65,97s", obwohl die
        // WAV dort Sprecher B hat (Titanet cos→B=0,73 ab 55s) – Chunk-RMS
        // identisch zur WAV, aber Segmentierung weicht ab. Chunk-WAVs erlauben
        // den sample-genauen Vergleich App-Chunk vs. WAV-Fenster.
        if (TestLog.path != null) saveChunkWav(chunk)

        // Chunk-Retry (0.5.53+): Bei 0 segments mehrere versetzte Fenster versuchen.
        // Log-Befund: Chunk [55,75] liefert reproduzierbar 0 Segmente – ein einzelner
        // 5s-Versatz reicht nicht. Wir probieren mehrere Offsets (3s/7s/10s), bis die
        // Engine liefert. Fehlschläge werden geloggt, damit der Retry sichtbar ist.
        var retryOffsetSecApplied = 0f
        if (engineSegments.isEmpty() && normalizedSamples.size >= MIN_RETRY_SAMPLES) {
            for (offset in RETRY_OFFSETS_SEC) {
                val offsetSamples = (offset * SAMPLE_RATE).toInt()
                if (offsetSamples >= normalizedSamples.size) continue
                // Retry-Samples aus dem NORMALISIERTEN Audio schneiden (Boost bleibt aktiv)
                val retrySamples = normalizedSamples.copyOfRange(offsetSamples, normalizedSamples.size)
                val retrySegments = diarizer.process(retrySamples)
                if (retrySegments.isNotEmpty()) {
                    Log.i(TAG, "processChunk: Retry SUCCESS – Offset ${offset}s: ${retrySegments.size} Segmente")
                    engineSegments = retrySegments
                    retryOffsetSecApplied = offset
                    break
                }
                Log.d(TAG, "processChunk: Retry Offset ${offset}s fehlgeschlagen (0 Segmente)")
            }
            if (engineSegments.isEmpty()) {
                Log.w(TAG, "processChunk: ALLE Retry-Offsets fehlgeschlagen – Chunk bleibt ohne Diarization")
            }
        }

        // ── 3: Time-Shift auf absolute Session-Zeit ──
        val absoluteSegments = engineSegments.map { seg ->
            DiarizationSegment(
                startSec = seg.startSec + chunk.startSec + retryOffsetSecApplied,
                endSec = seg.endSec + chunk.startSec + retryOffsetSecApplied,
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

        // ── 4b (Hebel G, Phase 6): Voice-Bank für ALLE lokalen IDs ──
        // Nicht nur new-IDs: Auch gemappte Segmente werden gegen die Bank
        // identifiziert (wiederkehrende Stimme → Mapping auf die Bank-ID =
        // ID-Stabilität; 2. Kontakt gegen ein pending → confirmPending passiert
        // in identify()). Die Bank wird PRO lokaler ID neu geprüft – nach einem
        // Enroll ist sie nicht mehr leer, der nächste Kontakt (z.B. der
        // Monologue-Split derselben Stimme) wird dann identified und auf die
        // bestehende pending-ID gemappt statt als eigene Stimme eingeschrieben.
        // Kein Match + nicht new → Phantom-/Fehlzuordnungs-Regel:
        //   a) Reconciler-Ziel-ID ohne Bank-Verankerung → echte neue Stimme
        //      (der 1. Kontakt war zu kurz fürs Enrollment) → unter der Ziel-ID
        //      enrollen (Quick-Confirm greift bei langer Redezeit)
        //   b) Ziel-ID in der Bank (Zone-Vote-Fehlzuordnung, z.B. B auf A
        //      gemappt) → frische ID + enroll (2. Kontakt bestätigt sie dann)
        var finalMapping = result.mapping
        var finalNewSpeakerIds = result.newSpeakerIds
        var driftResolvedCount = 0
        var enrolledCount = 0
        var skippedCount = 0
        var globalResolvedCount = 0
        // Phase 10 (Fix 1): Kontext des letzten zeitlich gemappten Blocks (Kontinuitätserbe)
        var lastMappedEndSec = -Float.MAX_VALUE
        var lastMappedGlobalId: Int? = null
        var lastMappedLocalId = Int.MIN_VALUE
        var continuityInheritedCount = 0
        val bankSize = voiceBank?.speakerCount ?: 0
        var freshGlobalId = (globalSegments.maxOfOrNull { it.speakerId } ?: -1) + 1
        if (voiceBank != null) {
            val allLocalIds = absoluteSegments.map { it.speaker }.distinct().sorted()
            for (localId in allLocalIds) {
                val segsOfSpeaker = absoluteSegments.filter { it.speaker == localId }
                val best = segsOfSpeaker.maxByOrNull { it.endSec - it.startSec } ?: continue
                val samples = extractSegmentSamples(chunk, best)
                if (samples.isEmpty()) {
                    skippedCount++
                    Log.d(TAG, "VOICE_BANK check: local=$localId – kein Audio extrahierbar (skip)")
                    continue
                }
                val durationMs = ((best.endSec - best.startSec) * 1000f).toLong()

                val bankNonEmpty = voiceBank.speakerCount > 0 || voiceBank.pendingCount > 0
                val matchedGlobalId = if (bankNonEmpty) voiceBank.identify(samples) else null
                if (matchedGlobalId != null) {
                    // Stimme bekannt → lokale ID auf die Bank-ID mappen (Drift/Stabilität)
                    finalMapping = finalMapping + (localId to matchedGlobalId)
                    finalNewSpeakerIds = finalNewSpeakerIds - localId
                    driftResolvedCount++
                    // Phase 10: Kontinuitäts-Kontext nachführen
                    lastMappedEndSec = best.endSec
                    lastMappedGlobalId = matchedGlobalId
                    lastMappedLocalId = localId
                    Log.d(TAG, "VOICE_BANK resolve: local=$localId → global=$matchedGlobalId " +
                            "(dur=${durationMs}ms, statt neue ID ${result.mapping[localId]})")
                    TestLog.log("VB local=$localId dur=${durationMs}ms → RESOLVE auf global=$matchedGlobalId (statt neue ID ${result.mapping[localId]})")
                    continue
                }

                // ── Phase 10 (Fix 1): Sprechkontinuität (Kontinuitätserbe) ──
                // Host-Befund (standup_drift_test.py, 37-min-Standup): 95/144
                // Monolog-Blöcke fielen <0.62 gegen die Referenz → neue IDs.
                // Der VORGÄNGER ist dagegen stabil (mean sim 0.819, 88% >=0.62).
                // Ein Block ohne Bank-Match, der zeitlich DIREKT an einen bereits
                // gemappten Block anschließt (<2s Lücke), erbt dessen globale ID –
                // BEVOR eine neue ID gespawnt wird. Die 0.62-Regel bleibt unangetastet.
                if (best.startSec - lastMappedEndSec < CONTINUITY_GAP_SEC &&
                    lastMappedEndSec >= best.startSec - CONTINUITY_GAP_SEC &&
                    lastMappedGlobalId != null && lastMappedLocalId != localId
                ) {
                    finalMapping = finalMapping + (localId to lastMappedGlobalId)
                    finalNewSpeakerIds = finalNewSpeakerIds - localId
                    continuityInheritedCount++
                    // Phase 10: Kontext weiterschreiben (der Erbe wird selbst Anker)
                    lastMappedEndSec = best.endSec
                    lastMappedLocalId = localId
                    TestLog.log("VB local=$localId dur=${durationMs}ms → KONTINUITÄT global=$lastMappedGlobalId " +
                            "(gap=${(best.startSec - lastMappedEndSec).toInt()}ms, statt neue ID ${result.mapping[localId]})")
                    continue
                }

                // ── Phase 7: Global-Bank-Resolve ──
                // Die Session-Bank kennt die Stimme nicht (neuer Kontakt) – aber die
                // PERSISTENTE globale Bank vielleicht schon (Person aus früherer
                // Session). Dann: Session-ID über das Profil vergeben/behalten und
                // KEIN neues Enrollment (Mapping-only – die Session-Bank-Kontinuität
                // entsteht über wiederholte Global-Matches derselben Stimme).
                // Kein Session-Bank-Enroll hier: Eine Stimme, die global gematcht
                // wurde, darf nicht zusätzlich als pending eingeschrieben werden –
                // das würde zwei Session-IDs für dieselbe Person erzeugen.
                var globalResolved = false
                if (globalBank != null) {
                    val profileId = globalBank.identifySamples(samples)
                    if (profileId != null) {
                        val existing = globalProfileBySessionId.entries.firstOrNull { it.value == profileId }
                        val sessionId = existing?.key ?: freshGlobalId.also { freshGlobalId++ }
                        if (existing == null) globalProfileBySessionId[sessionId] = profileId
                        // Session-Bank einlernen (Geräte-Befund 0.7.0 Session 2):
                        // Ohne Enroll ist die global gemappte Session-ID für die
                        // finalen Resolves NICHT bestätigt (die nutzen nur die
                        // Session-Bank) → Nearest-Confirmed-Resolve mappt die
                        // ganze Session auf den einzigen bestätigten Nachbarn
                        // (SAVE persisted=1 speakers=1 trotz 2 erkannter Profile).
                        // Das Enroll unter der Session-ID macht die Stimme für die
                        // confirmed-Logik sichtbar (Quick-Confirm >= 4s bestätigt
                        // lange Blöcke sofort). Kein Doppel-ID-Risiko: der erste
                        // >= 2s-Kontakt einer Stimme läuft immer durch diesen
                        // Global-Match, vorher kann kein fremdes pending entstehen.
                        if (voiceBank != null) {
                            val learned = voiceBank.enroll(sessionId, samples, durationMs)
                            TestLog.log("VB_GLOBAL_LEARN session=$sessionId profil=${profileId.takeLast(8)} " +
                                    "enroll=${if (learned) "CONFIRMED" else "pending/skip"}")
                        }
                        finalMapping = finalMapping + (localId to sessionId)
                        finalNewSpeakerIds = finalNewSpeakerIds - localId
                        globalResolvedCount++
                        globalResolved = true
                        // Phase 10: Kontinuitäts-Kontext nachführen (Global-Match ist Anker)
                        lastMappedEndSec = best.endSec
                        lastMappedGlobalId = sessionId
                        lastMappedLocalId = localId
                        Log.d(TAG, "VB_GLOBAL resolve: local=$localId → profil=${profileId.takeLast(8)} " +
                                "(session=$sessionId, dur=${durationMs}ms, statt neue ID ${result.mapping[localId]})")
                        TestLog.log("VB_GLOBAL_RESOLVE local=$localId → profil=${profileId.takeLast(8)} (session=$sessionId)")
                    }
                }
                if (globalResolved) continue
                val targetGlobalId = result.mapping[localId]
                if (localId in finalNewSpeakerIds) {
                    // Wirklich neuer Sprecher → in die Bank einschreiben
                    if (targetGlobalId != null) {
                        val enrolled = voiceBank.enroll(targetGlobalId, samples, durationMs)
                        if (enrolled) {
                            enrolledCount++
                            TestLog.log("VB local=$localId dur=${durationMs}ms → ENROLL global=$targetGlobalId OK")
                        } else {
                            skippedCount++
                            TestLog.log("VB local=$localId dur=${durationMs}ms → ENROLL global=$targetGlobalId SKIP")
                        }
                    }
                } else if (targetGlobalId != null) {
                    // Phase 6: Phantom/Fehlzuordnung (nicht new, aber Bank kennt die Stimme nicht)
                    if (!voiceBank.hasVoiceprintFor(targetGlobalId)) {
                        // a) Phantom-ID: Ziel existiert nicht in der Bank → echte neue Stimme
                        val enrolled = voiceBank.enroll(targetGlobalId, samples, durationMs)
                        if (enrolled) {
                            enrolledCount++
                            TestLog.log("VB local=$localId dur=${durationMs}ms → ENROLL-Phantom global=$targetGlobalId OK (Ziel-ID war nicht in der Bank)")
                        } else {
                            skippedCount++
                            TestLog.log("VB local=$localId dur=${durationMs}ms → ENROLL-Phantom global=$targetGlobalId SKIP")
                        }
                    } else {
                        // b) Fehlzuordnung auf echte Bank-ID (Zone-Vote) → frische ID
                        // Phase 10 (Fix 2): Enroll-Schutz – eine frisch gespawnte ID
                        // wird NICHT mehr sofort bestätigt/enrolled (Quick-Confirm
                        // >4s hat bei Standup-Drift 7 Müll-Profile pro Meeting
                        // erzeugt). Stattdessen pending mit 2-Kontakt-Härtung:
                        // enroll() bestätigt erst beim ZWEITEN unabhängigen Kontakt.
                        finalMapping = finalMapping + (localId to freshGlobalId)
                        val enrolled = voiceBank.enroll(freshGlobalId, samples, durationMs,
                            allowQuickConfirm = false)
                        if (!enrolled) {
                            // Pending angelegt (kein Confirm) → zählt als skipped
                            skippedCount++
                            TestLog.log("VB local=$localId dur=${durationMs}ms → NEUE ID=${freshGlobalId} PENDING " +
                                    "(Fix2: kein Quick-Confirm, Ziel ${targetGlobalId} war in Bank, aber kein Match)")
                        } else {
                            TestLog.log("VB local=$localId dur=${durationMs}ms → NEUE ID=${freshGlobalId} CONFIRMED " +
                                    "(2. Kontakt, Ziel ${targetGlobalId} war in Bank, aber kein Match)")
                        }
                        freshGlobalId++
                    }
                }
            }
            if (driftResolvedCount > 0 || enrolledCount > 0) {
                Log.d(TAG, "VOICE_BANK: $driftResolvedCount Drift-ID(s) aufgelöst, " +
                        "$enrolledCount enrolled, $skippedCount skipped (Bank=${voiceBank.speakerCount} Sprecher)")
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
        TestLog.log("CHUNK chunk=${chunk.startSec}-${chunk.endSec}s overlap=${chunk.overlapSec}s " +
                "engineSegs=${engineSegments.size} mapped=${correctedSegments.size} " +
                "globalBestand=${globalSegments.size} retry=${retryOffsetSecApplied}s")
        // 0.5.72: Segment-Grenzen + Dauern loggen – Diagnose: Warum skip die
        // Voice-Bank? (Log-Befund 0.5.71: vb skip=2 im Chunk [0,15], obwohl der
        // Host mit derselben WAV Segmente >= 2s findet → Bank kann nicht enrollen
        // → nur 1 Sprecher wird etabliert.)
        val segsDesc = engineSegments.sortedBy { it.startSec }.take(12)
            .joinToString(" ") { s -> "[${"%.2f".format(s.startSec)}-${"%.2f".format(s.endSec)}]spk${s.speaker}(${"%.1f".format(s.endSec - s.startSec)}s)" }
        TestLog.log("ENGINE_SEGS chunk=${chunk.startSec}-${chunk.endSec}: $segsDesc")

        return WorkerChunkResult(
            chunk = chunk,
            mappedSegments = correctedSegments,
            mapping = finalMapping,
            newSpeakerIds = finalNewSpeakerIds,
            allGlobalSegments = globalSegments,
            voiceBankResolvedCount = driftResolvedCount,
            voiceBankEnrolledCount = enrolledCount,
            voiceBankSkipCount = skippedCount,
            voiceBankSize = bankSize,
            globalResolvedCount = globalResolvedCount,
            globalProfileMapSize = globalProfileBySessionId.size,
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
     * Hebel C (0.5.55–0.5.59): RMS-Normalisierung + DC-Blocker + RELATIVES Noise Gate + Gain-Limit.
     *
     * Log-Befund 0.5.58: Chunk [55,75] lieferte trotz Time-Drift-Fix weiterhin 0
     * Segmente – aber die lokale Sherpa-ONNX-Reproduktion (exakt App-Version 1.13.4)
     * findet mit der MP3-Quelle Sprache dort. Die Mikrofon-Aufnahme ist deutlich
     * leiser (RMS 0.0005) – das ABSOLUTE Gate (0.001) löschte das komplette Signal.
     *
     * Pipeline in dieser Reihenfolge:
     * 1. Mean Subtraction (DC-Blocker): zentriert auf die Nulllinie
     * 2. RELATIVES Noise Gate: Schwelle = 10% des Signal-RMS (leise Sprache
     *    überlebt, echtes Rauschen wird gekillt – Pegel skaliert mit)
     * 3. RMS berechnen, nur verstärken wenn zu leise (Ziel 0.1)
     * 4. Gain-Limit: NIE mehr als [maxBoostFactor] (10x) – verhindert
     *    Rausch-Orkane bei fast stillem Audio (Log-Beweis: 197x = pathologisch)
     *
     * WICHTIG: Nur der Engine-Pfad nutzt die normalisierten Samples. Die
     * Voice-Bank-Extraktion arbeitet mit dem Original-Audio, sonst würden die
     * Speaker-Embeddings durch den Boost verzerrt (gleicher Sprecher, andere
     * Lautstärke → verfälschte Cosine-Similarity).
     */
    private fun normalizeAudio(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples

        // 1. DC-Offset entfernen (Mean Subtraction) – zentriert auf die Nulllinie
        var sum = 0.0
        for (sample in samples) {
            sum += sample
        }
        val mean = (sum / samples.size).toFloat()

        val centeredSamples = FloatArray(samples.size)
        var sumSquaresRaw = 0.0
        for (i in samples.indices) {
            val centered = samples[i] - mean
            centeredSamples[i] = centered
            sumSquaresRaw += centered * centered
        }
        // RMS des ROH-Signals (VOR dem Gate) – Basis für die relative Gate-Schwelle
        val rmsRaw = kotlin.math.sqrt((sumSquaresRaw / samples.size).toDouble()).toFloat()
        if (rmsRaw < 0.0001f) return centeredSamples // absolute Stille

        // 2. RELATIVES Noise Gate (0.5.59): Schwelle = Anteil des Signal-RMS.
        //    Log-Befund 0.5.58: absolutes Gate (0.001) löschte leises
        //    Mikrofon-Signal (RMS 0.0005) komplett → Pyannote sah Stille →
        //    0 Segmente. Mit 10% des RMS überlebt leise Sprache, Rauschen
        //    unterhalb der relativen Schwelle wird weiterhin gekillt.
        val gateThreshold = (rmsRaw * noiseGateRatio).coerceAtLeast(noiseFloorAbs)
        var sumSquares = 0f
        var maxPeak = 0f
        for (i in centeredSamples.indices) {
            var v = centeredSamples[i]
            if (kotlin.math.abs(v) < gateThreshold) {
                v = 0f
            }
            centeredSamples[i] = v
            val absSample = kotlin.math.abs(v)
            sumSquares += v * v
            if (absSample > maxPeak) maxPeak = absSample
        }

        val rms = kotlin.math.sqrt((sumSquares / samples.size).toDouble()).toFloat()
        if (rms < 0.0001f) return centeredSamples // nach Gate nur noch Rauschen/Stille

        // 3. Nur verstärken wenn zu leise (Ziel-Pegel 0.1), Clipping verhindern
        val targetRms = 0.1f
        var gain = 1f
        if (rms < targetRms) {
            gain = targetRms / rms
            // 4. Gain-Limit: harter Riegel – niemals 197x wie in 0.5.56!
            if (gain > maxBoostFactor) {
                gain = maxBoostFactor
            }
            if (maxPeak * gain > 0.99f) {
                gain = 0.99f / maxPeak
            }
        }

        if (gain > 1.5f) {
            Log.d(TAG, "normalizeAudio: DC-Offset $mean entfernt, Gate ${String.format("%.5f", gateThreshold)} (relativ), Boost ${String.format("%.2fx", gain)} " +
                    "(Limit ${maxBoostFactor}x, RMS ${String.format("%.4f", rms)} → Ziel $targetRms)")
            TestLog.log("normalizeAudio: Gate ${String.format("%.5f", gateThreshold)} Boost ${String.format("%.2fx", gain)} RMS ${String.format("%.4f", rms)}")
            for (i in centeredSamples.indices) {
                centeredSamples[i] = centeredSamples[i] * gain
            }
        }
        return centeredSamples
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

    /** 0.5.74: Chunk-Samples als Diagnose-WAV speichern (Debug-Mode). */
    private fun saveChunkWav(chunk: AudioChunk) {
        try {
            val base = SherpaTranscriptApp.instance
                .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            val dir = File(base, "testaufnahmen/chunks")
            if (!dir.exists() && !dir.mkdirs()) return
            val f = File(dir, "chunk_${"%.1f".format(chunk.startSec)}_${"%.1f".format(chunk.endSec)}.wav")
            val out = DataOutputStream(BufferedOutputStream(FileOutputStream(f)))
            // WAV-Header (16 kHz mono 16-bit PCM, Little-Endian)
            out.writeBytes("RIFF")
            writeLeInt(out, 36 + chunk.samples.size * 2)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeLeInt(out, 16)
            writeLeShort(out, 1)
            writeLeShort(out, 1)
            writeLeInt(out, 16000)
            writeLeInt(out, 32000)
            writeLeShort(out, 2)
            writeLeShort(out, 16)
            out.writeBytes("data")
            writeLeInt(out, chunk.samples.size * 2)
            for (v in chunk.samples) {
                val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                out.writeByte(s and 0xFF)
                out.writeByte((s shr 8) and 0xFF)
            }
            out.close()
            Log.d(TAG, "Chunk-WAV gespeichert: ${f.absolutePath} (${chunk.samples.size / 16000.0f}s)")
        } catch (t: Throwable) {
            Log.w(TAG, "Chunk-WAV fehlgeschlagen: ${t.message}")
        }
    }

    private fun writeLeInt(out: DataOutputStream, v: Int) {
        out.writeByte(v and 0xFF)
        out.writeByte((v shr 8) and 0xFF)
        out.writeByte((v shr 16) and 0xFF)
        out.writeByte((v shr 24) and 0xFF)
    }

    private fun writeLeShort(out: DataOutputStream, v: Int) {
        out.writeByte(v and 0xFF)
        out.writeByte((v shr 8) and 0xFF)
    }
}
