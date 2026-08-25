package com.sherpa.transcript.ui.live

import android.Manifest
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sherpa.transcript.SherpaTranscriptApp
import com.sherpa.transcript.data.debug.DebugUploadClient
import com.sherpa.transcript.data.local.AsrLanguageMode
import com.sherpa.transcript.data.local.SegmentEntity
import com.sherpa.transcript.data.local.SettingsStore
import com.sherpa.transcript.data.local.TranscriptEntity
import com.sherpa.transcript.domain.export.TranscriptExporter
import com.sherpa.transcript.data.repository.TranscriptRepository
import com.sherpa.transcript.domain.audio.AudioCaptureManager
import com.sherpa.transcript.domain.audio.AudioImportDecoder
import com.sherpa.transcript.domain.audio.ChunkedAudioBuffer
import com.sherpa.transcript.data.local.SpeakerProfileStore
import com.sherpa.transcript.engine.GlobalVoiceBank
import com.sherpa.transcript.engine.SpeakerProfiles
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.Application
import com.sherpa.transcript.R
import com.sherpa.transcript.domain.audio.TestLog
import com.sherpa.transcript.domain.model.RecordingState
import com.sherpa.transcript.domain.model.TranscriptSegment
import com.sherpa.transcript.engine.DiarizationChunkWorker
import com.sherpa.transcript.engine.DiarizationClusteringMode
import com.sherpa.transcript.engine.DiarizationSegment
import com.sherpa.transcript.engine.FinalTranscriptComposer
import com.sherpa.transcript.engine.ModelDownloadManager
import com.sherpa.transcript.engine.RollingReconciler
import com.sherpa.transcript.engine.SherpaOnnxEngine
import com.sherpa.transcript.engine.SherpaEmbeddingComputer
import com.sherpa.transcript.engine.SessionVoiceBank
import com.sherpa.transcript.engine.SpeakerDiarizationEngine
import com.sherpa.transcript.engine.SpeakerModelDownloadManager
import com.sherpa.transcript.engine.TimelineComposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LiveUiState(
    val recordingState: RecordingState = RecordingState.Idle,
    val segments: List<TranscriptSegment> = emptyList(),
    val latestSegmentId: String? = null,
    val autoScrollEnabled: Boolean = true,
    val keepScreenOn: Boolean = false,   // Phase 8 (0.7.3): Display-Wake-Toggle
    val postProcessing: Boolean = false,        // Phase 8 (0.7.4): Nachbearbeitung nach Stop läuft
    val postProcessingElapsedSec: Int = 0,      // Ticker: Sekunden seit Stop
    /** Phase 9 (0.9.0): Import läuft – Fortschritt 0..100, -1 = inaktiv. */
    val importProgress: Int = -1,
    val importFileName: String? = null,
    val fontSize: Float = 16f,
    val isModelReady: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadMessage: String = "",
    val error: String? = null,
    val debugMode: Boolean = false,
    val remappedSegmentIds: Set<String> = emptySet(),
    val rawCount: Int = 0,
    val labeledCount: Int = 0,
    val displayCount: Int = 0,
    /** Phase 7a (0.7.2): bekannte Profile für Namens-Overlay + Kontakte-Screen. */
    val speakerProfiles: List<SpeakerProfileUi> = emptyList(),
    /** Phase 8 (0.7.5): Session-GID → Profil-UUID (stabile Farben über Sessions). */
    val sessionProfileMap: Map<Int, String> = emptyMap(),
)

/** Phase 7a: Anzeige-Pendant eines persistierten Speaker-Profils (kein Embedding nötig). */
data class SpeakerProfileUi(
    val id: String,
    val name: String?,
    val sampleCount: Int,
)

data class AssignmentQuality(
    val labeledSegments: Int,
    val unlabeledSegments: Int,
    val distinctSpeakers: Int,
    val totalLabeledDurationMs: Long = 0L,
)

/**
 * Unterschied zwischen zwei Speaker-Zuordnungen.
 * Wird verwendet, um ACCEPTED_IMPROVED vs NO_CHANGE zu unterscheiden.
 */
data class AssignmentDiff(
    val newlyLabeledSegments: Int,
    val changedSpeakerAssignments: Int,
    val lostLabels: Int,
    val distinctSpeakersBefore: Int,
    val distinctSpeakersAfter: Int,
) {
    val isMeaningfulChange: Boolean get() =
        newlyLabeledSegments > 0 || distinctSpeakersAfter > distinctSpeakersBefore || changedSpeakerAssignments > 0
}

class LiveViewModel : ViewModel() {

    companion object {
        private const val TAG = "LiveViewModel"
        private const val NOTIFICATION_CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 0x5243
        private const val NOTIFICATION_IMPORT_ID = 0x5244
        private const val ASR_MODEL = "kroko-de"
        private const val DIARIZATION_INTERVAL_MS = 10_000L
        private const val MAX_AUDIO_FRAMES = 72_000
        private const val ENABLE_DIARIZATION = true
        private const val MIN_DIARIZATION_FRAMES = 2_000
        /**
         * Sliding-Window-Länge für die Diarization (Sekunden).
         * pyannote-segmentation degradiert bei sehr langen Eingabe-Buffern massiv
         * (beobachtet: bei 60s+ liefert die Engine nur noch winzige oder 0 Segmente).
         * Nur die letzten N Sekunden werden verarbeitet, der Offset wird verschoben.
         */
        private const val DIARIZATION_WINDOW_SEC = 30f

        /**
         * Feature-Toggle für die Rolling-Reconciliation-Architektur (Dark Launch).
         *
         * false = alte Pipeline (audioAccumulator + Sliding Window + normalizeSpeakerIds)
         * true  = neue Pipeline (ChunkedAudioBuffer + DiarizationChunkWorker + RollingReconciler)
         *
         * Bei true wird der audioAccumulator NICHT mehr gefüttert (Memory-Leak-Schutz):
         * Die Weiche im Capture-Loop pusht dann ausschließlich in den ChunkedAudioBuffer.
         */
        private const val ENABLE_CHUNKED_DIARIZATION = true

        /**
         * Phase 7 (0.7.0): Persistente geräteweite Speaker-Profile.
         * true = Auto-Enroll bestätigter Kontakte beim Stop + sofortige
         * Wiedererkennung bekannter Stimmen über Sessions (GlobalVoiceBank).
         * A/B gegen false möglich (eine Variable, Log-Zähler VB_GLOBAL_*).
         */
        private const val ENABLE_GLOBAL_VOICE_BANK = true

        /**
         * Tuning-Hebel F (Diagnose, 0.5.45) – seit 0.5.53 überholt:
         * ALLOW_NEW_SPEAKER_IDS=false reaktiviert den Strip-Guard.
         *
         * false = protectFromNewIds aktiv: unbestätigte Kandidaten-IDs werden
         *         verworfen. Bestätigte Voice-Bank-Sprecher (2-Kontakt-Härtung,
         *         confirmedBankIds-Ausnahme in mergeCandidateIntoBest) kommen
         *         trotzdem durch – sie sind echte neue Sprecher.
         *         Log-Befund 0.5.55: speakers=4 bei nur 1 bestätigtem
         *         Bank-Sprecher (global=2,3 pending) → Guard wieder scharf.
         * true  = Diagnose-Modus (0.5.45): alle neuen IDs in den UI-Bestand.
         */
        private const val ALLOW_NEW_SPEAKER_IDS = false

        /**
         * 0.6.23/0.6.24: Auto-Spracherkennung DE/EN. Aktiv wenn der User in den
         * Einstellungen DE_EN_AUTO gewählt hat (Standard: DE_ONLY – Kroko
         * transkribiert Englisch selbst überraschend gut, Testlauf 0.6.22).
         * In den ersten [LANG_DETECT_MS] werden beide Engines parallel gefüttert;
         * die Sprache mit dem ersten finalen ASR-Text gewinnt, die andere Engine
         * wird gestoppt (spart CPU).
         */
        private const val LANG_DETECT_MS = 3000L
        private const val EN_MODEL = "en-zipformer"
    }

    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    private val audioCapture = AudioCaptureManager()
    private val engine = SherpaOnnxEngine(SherpaTranscriptApp.instance)
    /** 0.6.23: Zweite ASR-Engine für die Auto-Spracherkennung (EN-Fallback). */
    private val engineEn = SherpaOnnxEngine(SherpaTranscriptApp.instance)
    private val speakerEngine = SpeakerDiarizationEngine(SherpaTranscriptApp.instance, SherpaTranscriptApp.instance.assets)
    private val repository = TranscriptRepository()

    // ── Neue Rolling-Reconciliation-Pipeline (nur bei Toggle=ON aktiviert) ──
    // Lazy-Init: bei Toggle=OFF werden diese Komponenten nie erzeugt (kein RAM/CPU).
    private val chunkedAudioBuffer by lazy { ChunkedAudioBuffer() }
    private val rollingReconciler by lazy { RollingReconciler() }
    /** Hebel G: akustische Voice-Bank gegen Engine-Drift (embedding.onnx aus Assets). */
    private val sessionVoiceBank by lazy {
        // 0.5.61-Neukalibrierung (echte Mikrofon-Aufnahme + Titanet, Transkript-
        // Referenzzeiten): INTRA min 0.638 / INTER max 0.612 → Sweet Spot 0.625.
        // 0.38 (0.5.48, auf Wall-Clock-Pfad kalibriert) lag unter dem Inter-Maximum
        // → matchte verschiedene Sprecher fälschlich (Log-Befund 0.5.60: sim 0.672
        // → global=0). 0.62 = konservativ. minIdentifySec=2s: 1s-Segmente erzeugten
        // Falsch-Matches (sim 0.669) und werden seit 0.5.61 nicht mehr aufgelöst.
        // 0.5.62: pendingConfirmThreshold 0.35 (BESTÄTIGUNG locker – die App-
        // Aufnahme hat niedrigere Intra-Sims als die Rekorder-WAV; mit 0.62 blieb
        // alles pending). matchThreshold bleibt 0.62 (RESOLVE strikt).
        SessionVoiceBank(
            computer = SherpaEmbeddingComputer(SherpaTranscriptApp.instance.assets),
            matchThreshold = 0.62f,
            minEnrollmentSec = 2f,
            minIdentifySec = 2f,
            pendingConfirmThreshold = 0.35f,
            // Phase 6 (0.6.11): Quick-Confirm – langer 1. Kontakt (>= 4s) wird
            // sofort bestätigt (etabliert einmalige Kurzbeiträge in Meetings
            // mit 3+ Speakern; Host-verifiziert mit dem 4-Sprecher-Podium).
            quickConfirmSec = 4f,
        )
    }
    private val diarizationChunkWorker by lazy {
        DiarizationChunkWorker(
            buffer = chunkedAudioBuffer,
            diarizer = speakerEngine::process,
            reconciler = rollingReconciler,
            voiceBank = sessionVoiceBank,
            // Phase 7: persistente Profile als zweiter akustischer Anker
            // (wirkt nur zusammen mit der Session-Bank – beide sind immer gesetzt).
            globalBank = globalVoiceBank.takeIf { ENABLE_GLOBAL_VOICE_BANK },
            // Tuning-Hebel 1 rückgängig (0.5.51): Chunk zurück auf 15s + 5s Overlap.
            // Log-Befund 0.5.50: 30s+10s lieferte ein 30,9s-Monster-Segment (beide
            // Sprecher verschmolzen) → speakers=1, kein FIRST_2SPK. Bei 15s+5s hatte
            // Pyannote den Wechsel eindeutig gefunden – das war der bessere Stand.
            chunkSec = 15f,
        )
    }

    /**
     * Phase 7: Persistente geräteweite Speaker-Profile – ZENTRALE Instanz
     * (SpeakerProfiles-Objekt, geteilt mit dem Kontakte-Screen, damit keine
     * RAM-Stände divergieren). Wird beim ersten Zugriff geladen und nach
     * jedem Auto-Enroll/Änderung geschrieben. KEIN Reset in onCleared.
     * Privacy: speakerProfiles.json liegt in filesDir (biometrische Daten) –
     * der Debug-Upload scannt nur das testaufnahmen-Verzeichnis.
     */
    private val globalVoiceBank by lazy { SpeakerProfiles.ensureBank() }

    private var captureJob: Job? = null
    private var diarizationJob: Job? = null
    private var postProcessTicker: Job? = null
    private var postProcessStartMs = 0L
    private var currentTranscriptId: String? = null
    private var recordingStartedAt: Long = 0L

    // ── 0.6.23: Auto-Sprachdetektion DE/EN ──
    /** null = Detektion läuft, "de"/"en" = erkannt (bis dahin wird verworfen). */
    private var currentLanguage: String? = null
    private var deHasFinalText = false
    private var enHasFinalText = false

    /**
     * KUMULIERTE Sample-Zeit (ms) seit Session-Start für den ChunkedAudioBuffer.
     *
     * KRITISCHER FIX (0.5.58): Die Frames werden NICHT mit Wall-Clock-Zeit
     * (`sessionRelativeMs()`) positioniert, sondern mit der kumulierten
     * Audio-Dauer (`pushedSamples * 1000 / sampleRate`).
     *
     * Log-Beweis: Die WAV-Analyse zeigte Chunk [55,75] in der Quelle mit völlig
     * normalem Pegel (RMS 0.076), die App maß dort aber 0.0005 (152x leiser) –
     * obwohl Whisper denselben Frame-Stream transkribierte. Ursache: Der
     * Capture-Loop stockt unter Last (ASR-Inferenz + Pyannote + Voice-Bank),
     * Frames bekamen Wall-Clock-Stempel → die Zeitachse im Buffer dehnte sich →
     * Chunk [55,75] zeigte auf einen Bereich mit fast keinen Frames → Pyannote
     * sah fast Stille → 0 Segmente (Boost 197x als Rausch-Orkan).
     *
     * Mit der Sample-Zeit bleibt die Position eines Frames exakt seine
     * Audio-Position, egal wie stark der Loop verzögert wird.
     */
    private var pushedSampleCountMs = 0L

    // Ebene 1: Rohdaten – nie mergen
    // var: Hebel 2 (0.5.52) ersetzt die Liste atomar bei Split der Ground Truth –
    // sicherer gegen Race mit dem Whisper-Callback als in-place clear+addAll.
    private var rawFinalSegments = mutableListOf<TranscriptSegment>()
    // Ebene 2: mit Sprecher-Zuordnung
    private var assignedFinalSegments: List<TranscriptSegment> = emptyList()
    private var bestAssignmentQuality = AssignmentQuality(0, 0, 0)
    // Ebene 3: UI – nur abgeleitet
    private var livePartial: TranscriptSegment? = null

    private val audioAccumulator = ArrayDeque<FloatArray>()
    private val audioLock = Any()
    private var audioBaseTimeMs: Long = 0L
    private var currentUtteranceStartMs: Long? = null
    private var lastPartialText: String = ""
    private var lastForcedFlushTime: Long = 0L
    private var lastForcedFlushText: String = ""

    /**
     * Prüft vor dem Hinzufügen eines neuen Segments, ob es mit dem letzten
     * Segment in rawFinalSegments zusammengeführt werden kann (Text-Overlap + zeitliche Nähe).
     * @return true wenn gemerged (kein add nötig), false wenn normal hinzugefügt werden soll
     */
    private fun dedupeOrMergeIntoLastSegment(newText: String, startMs: Long, endMs: Long): Boolean {
        val last = rawFinalSegments.lastOrNull() ?: return false
        // Zeitliche Nähe: Pause zwischen Segmenten (nicht Start-Abstand!)
        val pauseMs = startMs - last.endTimeMs
        val startOffsetMs = startMs - last.startTimeMs
        if (startOffsetMs !in 1..59999L) return false

        val existingWords = last.text.trim().split("\\s+".toRegex())
        val newWords = newText.trim().split("\\s+".toRegex())
        val existingStr = existingWords.joinToString(" ")
        val newStr = newWords.joinToString(" ")

        // ===== Check 0: Micro-Segment (< 500ms UND < 2 Wörter) → immer mergen =====
        val newDurationMs = endMs - startMs
        if (newDurationMs < 500L && newWords.size < 2 && pauseMs < 1500L) {
            val resultWords = existingWords.size + newWords.size
            // 0.6.19: Führende Satzzeichen beim Merge bereinigen
            val mergedText = cleanLeadingPunctuation(existingStr + " " + newStr)
            rawFinalSegments[rawFinalSegments.lastIndex] = last.copy(
                text = mergedText,
                endTimeMs = maxOf(last.endTimeMs, endMs),
            )
            Log.d(TAG, "rawFinalSegments MERGE (micro): #${rawFinalSegments.size} old=${existingWords.size} new=${newWords.size} res=$resultWords \"${newText.take(30)}\"")
            return true
        }

        // ===== Check 1: Suffix-Overlap (existing endet wie new beginnt) → MERGE =====
        val maxOverlap = minOf(existingWords.size, newWords.size)
        var overlapWords = 0
        for (checkLen in maxOverlap downTo 1) {
            val suffix = existingWords.takeLast(checkLen).joinToString(" ")
            val prefix = newWords.take(checkLen).joinToString(" ")
            if (suffix.equals(prefix, ignoreCase = true)) { overlapWords = checkLen; break }
        }
        if (overlapWords >= 2) {
            // Volle Prefix-Überlappung + new ist länger → new gewinnt (ersetzt old)
            val isFullPrefix = overlapWords >= existingWords.size
            val mergedText = if (isFullPrefix && newWords.size > existingWords.size) {
                newStr
            } else if (overlapWords >= maxOverlap) {
                existingStr
            } else {
                // 0.6.19: Führende Satzzeichen beim Merge bereinigen
                cleanLeadingPunctuation(existingStr + " " + newWords.drop(overlapWords).joinToString(" "))
            }
            val resultWords = mergedText.trim().split("\\s+".toRegex()).size
            val action = when {
                isFullPrefix && newWords.size > existingWords.size -> "REPLACE (fullPrefix)"
                isFullPrefix -> "NOOP (fullOverlap)"
                else -> "MERGE"
            }
            rawFinalSegments[rawFinalSegments.lastIndex] = last.copy(
                text = mergedText.trim(), endTimeMs = maxOf(last.endTimeMs, endMs),
            )
            Log.d(TAG, "rawFinalSegments $action: #${rawFinalSegments.size} old=${existingWords.size} new=${newWords.size} res=$resultWords gap=${pauseMs}ms overlap=${overlapWords}words")
            return true
        }

        // ===== Check 2: Prefix-Match (new beginnt wie existing) → REPLACE/DROP =====
        val matchLen = minOf(existingWords.size, newWords.size)
        var commonPrefixWords = 0
        for (i in 0 until matchLen) {
            if (existingWords[i].equals(newWords[i], ignoreCase = true)) commonPrefixWords++ else break
        }
        if (commonPrefixWords >= 3) {
            if (newWords.size > existingWords.size) {
                rawFinalSegments[rawFinalSegments.lastIndex] = last.copy(
                    text = newStr, endTimeMs = maxOf(last.endTimeMs, endMs),
                )
                Log.d(TAG, "rawFinalSegments REPLACE: #${rawFinalSegments.size} old=${existingWords.size} new=${newWords.size} res=${newWords.size} prefix=${commonPrefixWords}words")
            } else {
                rawFinalSegments[rawFinalSegments.lastIndex] = last.copy(
                    endTimeMs = maxOf(last.endTimeMs, endMs),
                )
                Log.d(TAG, "rawFinalSegments DROP: #${rawFinalSegments.size} old=${existingWords.size} new=${newWords.size} res=${existingWords.size} replay prefix=${commonPrefixWords}words")
            }
            return true
        }

        // ===== Check 3: Containment (einer ist substring des anderen) → DROP =====
        val exLower = existingStr.lowercase()
        val newLower = newStr.lowercase()
        if (exLower.contains(newLower) || newLower.contains(exLower)) {
            val longer = if (existingWords.size >= newWords.size) existingStr else newStr
            val longerWords = longer.split("\\s+".toRegex()).size
            rawFinalSegments[rawFinalSegments.lastIndex] = last.copy(
                text = longer, endTimeMs = maxOf(last.endTimeMs, endMs),
            )
            Log.d(TAG, "rawFinalSegments DROP: #${rawFinalSegments.size} old=${existingWords.size} new=${newWords.size} res=$longerWords containment")
            return true
        }

        return false
    }

    /** Aktueller Clustering-Testmodus, wird an SpeakerDiarizationEngine durchgereicht. */
    var clusteringMode: DiarizationClusteringMode = DiarizationClusteringMode.AUTO_HIGHER_THRESHOLD

    // ── Diarization-Serialisierung ──
    private val diarizationMutex = Mutex()
    private var diarizationEpoch = 0L
    private var isStopping = false
    private var isSavingFinalResult = false

    /** Letzte Diarization-Segmente – für Segment-Splitting im Save-Pfad */
    private var lastDiarizationSegments: List<DiarizationSegment> = emptyList()

    /**
     * Bester gemergter Kandidat eines verworfenen (DROP_STALE) Laufs.
     * Wird beim Stoppen als Fallback übernommen, wenn der Final-Run
     * 0 Segmente liefert (verhindert Verlust eines guten 2-Speaker-Stands).
     */
    private var lastGoodDiarizationCandidate: List<TranscriptSegment>? = null

    init {
        RecordingBridge.current = this   // Phase 8: Notification-Aktionen erreichen die aktive Instanz
        checkModels()
        // Phase 5 (0.6.8): Persistente Einstellungen – Schriftgröße + Debug-Mode
        // aus dem SettingsStore laden und live auf Änderungen reagieren
        // (z.B. wenn der User im Einstellungen-Screen die Schriftgröße ändert).
        val settings = SettingsStore.current
        _uiState.update {
            it.copy(fontSize = settings.fontSize.value, debugMode = settings.debugMode.value)
        }
        viewModelScope.launch {
            settings.fontSize.collect { size ->
                _uiState.update { it.copy(fontSize = size) }
            }
        }
        viewModelScope.launch {
            settings.debugMode.collect { enabled ->
                _uiState.update { it.copy(debugMode = enabled) }
                audioCapture.saveRawWav = enabled
            }
        }
    }


    /** 0.6.24: Auto-Detection nur wenn der User DE_EN_AUTO in den Einstellungen aktiviert hat. */
    private fun useAutoLanguageDetection(): Boolean =
        SettingsStore.current.asrLanguageMode.value == AsrLanguageMode.DE_EN_AUTO

    private fun checkModels() {
        val ctx = SherpaTranscriptApp.instance
        if (ModelDownloadManager.isModelDownloaded(ctx, ASR_MODEL)) { engine.initialize(ASR_MODEL); _uiState.update { it.copy(isModelReady = true) } }
        if (useAutoLanguageDetection() && ModelDownloadManager.isModelDownloaded(ctx, EN_MODEL)) { engineEn.initialize(EN_MODEL) }
        if (SpeakerModelDownloadManager.areModelsDownloaded()) { speakerEngine.initialize(clusteringMode) }
    }

    /**
     * Loggt Permission- und AppOps-Zustand für Mikrofonzugriff.
     * Hilft bei MIUI-Problemen wie "App op 27 missing, silencing record".
     */
    private fun logRecordPermissionState(context: android.content.Context = SherpaTranscriptApp.instance) {
        val permission = if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
        val appOpMode = try {
            val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> "MODE_ALLOWED"
                AppOpsManager.MODE_FOREGROUND -> "MODE_FOREGROUND"
                AppOpsManager.MODE_IGNORED -> "MODE_IGNORED"
                else -> "MODE_$mode"
            }
        } catch (t: Throwable) { "ERR:${t.message}" }
        val foreground = try {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.any { it.pid == Process.myPid() && it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND } == true
        } catch (t: Throwable) { false }
        Log.i(TAG, "recordPermissionState: permission=$permission appOp=$appOpMode foreground=$foreground")
    }

    fun startRecording() {
        val currentState = _uiState.value.recordingState
        if (currentState is RecordingState.Listening || currentState is RecordingState.Processing || currentState is RecordingState.Initializing) return
        logRecordPermissionState()
        _uiState.update { it.copy(recordingState = RecordingState.Initializing, error = null) }
        // Foreground-Service starten: hält Mikrofon-AppOp aktiv (MIUI-"silencing" Schutz)
        com.sherpa.transcript.service.RecordingService.start(SherpaTranscriptApp.instance)

        viewModelScope.launch {
            if (!engine.isInitialized) {
                downloadWithProgress("ASR", "Lade Spracherkennungsmodell…") {
                    val ctx = SherpaTranscriptApp.instance
                    if (!ModelDownloadManager.isModelDownloaded(ctx, ASR_MODEL)) ModelDownloadManager.downloadModel(ctx, ASR_MODEL) { done, total -> _uiState.update { it.copy(downloadProgress = if (total > 0) done.toFloat() / total else 0f) } }
                    else true
                }
                if (!ModelDownloadManager.isModelDownloaded(SherpaTranscriptApp.instance, ASR_MODEL)) { _uiState.update { it.copy(recordingState = RecordingState.Error("ASR-Download fehlgeschlagen")) }; return@launch }
                engine.initialize(ASR_MODEL); _uiState.update { it.copy(isModelReady = true) }
            }
            // 0.6.23: EN-Fallback-Modell für Auto-Detection (nur laden, wenn Toggle an)
            if (useAutoLanguageDetection() && !engineEn.isInitialized) {
                downloadWithProgress("EN", "Lade Englisch-Modell…") {
                    val ctx = SherpaTranscriptApp.instance
                    if (!ModelDownloadManager.isModelDownloaded(ctx, EN_MODEL)) ModelDownloadManager.downloadModel(ctx, EN_MODEL) { done, total -> _uiState.update { it.copy(downloadProgress = if (total > 0) done.toFloat() / total else 0f) } }
                    else true
                }
                if (ModelDownloadManager.isModelDownloaded(SherpaTranscriptApp.instance, EN_MODEL)) {
                    engineEn.initialize(EN_MODEL)
                } else {
                    Log.w(TAG, "EN-Modell nicht verfügbar – Auto-Detection entfällt (nur Deutsch)")
                }
            }
            if (!speakerEngine.isInitialized) {
                downloadWithProgress("Speaker", "Lade Sprechererkennung…") {
                    if (!SpeakerModelDownloadManager.areModelsDownloaded()) SpeakerModelDownloadManager.downloadModels { _, done, total -> _uiState.update { it.copy(downloadProgress = if (total > 0) done.toFloat() / total else 0f) } }
                    else true
                }
                if (!SpeakerModelDownloadManager.areModelsDownloaded()) { _uiState.update { it.copy(recordingState = RecordingState.Error("Speaker-Download fehlgeschlagen")) }; return@launch }
                speakerEngine.initialize(clusteringMode)
            }

            _uiState.update { it.copy(segments = emptyList(), latestSegmentId = null, recordingState = RecordingState.Listening) }
            currentTranscriptId = java.util.UUID.randomUUID().toString()
            recordingStartedAt = System.currentTimeMillis()
            // 0.6.23: Sprachdetektion pro Session neu
            currentLanguage = null
            deHasFinalText = false
            enHasFinalText = false
            rawFinalSegments.clear()
            assignedFinalSegments = emptyList()
            bestAssignmentQuality = AssignmentQuality(0, 0, 0)
            livePartial = null
            currentUtteranceStartMs = null; lastPartialText = ""; lastForcedFlushTime = 0L; lastForcedFlushText = ""
            // Flags zurücksetzen (sonst läuft nächste Session sofort in DROP_STALE)
            isStopping = false; isSavingFinalResult = false
            lastDiarizationSegments = emptyList()
            lastGoodDiarizationCandidate = null
            lastShownSpeakerIds.clear()
            firstTwoSpeakerLogged = false
            speakerEngine.resetZeroSegmentCounters()
            synchronized(audioLock) { audioAccumulator.clear(); audioBaseTimeMs = 0L }
            if (ENABLE_CHUNKED_DIARIZATION) {
                // Neue Pipeline zurücksetzen (Buffer-Fortschritt + globaler Speaker-Bestand + Voice-Bank)
                chunkedAudioBuffer.clear()
                diarizationChunkWorker.reset()
                sessionVoiceBank.reset()
                pushedSampleCountMs = 0L
            }
            engine.startSession()
            // 0.6.23: EN-Engine für die Auto-Detection ebenfalls starten
            if (useAutoLanguageDetection() && engineEn.isInitialized) engineEn.startSession()

            // 0.5.68: Debug-Mode → Roh-Aufnahme als WAV speichern (Host-Analyse)
            audioCapture.saveRawWav = _uiState.value.debugMode

            captureJob = viewModelScope.launch {
                audioCapture.startCapture().collect { frame ->
                    // 0.6.23: Auto-Sprachdetektion – beide Engines parallel füttern,
                    // bis die Sprache erkannt ist (oder Timeout). Die verlierende
                    // Engine wird gestoppt, die Gewinner-Engine weiter genutzt.
                    if (useAutoLanguageDetection() && currentLanguage == null && engineEn.isInitialized) {
                        val deRes = engine.processFrame(frame)
                        val enRes = engineEn.processFrame(frame)
                        if (deRes?.isFinal == true && deRes.text.isNotBlank()) deHasFinalText = true
                        if (enRes?.isFinal == true && enRes.text.isNotBlank()) enHasFinalText = true
                        if (sessionRelativeMs() >= LANG_DETECT_MS) {
                            // Default Deutsch, wenn keiner finalen Text lieferte (z.B. Stille).
                            // Bei beiden Sprachen mit Text gewinnt DE (primäres Modell).
                            currentLanguage = when {
                                enHasFinalText && !deHasFinalText -> "en"
                                else -> "de"
                            }
                            if (currentLanguage == "de") engineEn.stopSession() else engine.stopSession()
                            Log.i(TAG, "Sprache erkannt: $currentLanguage (de=$deHasFinalText en=$enHasFinalText)")
                            TestLog.log("LANG_DETECT: $currentLanguage (de=$deHasFinalText en=$enHasFinalText)")
                        }
                    } else {
                        val result = if (currentLanguage == "en") engineEn.processFrame(frame) else engine.processFrame(frame)
                        if (result != null && result.text.isNotBlank()) handleResult(result.text, result.isFinal)
                    }
                    if (ENABLE_CHUNKED_DIARIZATION) {
                        // Gleis 2 (neu): Frame in den Chunk-Buffer – non-blocking (~20ns Lock).
                        // KRITISCH: Sample-basierte Zeit statt Wall-Clock (0.5.58) – siehe
                        // pushedSampleCountMs-Doku. Sonst dehnt sich die Buffer-Zeitachse
                        // unter Last und Chunks zeigen auf fast leere Bereiche (0 Segmente).
                        chunkedAudioBuffer.push(frame, pushedSampleCountMs)
                        pushedSampleCountMs += frame.size * 1000L / 16000
                    } else {
                        // Gleis 2 (alt): bisheriger Accumulator-Pfad
                        synchronized(audioLock) {
                            if (audioAccumulator.isEmpty()) audioBaseTimeMs = sessionRelativeMs()
                            audioAccumulator.addLast(frame)
                            if (audioAccumulator.size > MAX_AUDIO_FRAMES) { audioAccumulator.removeFirst(); audioBaseTimeMs += 10L }
                        }
                    }
                }
            }

            if (speakerEngine.isInitialized && ENABLE_DIARIZATION) {
                diarizationJob = viewModelScope.launch {
                    while (isActive) {
                        delay(DIARIZATION_INTERVAL_MS)
                        try {
                            if (ENABLE_CHUNKED_DIARIZATION) runChunkedDiarization() else runDiarization()
                            deriveUiSegments()
                        } catch (e: CancellationException) { Log.d(TAG, "Diarization cancelled"); throw e }
                          catch (t: Throwable) { Log.e(TAG, "Diarization error: ${t.message}", t); break }
                    }
                }
            }

            // Phase 8 (0.7.4): Aufnahme-Benachrichtigung mit Aktionen (Stop / scr)
            postRecordingNotification()
        }
    }

    // ── Phase 8 (0.7.4): Aufnahme-Benachrichtigung ──
    private fun postRecordingNotification() {
        try {
            val app = SherpaTranscriptApp.instance
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "Aufnahme", NotificationManager.IMPORTANCE_LOW)
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val stopPi = PendingIntent.getBroadcast(
                app, 1,
                Intent(RecordingActionReceiver.ACTION_STOP).setPackage(app.packageName), flags,
            )
            val keepPi = PendingIntent.getBroadcast(
                app, 2,
                Intent(RecordingActionReceiver.ACTION_KEEP_SCREEN), flags,
            )
            val notif = NotificationCompat.Builder(app, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Transkription läuft")
                .setContentText("Aufnahme aktiv – Tap auf Aktion, um zu stoppen oder Display wach zu halten")
                .setOngoing(true)
                .addAction(0, "Stop", stopPi)
                .addAction(0, "scr", keepPi)
                .build()
            nm.notify(NOTIFICATION_ID, notif)
        } catch (t: Throwable) {
            Log.w(TAG, "recording notification fehlgeschlagen: ${t.message}")
        }
    }

    private fun cancelRecordingNotification() {
        try {
            val nm = SherpaTranscriptApp.instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    // ── Phase 9b (0.9.2): Import-Fortschritt als System-Notification ──
    // Sichtbar auch außerhalb der App (der In-App-Banner reicht nicht, wenn der
    // Nutzer z. B. direkt in den Verlauf wechselt oder den Screen sperrt).
    private fun postImportNotification(fileName: String, progressPct: Int) {
        try {
            val app = SherpaTranscriptApp.instance
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "Aufnahme", NotificationManager.IMPORTANCE_LOW)
            )
            val builder = NotificationCompat.Builder(app, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Transkribiere '$fileName' …")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (progressPct in 0..99) {
                builder.setContentText("$progressPct %")
                builder.setProgress(100, progressPct, false)
            } else {
                builder.setContentText("Verarbeite Audio…")
                builder.setProgress(0, 0, true)   // indeterminate
            }
            nm.notify(NOTIFICATION_IMPORT_ID, builder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "import notification fehlgeschlagen: ${t.message}")
        }
    }

    /** Phase 9b: Abschluss-Notification (nicht ongoing, bleibt bis Wegwischen). */
    private fun postImportDoneNotification(fileName: String) {
        try {
            val app = SherpaTranscriptApp.instance
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "Aufnahme", NotificationManager.IMPORTANCE_LOW)
            )
            val notif = NotificationCompat.Builder(app, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Transkript fertig")
                .setContentText("'$fileName' liegt jetzt im Verlauf")
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_IMPORT_ID, notif)
        } catch (t: Throwable) {
            Log.w(TAG, "import-done notification fehlgeschlagen: ${t.message}")
        }
    }

    private fun cancelImportNotification() {
        try {
            val nm = SherpaTranscriptApp.instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_IMPORT_ID)
        } catch (_: Throwable) {
        }
    }

    private suspend fun downloadWithProgress(type: String, msg: String, block: suspend () -> Boolean) {
        _uiState.update { it.copy(isDownloading = true, downloadMessage = msg, downloadProgress = 0f) }
        val ok = block()
        _uiState.update { it.copy(isDownloading = false, downloadProgress = 0f) }
        if (!ok) _uiState.update { it.copy(recordingState = RecordingState.Error("$type-Download fehlgeschlagen")) }
    }

    private var lastUiLogSignature: String? = null

    /** Letzter angezeigter speakerId pro segmentId – für Remap-Erkennung in der UI */
    private val lastShownSpeakerIds = mutableMapOf<String, String>()

    /** Session-Flag: wurde der erste 2-Speaker-Zustand bereits erreicht? (für FIRST_2SPK-Marker) */
    private var firstTwoSpeakerLogged = false

    fun toggleDebugMode() {
        // Phase 5 (0.6.8): über den Store persistieren – der Flow aktualisiert den State
        SettingsStore.current.setDebugMode(!_uiState.value.debugMode)
    }

    private fun deriveUiSegments() {
        // UI-Basis: rawFinalSegments + Speaker-Labels aus assignedFinalSegments
        val assignedById = assignedFinalSegments.associateBy { it.segmentId }
        val uiFinals = rawFinalSegments.map { raw ->
            val assigned = assignedById[raw.segmentId]
            if (assigned != null && assigned.speakerId != null) {
                // Phase 7a (0.7.2): Anzeige-Label über Profil-Namen auflösen (display-only!)
                raw.copy(
                    speakerId = assigned.speakerId,
                    speakerLabel = resolveDisplayLabel(assigned) ?: assigned.speakerLabel,
                )
            } else {
                raw
            }
        }

        // Remap-Erkennung: segmentId, deren speakerId sich seit dem letzten UI-Update geändert hat
        val remapped = mutableSetOf<String>()
        for (seg in uiFinals) {
            val sid = seg.speakerId?.takeIf { it.isNotBlank() } ?: continue
            val prev = lastShownSpeakerIds[seg.segmentId]
            if (prev != null && prev != sid) {
                remapped.add(seg.segmentId)
                if (_uiState.value.debugMode) {
                    Log.d(TAG, "LIVE_DBG_REMAP id=${seg.segmentId.take(8)} from=$prev to=$sid start=${seg.startTimeMs} end=${seg.endTimeMs}")
                }
            }
            lastShownSpeakerIds[seg.segmentId] = sid
        }

        val mergedFinals = TimelineComposer.mergeSegmentsForDisplay(uiFinals)
        val displaySegments = if (livePartial != null) {
            (mergedFinals + livePartial!!).sortedBy { it.startTimeMs }
        } else {
            mergedFinals
        }

        val latestId = displaySegments.lastOrNull()?.segmentId
        val current = _uiState.value
        if (current.segments == displaySegments && current.latestSegmentId == latestId &&
            current.remappedSegmentIds == remapped
        ) return

        _uiState.update {
            it.copy(
                segments = displaySegments,
                latestSegmentId = latestId,
                remappedSegmentIds = remapped,
                rawCount = rawFinalSegments.size,
                labeledCount = assignedFinalSegments.count { !it.speakerId.isNullOrBlank() },
                displayCount = displaySegments.size,
            )
        }
        if (_uiState.value.debugMode) {
            Log.d(TAG, "LIVE_DBG_UI raw=${rawFinalSegments.size} labeled=${assignedFinalSegments.count { !it.speakerId.isNullOrBlank() }} display=${displaySegments.size} partial=${livePartial != null} latest=${latestId?.take(8) ?: "-"}")
        }
        logUiState(rawFinalSegments.size, assignedFinalSegments.size, displaySegments.size, livePartial != null)
        if (remapped.isNotEmpty()) {
            Log.d(TAG, "deriveUiSegments: speaker remapped for ${remapped.size} segment(s)")
        }
    }

    private fun logUiState(raw: Int, assigned: Int, display: Int, hasPartial: Boolean) {
        val sig = "$raw|$assigned|$display|$hasPartial"
        if (sig == lastUiLogSignature) return
        lastUiLogSignature = sig
        Log.d(TAG, "deriveUiSegments: raw=$raw assigned=$assigned display=$display partial=$hasPartial")
    }

    /**
     * Führt einen neuen Diarization-Kandidaten inkrementell mit dem bisherigen
     * Bestand zusammen. Basis ist immer rawFinalSegments.
     *
     * - Wenn candidate für ein Segment ein Label hat → übernehmen
     * - Wenn candidate kein Label hat, aber bestAssigned eines hat → behalten
     * - Sonst → unlabeled
     * - Matching per segmentId, Ergebnis = exakt rawFinalSegments.size Segmente
     */
    private fun mergeCandidateIntoBest(candidate: List<TranscriptSegment>): List<TranscriptSegment> {
        val bestById = assignedFinalSegments.associateBy { it.segmentId }
        val candById = candidate.associateBy { it.segmentId }

        // Bestehende Speaker-IDs
        val bestSpeakerIds = assignedFinalSegments
            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
            .distinct().toSet()
        val bestSpeakerCount = bestSpeakerIds.size
        val candSpeakerCount = candidate
            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
            .distinct().size
        val protectExistingLabels = bestSpeakerCount >= 2 && candSpeakerCount < bestSpeakerCount

        // Hebel F (0.5.45 Diagnose) ist mit der Enrollment-Härtung (0.5.53+)
        // überholt: ALLOW_NEW_SPEAKER_IDS=false reaktiviert den Strip-Guard.
        // ABER: bestätigte Voice-Bank-Sprecher (2-Kontakt-Härtung) sind echte
        // neue Sprecher und werden NIE gestrippt – nur unbestätigte Fehlcluster.
        // Log-Befund 0.5.55: speakers=4 am Ende, obwohl die Bank nur 1 bestätigt
        // hat (global=2,3 pending) – die unbestätigten IDs kamen durch den Guard.
        val confirmedBankIds = if (ENABLE_CHUNKED_DIARIZATION) {
            sessionVoiceBank.enrolledSpeakerIds.map { "speaker_$it" }.toSet()
        } else emptySet()
        val candHasNewIds = candidate
            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
            .distinct().any { it !in bestSpeakerIds && it !in confirmedBankIds }
        val shouldStripNewIds = !ALLOW_NEW_SPEAKER_IDS && (protectExistingLabels || (bestSpeakerCount >= 2 && candHasNewIds))

        // Zweite Schutzschicht: neue IDs aus Kandidaten entfernen
        var strippedCount = 0
        val result = rawFinalSegments.map { raw ->
            val cand = candById[raw.segmentId]
            val best = bestById[raw.segmentId]
            val candLabel = cand?.takeIf { !it.speakerId.isNullOrBlank() }
            val bestLabel = best?.takeIf { !it.speakerId.isNullOrBlank() }

            // Schutz: cand hat Label mit neuer ID (nicht in best) → verwerfen
            val effectiveCandLabel = if (shouldStripNewIds && candLabel != null &&
                candLabel.speakerId !in bestSpeakerIds
            ) {
                strippedCount++
                null
            } else candLabel

            when {
                effectiveCandLabel != null && bestLabel == null ->
                    raw.copy(speakerId = effectiveCandLabel.speakerId, speakerLabel = effectiveCandLabel.speakerLabel)
                effectiveCandLabel != null && bestLabel != null ->
                    if (shouldStripNewIds) {
                        raw.copy(speakerId = bestLabel.speakerId, speakerLabel = bestLabel.speakerLabel)
                    } else {
                        raw.copy(speakerId = effectiveCandLabel.speakerId, speakerLabel = effectiveCandLabel.speakerLabel)
                    }
                bestLabel != null ->
                    raw.copy(speakerId = bestLabel.speakerId, speakerLabel = bestLabel.speakerLabel)
                else -> raw
            }
        }

        if (strippedCount > 0) {
            Log.d(TAG, "mergeCandidateIntoBest: stripped $strippedCount new-label segments (protectFromNewIds)")
        }
        return result
    }

    /**
     * Nummeriert Speaker-IDs in assignedFinalSegments nach erstem Auftreten neu.
     * Stellt sicher, dass die IDs immer bei 0 beginnen (keine Lücken wie [1,2]).
     * Wird nach jedem ACCEPTED_IMPROVED aufgerufen.
     */
    private fun renumberLiveSpeakerIds() {
        val order = linkedMapOf<String, String>() // old speakerId → new speakerLabel
        for (seg in assignedFinalSegments) {
            val sid = seg.speakerId?.takeIf { it.isNotBlank() } ?: continue
            if (sid !in order) {
                val num = order.size
                order[sid] = "Sprecher ${num + 1}"
            }
        }
        if (order.isEmpty() || order.size == 1) return

        // Prüfen, ob eine Lücke besteht (z. B. speaker_1, speaker_2 statt speaker_0, speaker_1)
        val currentMin = order.keys.mapNotNull {
            it.removePrefix("speaker_").toIntOrNull()
        }.minOrNull() ?: return
        // Nur umnummerieren, wenn die IDs nicht sauber bei 0 beginnen
        if (currentMin == 0) {
            val expectedIds = (0 until order.size).map { "speaker_$it" }.toSet()
            val actualIds = order.keys.toSet()
            if (actualIds == expectedIds) return // bereits sauber
        }

        val oldToNew = linkedMapOf<String, String>()
        for ((i, entry) in order.entries.withIndex()) {
            oldToNew[entry.key] = "speaker_$i"
        }

        assignedFinalSegments = assignedFinalSegments.map { seg ->
            val oldId = seg.speakerId ?: return@map seg
            val newId = oldToNew[oldId] ?: return@map seg
            val newLabel = order[oldId] ?: return@map seg
            seg.copy(speakerId = newId, speakerLabel = newLabel)
        }
        Log.d(TAG, "renumberLiveSpeakerIds: ${oldToNew.size} speakers remapped (old=${oldToNew.keys.map { it.removePrefix("speaker_") }.sorted().joinToString(",")} → new=0..${oldToNew.size - 1})")
    }

    /**
     * Heuristik (0.5.63, Ziel-Fix 0.5.64): Führende unbestätigte Speaker-Labels
     * dem ersten bestätigten Sprecher zuordnen.
     *
     * Host-Befund (Testclip Di._07.52, lokale Reproduktion exakt App-Version):
     * Der 1. Chunk mit FIXED_2 erzeugt für einen einzelnen Sprecher zwei Cluster
     * (bekannter "Monologue split" – numClusters=2 erzwingt 2 Cluster). Das
     * 0-10s-Prä-Fragment (z.B. "Nicht mehr merken, aber") wird von der Voice-Bank
     * nie bestätigt (Titanet-Sim 0.05 zu A), bleibt aber im globalen Bestand und
     * erscheint als zusätzlicher Sprecher (3 statt 2).
     *
     * 0.5.64-Fix: Ziel-ID und Ziel-Label kommen aus dem SEGMENT mit der frühesten
     * Startzeit, dessen ID in der Bank bestätigt ist – nicht aus der Bank-ID
     * konstruiert ("Sprecher ${id+1}" stimmt nur nach renumber, und die Bank-ID
     * ist nicht zwingend die Bestands-ID). Geräte-Log 0.5.63 zeigte dadurch
     * "auf Sprecher 2 gemappt" für den ersten bestätigten Sprecher.
     *
     * 0.5.65-Fix: Auch UNLABELED Segmente (kein speakerId, z.B. kein Overlap
     * mit Diarization-Segmenten) werden gemappt, wenn sie komplett vor dem
     * ersten bestätigten Sprecher enden. Geräte-Log 0.5.64: "Nicht mehr merken,
     * aber" (3,7-4,9s) und "die Erfahrung deswegen ist aus" (6,2-7,6s) blieben
     * unlabeled, weil der fullBestand dort keine Segmente hat – die Referenz
     * ordnet sie Sprecher 1 zu.
     *
     * Konservativ: NUR Segmente, die KOMPLETT VOR dem ersten bestätigten Sprecher
     * enden, werden auf dessen ID gemappt. Spätere unbestätigte Fragmente bleiben
     * unangetastet (könnten echte neue Sprecher sein). Läuft nur im Final-Lauf
     * (forceFinal) – Rolling-Zustände bleiben unverändert.
     */
    private fun resolveLeadingUnconfirmedSpeakerLabels() {
        // Phase 10 (0.9.9): Anzeige-IDs ↔ Bank-IDs Brücke (siehe resolveListByNearestConfirmed)
        val bankConfirmedRaw = sessionVoiceBank.enrolledSpeakerIds
        val mapping = diarizationChunkWorker.originalGidToDisplayId()
        val confirmedIds = buildSet {
            addAll(bankConfirmedRaw)
            for ((displayNum, origGid) in mapping) {
                if (origGid in bankConfirmedRaw) add(displayNum)
            }
        }
        if (confirmedIds.isEmpty()) return
        // Erstes bestätigtes Segment im BESTAND: ID + Label daraus übernehmen
        val firstConfirmed = assignedFinalSegments
            .filter { seg -> seg.speakerId?.removePrefix("speaker_")?.toIntOrNull() in confirmedIds }
            .minByOrNull { it.startTimeMs } ?: return
        val targetId = firstConfirmed.speakerId ?: return
        val targetLabel = firstConfirmed.speakerLabel ?: targetId
        val firstConfirmedStartMs = firstConfirmed.startTimeMs
        var resolved = 0
        var resolvedUnlabeled = 0
        assignedFinalSegments = assignedFinalSegments.map { seg ->
            val spkNum = seg.speakerId?.removePrefix("speaker_")?.toIntOrNull()
            val isUnconfirmed = spkNum == null || spkNum !in confirmedIds
            if (isUnconfirmed && seg.endTimeMs <= firstConfirmedStartMs) {
                resolved++
                if (spkNum == null) resolvedUnlabeled++
                seg.copy(speakerId = targetId, speakerLabel = targetLabel)
            } else seg
        }
        if (resolved > 0) {
            bestAssignmentQuality = computeQuality(assignedFinalSegments)
            val msg = "resolveLeadingUnconfirmedSpeakerLabels: $resolved Segment(e) auf $targetLabel gemappt " +
                    "(davon $resolvedUnlabeled unlabeled, vor erstem bestätigten Start ${firstConfirmedStartMs}ms, Bank=${confirmedIds.sorted()})"
            Log.i(TAG, msg)
            TestLog.log(msg)
        }
    }

    /**
     * 0.6.1/0.6.4: Verbleibende Segmente ohne bestätigten Sprecher über den
     * zeitlich nächsten bestätigten Nachbarn labeln.
     *
     * Geräte-Befund 0.6.0 (Release-Test 11:08, 86,9s): ein 0,5s-Fragment im
     * Rest-Chunk [70,86.9] (~76,2s) blieb unlabeled – zu kurz für die Voice-Bank
     * (min 2s → skip), kein Aggregations-Nachbar, und die 0.5.63-Heuristik
     * (resolveLeadingUnconfirmedSpeakerLabels) greift nur für Segmente VOR dem
     * ersten bestätigten Sprecher. Ergebnis: labeled=13/14 statt 14/14.
     *
     * Geräte-Befund 0.6.3 (Export-Test 12:06): 0,47s- und 0,64s-Fragmente wurden
     * vom Reconciler als EIGENE globale IDs etabliert (new, kein Zone-Vote), die
     * Bank skipped sie (< 2s) → sie bleiben unbestätigt im Bestand und erscheinen
     * nach renumber als zusätzliche Sprecher im Export ("Sprecher 3 · 00:01:51:
     * sitze arbeitslos zu" = akustisch die Fortsetzung von Sprecher 2). Diese
     * Segmente haben eine speakerId (kein null) – der 0.6.1-Resolve ließ sie
     * unangetastet. 0.6.4 behandelt deshalb auch Segmente mit UNBESTÄTIGTEN IDs.
     *
     * Konservativ (Nutzer-Prinzip "besser unlabeled als falsch"): nur Segmente
     * ohne bestätigte ID (unlabeled ODER unbestätigte Fragment-ID) werden
     * behandelt, und nur wenn der nächste BESTÄTIGTE Nachbar EINDEUTIG ist –
     * beide Nachbarn (vor+nach) derselbe bestätigte Sprecher ODER nur ein
     * Nachbar existiert. Zwischen zwei VERSCHIEDENEN bestätigten Speakern
     * (Wechselgrenze) bleibt das Segment unangetastet.
     * Läuft nur im Final-Lauf (forceFinal), nach dem Leading-Resolve.
     */
    private fun resolveRemainingUnconfirmedByNearestConfirmed() {
        val resolved = resolveListByNearestConfirmed(assignedFinalSegments)
        if (resolved != assignedFinalSegments) {
            assignedFinalSegments = resolved
            bestAssignmentQuality = computeQuality(assignedFinalSegments)
            val msg = "resolveRemainingUnconfirmedByNearestConfirmed: unlabeled/unbestätigte Segmente über bestätigte " +
                    "Nachbarn gelabelt (jetzt ${assignedFinalSegments.count { !it.speakerId.isNullOrBlank() }}/${assignedFinalSegments.size} gelabelt)"
            Log.i(TAG, msg)
            TestLog.log(msg)
        }
    }

    /**
     * 0.6.7: Pure Variante des Nachbar-Resolves – arbeitet auf einer beliebigen
     * Segment-Liste. Wird im Final auf assignedFinalSegments UND in der Save-Phase
     * auf dem Overlay angewendet (das Overlay enthält auch raw-ASR-Segmente ohne
     * Diarization-Overlay, z.B. den Ausklang nach dem letzten Diarization-Segment –
     * Geräte-Befund 0.6.6: "## Unbekannt · 00:01:34").
     */
    private fun resolveListByNearestConfirmed(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        // Phase 10 (0.9.9): confirmedIds sind BANK-Nummern. Nach renumberLiveSpeakerIds()
        // tragen die Overlay-Segmente aber ANZEIGE-IDs (Bank-8 → Anzeige-1). Ohne die
        // Mapping-Brücke galt jedes bestätigte Segment als "unbestätigt" und kollabierte
        // auf den Nachbarn (Geräte-Befund 0.9.8: 2 Sprecher live → speakers=1 im Save).
        val bankConfirmedRaw = sessionVoiceBank.enrolledSpeakerIds
        val mapping = diarizationChunkWorker.originalGidToDisplayId()
        val confirmedIds = buildSet {
            addAll(bankConfirmedRaw)
            // Anzeige-IDs, deren ORIGINALE Session-GID in der Bank bestätigt ist:
            for ((displayNum, origGid) in mapping) {
                if (origGid in bankConfirmedRaw) add(displayNum)
            }
        }
        if (confirmedIds.isEmpty()) return segments
        val confirmed = segments.filter { seg ->
            seg.speakerId?.removePrefix("speaker_")?.toIntOrNull() in confirmedIds
        }
        if (confirmed.isEmpty()) return segments
        var changed = false
        val result = segments.map { seg ->
            val spkNum = seg.speakerId?.removePrefix("speaker_")?.toIntOrNull()
            if (spkNum != null && spkNum in confirmedIds) return@map seg // bestätigt → unangetastet
            val prev = confirmed.lastOrNull { it.endTimeMs <= seg.startTimeMs }
            val next = confirmed.firstOrNull { it.startTimeMs >= seg.endTimeMs }
            val candidate = when {
                prev != null && next != null && prev.speakerId == next.speakerId ->
                    prev.speakerId to (prev.speakerLabel ?: prev.speakerId)
                prev != null && next == null ->
                    prev.speakerId to (prev.speakerLabel ?: prev.speakerId)
                prev == null && next != null ->
                    next.speakerId to (next.speakerLabel ?: next.speakerId)
                else -> null // zwei verschiedene bestätigte Nachbarn → unangetastet
            }
            if (candidate != null) {
                changed = true
                seg.copy(speakerId = candidate.first, speakerLabel = candidate.second)
            } else seg
        }
        return if (changed) result else segments
    }

    /**
     * Baut aus allen rawFinalSegments die aktuell beste Speaker-Overlay-Liste auf.
     * Verlustfrei: jedes Rohsegment ist enthalten, ggf. mit Label aus assignedFinalSegments.
     */
    private fun buildAssignedOverlayForAllRawSegments(): List<TranscriptSegment> {
        val bestById = assignedFinalSegments.associateBy { it.segmentId }
        return rawFinalSegments.map { raw ->
            val best = bestById[raw.segmentId]
            if (best != null && !best.speakerId.isNullOrBlank()) {
                raw.copy(speakerId = best.speakerId, speakerLabel = best.speakerLabel)
            } else {
                raw
            }
        }
    }

    /**
     * 0.6.14: Backchannel-Korrektur der Overlay-Zuordnung über die Voice-Bank.
     * Jedes ASR-Segment (>= 2s) wird gegen die BESTÄTIGTEN Voiceprints geprüft
     * (confirmedOnly – keine pending, keine lockeren 0.35-Matches). Matcht die
     * Stimme klar (0.62) auf einen ANDEREN Sprecher als die zeitliche
     * Zuordnung, wird korrigiert (der Einwurf-Fall: "also Wähler..." akustisch
     * Sprecher 3, zeitlich Sprecher 4 zugeordnet).
     * Nur im Debug-Modus aktiv (die Roh-WAV für die Samples existiert nur dort).
     *
     * 0.6.23: Auch UNLABELED Segmente (>= 2s) werden akustisch aufgelöst – z.B.
     * ein Block zwischen zwei VERSCHIEDENEN bestätigten Speakern (Wechselgrenze)
     * bleibt in resolveListByNearestConfirmed bewusst unangetastet ("## Unbekannt"),
     * obwohl die Stimme eindeutig einem Voiceprint gehört. Konservativ: nur wenn
     * ein klarer 0.62-Match auf genau einen bestätigten Sprecher existiert.
     */
    private fun correctOverlayByVoiceBank(overlay: List<TranscriptSegment>): List<TranscriptSegment> {
        val wavFile = audioCapture.currentTestWavFile ?: return overlay
        if (!wavFile.exists()) return overlay
        var corrected = 0
        var resolved = 0
        // Phase 10 (0.9.9): identify() liefert BANK-Nummern – die müssen vor dem
        // Schreiben in die Anzeige-Segmente auf Anzeige-IDs gemappt werden (renumber!).
        val bankToDisplay = diarizationChunkWorker.originalGidToDisplayId()
            .entries.associate { (display, orig) -> orig to display }
        val result = overlay.map { seg ->
            val speakerNum = seg.speakerId?.removePrefix("speaker_")?.toIntOrNull()
            val durationMs = seg.endTimeMs - seg.startTimeMs
            if (durationMs < 2000) return@map seg
            val samples = readWavSamples(wavFile, seg.startTimeMs, seg.endTimeMs)
            if (samples == null || samples.isEmpty()) return@map seg
            val matchedBank = sessionVoiceBank.identify(samples, confirmedOnly = true)
            // Bank-Nr → Anzeige-Nr (Fallback: Bank-Nr selbst, wenn nicht gemappt)
            val matched = matchedBank?.let { bankToDisplay[it] ?: it }
            if (speakerNum == null) {
                // 0.6.23: Unlabeled Segment akustisch auflösen
                if (matched != null) {
                    resolved++
                    val label = "Sprecher ${matched + 1}"
                    Log.d(TAG, "VB_RESOLVE_UNLABELED: Segment ${seg.segmentId} (${seg.startTimeMs}-${seg.endTimeMs}ms) unlabeled → akustisch speaker_$matched (Wechselgrenze, 0.62-Match)")
                    TestLog.log("VB_RESOLVE_UNLABELED: Segment ${seg.startTimeMs}-${seg.endTimeMs}ms unlabeled → akustisch speaker_$matched")
                    seg.copy(speakerId = "speaker_$matched", speakerLabel = label)
                } else seg
            } else if (matched != null && matched != speakerNum) {
                corrected++
                val label = "Sprecher ${matched + 1}"
                Log.d(TAG, "VB_CORRECT: Segment ${seg.segmentId} (${seg.startTimeMs}-${seg.endTimeMs}ms) zeitlich ${seg.speakerId} → akustisch speaker_$matched (Backchannel-Korrektur)")
                TestLog.log("VB_CORRECT: Segment ${seg.startTimeMs}-${seg.endTimeMs}ms zeitlich ${seg.speakerId} → akustisch speaker_$matched")
                seg.copy(speakerId = "speaker_$matched", speakerLabel = label)
            } else seg
        }
        if (corrected > 0 || resolved > 0) {
            Log.i(TAG, "VB_OVERLAY: $corrected korrigiert, $resolved unlabeled aufgelöst (akustisch, confirmed-only)")
        }
        return result
    }

    /** Liest 16kHz-mono-s16le-WAV-Samples (Debug-WAV, 44-Byte-Header) für ein Zeitfenster. */
    private fun readWavSamples(file: java.io.File, startMs: Long, endMs: Long): FloatArray? {
        return try {
            java.io.FileInputStream(file).use { fis ->
                val header = 44L
                val bytesPerSec = 16000L * 2L
                val startByte = header + startMs * bytesPerSec / 1000L
                val endByte = header + endMs * bytesPerSec / 1000L
                val count = ((endByte - startByte) / 2L).toInt()
                if (count <= 0 || count > 16000 * 60) return null
                val buf = ByteArray(count * 2)
                if (fis.skip(startByte) < startByte) return null
                if (fis.read(buf) < buf.size) return null
                FloatArray(count) { i ->
                    val lo = buf[i * 2].toInt() and 0xFF
                    val hi = buf[i * 2 + 1].toInt()
                    (lo or (hi shl 8)).toShort().toFloat() / 32768f
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "readWavSamples fehlgeschlagen: ${t.message}")
            null
        }
    }

    /**
     * Vergleicht zwei Speaker-Zuordnungen und liefert den Unterschied.
     * Basis ist rawFinalSegments (die Segmentmenge). previous und next
     * werden per segmentId verglichen.
     */
    private fun compareAssignments(
        previous: List<TranscriptSegment>,
        next: List<TranscriptSegment>,
    ): AssignmentDiff {
        val prevById = previous.associateBy { it.segmentId }
        val nextById = next.associateBy { it.segmentId }
        var newlyLabeled = 0
        var changed = 0
        var lost = 0
        val allIds = (prevById.keys + nextById.keys)
        for (id in allIds) {
            val prevSpeaker = prevById[id]?.speakerId?.takeIf { it.isNotBlank() }
            val nextSpeaker = nextById[id]?.speakerId?.takeIf { it.isNotBlank() }
            when {
                prevSpeaker == null && nextSpeaker != null -> newlyLabeled++
                prevSpeaker != null && nextSpeaker == null -> lost++
                prevSpeaker != null && nextSpeaker != null && prevSpeaker != nextSpeaker -> changed++
            }
        }
        return AssignmentDiff(
            newlyLabeledSegments = newlyLabeled,
            changedSpeakerAssignments = changed,
            lostLabels = lost,
            distinctSpeakersBefore = previous.mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }.distinct().size,
            distinctSpeakersAfter = next.mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }.distinct().size,
        )
    }

    /**
     * Prüft, ob ein Diarization-Ergebnis noch angewendet werden darf.
     *
     * - isSavingFinalResult = true → nichts mehr, auch kein forceFinal
     * - epoch != diarizationEpoch → stale, neuerer Lauf existiert
     * - isStopping && !forceFinal → Background-Lauf nach Stop verwerfen
     * - forceFinal läuft auch bei isStopping=true durch (der finale Lauf selbst)
     */
    private fun canApplyDiarizationResult(epoch: Long, forceFinal: Boolean): Boolean {
        if (isSavingFinalResult) return false
        if (currentTranscriptId == null) return false
        if (epoch != diarizationEpoch) return false
        if (!forceFinal && isStopping) return false
        return true
    }

    private suspend fun runDiarization(forceFinal: Boolean = false) {
        val epoch = ++diarizationEpoch
        Log.d(TAG, "Diarization START: epoch=$epoch raw=${rawFinalSegments.size} stopping=$isStopping saving=$isSavingFinalResult forceFinal=$forceFinal")

        diarizationMutex.withLock {
            // Early drop: Background-Lauf nach Stop verwerfen
            if (!canApplyDiarizationResult(epoch, forceFinal)) {
                Log.d(TAG, "Diarization DROP_STALE: epoch=$epoch latest=$diarizationEpoch stopping=$isStopping saving=$isSavingFinalResult")
                return
            }

            withContext(Dispatchers.IO) {
                ensureActive()

                val snapshot: List<FloatArray>
                val offsetSec: Float
                synchronized(audioLock) {
                    if (!forceFinal && audioAccumulator.size < MIN_DIARIZATION_FRAMES) {
                        Log.d(TAG, "Diarization skip: only ${audioAccumulator.size} frames"); return@withContext
                    }
                    snapshot = audioAccumulator.toList(); offsetSec = audioBaseTimeMs / 1000f
                }
                if (snapshot.isEmpty()) return@withContext

                val totalSamples = snapshot.sumOf { it.size }
                val audioDurationSec = totalSamples / 16000f
                Log.d(TAG, "Diarization audio buffer: ${snapshot.size} frames, ${totalSamples} samples (${
                    "%.1f".format(audioDurationSec)
                }s)")
                val allAudio = FloatArray(totalSamples); var writeOffset = 0
                for (frame in snapshot) { frame.copyInto(allAudio, writeOffset); writeOffset += frame.size }

                // Sliding Window: pyannote degradiert bei langen Buffern → nur die letzten
                // DIARIZATION_WINDOW_SEC Sekunden verarbeiten, Offset entsprechend verschieben.
                val windowStartSample = maxOf(0, totalSamples - (DIARIZATION_WINDOW_SEC * 16000f).toInt())
                val windowAudio = allAudio.copyOfRange(windowStartSample, totalSamples)
                val windowOffsetSec = offsetSec + windowStartSample / 16000f
                val windowDurSec = windowAudio.size / 16000f
                if (windowStartSample > 0) {
                    Log.d(TAG, "Diarization sliding window: ${"%.1f".format(windowDurSec)}s (offset=+${"%.1f".format(windowStartSample / 16000f)}s)")
                }

                try {
                    val rawSegs = speakerEngine.process(windowAudio)
                    if (rawSegs.isEmpty()) {
                        // Final-Run mit 0 Segmenten: konservierten Kandidaten als Fallback übernehmen,
                        // statt auf einen schlechteren Stand zurückzufallen
                        if (forceFinal) {
                            val fallback = lastGoodDiarizationCandidate
                            if (fallback != null) {
                                val fbQuality = computeQuality(fallback)
                                if (fbQuality.labeledSegments > bestAssignmentQuality.labeledSegments ||
                                    fbQuality.distinctSpeakers > bestAssignmentQuality.distinctSpeakers
                                ) {
                                    assignedFinalSegments = fallback
                                    bestAssignmentQuality = fbQuality
                                    renumberLiveSpeakerIds()
                                    Log.w(TAG, "Diarization FINAL_FALLBACK: epoch=$epoch engine=0 segments — restored preserved candidate " +
                                            "(labeled=${fbQuality.labeledSegments} speakers=${fbQuality.distinctSpeakers})")
                                } else {
                                    Log.w(TAG, "Diarization FINAL_FALLBACK: epoch=$epoch engine=0 segments, fallback not better " +
                                            "(labeled=${fbQuality.labeledSegments} speakers=${fbQuality.distinctSpeakers} vs best ${bestAssignmentQuality.labeledSegments}/${bestAssignmentQuality.distinctSpeakers})")
                                }
                            } else {
                                Log.w(TAG, "Diarization FINAL_FALLBACK: epoch=$epoch engine=0 segments, no preserved candidate available")
                            }
                        }
                        Log.w(TAG, "Diarization: 0 segments from engine")
                        return@withContext
                    }

                    val diarizationSegs = rawSegs.map { DiarizationSegment(it.startSec + windowOffsetSec, it.endSec + windowOffsetSec, it.speaker) }
                    lastDiarizationSegments = diarizationSegs

                    // Rohsegmente vor Zuordnung kompaktieren (temporäre Kopie)
                    val compactedSegs = TimelineComposer.compactRawSegmentsBeforeAssignment(rawFinalSegments)
                    val candidate = TimelineComposer.assignSpeakersToRawSegments(compactedSegs, diarizationSegs, debug = _uiState.value.debugMode)
                    if (candidate.isEmpty()) return@withContext
                    val candQuality = computeQuality(candidate)

                    // Speaker-IDs über Läufe hinweg normalisieren
                    val normalizedCandidate = TimelineComposer.normalizeSpeakerIds(candidate, assignedFinalSegments)
                    // Debug: normalized labels vor Merge
                    val normLabels = normalizedCandidate
                        .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                        .distinct().map { it.removePrefix("speaker_") }.sorted().joinToString(",")
                    Log.d(TAG, "runDiarization: normalized=$normLabels candRaw=${
                        candidate.mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                            .distinct().map { it.removePrefix("speaker_") }.sorted().joinToString(",")
                    } best=${
                        assignedFinalSegments.mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                            .distinct().map { it.removePrefix("speaker_") }.sorted().joinToString(",")
                    }")
                    val merged = mergeCandidateIntoBest(normalizedCandidate)
                    val mergedQuality = computeQuality(merged)
                    val previousBest = bestAssignmentQuality
                    val prevAssigned = assignedFinalSegments
                    val diff = compareAssignments(prevAssigned, merged)

                    // Schutzstatus für Logs: Welcher Schutzmechanismus ist aktiv?
                    val protectActive = previousBest.distinctSpeakers >= 2 &&
                            candQuality.distinctSpeakers < previousBest.distinctSpeakers
                    val bestSpeakerIdSet = prevAssigned
                        .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                        .distinct().toSet()
                    val candNewIds = normalizedCandidate
                        .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                        .distinct().filter { it !in bestSpeakerIdSet }
                    val stripActive = previousBest.distinctSpeakers >= 2 && candNewIds.isNotEmpty()
                    val protectInfo = buildString {
                        append("protect=").append(if (protectActive) "active" else "inactive")
                        append(" bestSpeakers=").append(previousBest.distinctSpeakers)
                        append(" candSpeakers=").append(candQuality.distinctSpeakers)
                        if (stripActive) append(" strip=active newIds=[${candNewIds.map { it.removePrefix("speaker_") }.joinToString(",")}]")
                    }

                    // Commit-Guard: nur anwenden, wenn dieser Lauf noch aktuell ist
                    if (!canApplyDiarizationResult(epoch, forceFinal)) {
                        val reason = if (isSavingFinalResult) "DROP_DURING_SAVE" else if (isStopping) "DROP_STALE" else "DROP_EPOCH"
                        // In-Flight-Ergebnis konservieren: besserer Kandidat als Fallback für Stop/Save
                        if (isStopping && !isSavingFinalResult &&
                            (mergedQuality.labeledSegments > bestAssignmentQuality.labeledSegments ||
                                mergedQuality.distinctSpeakers > bestAssignmentQuality.distinctSpeakers)
                        ) {
                            lastGoodDiarizationCandidate = merged
                            Log.w(TAG, "Diarization $reason: epoch=$epoch — preserving better candidate as fallback " +
                                    "(labeled=${mergedQuality.labeledSegments} speakers=${mergedQuality.distinctSpeakers})")
                            if (_uiState.value.debugMode) {
                                val labelDist = merged.mapNotNull { it.speakerId }.groupingBy { it }.eachCount()
                                    .entries.joinToString(",") { (k, v) -> "$k=$v" }
                                Log.d(TAG, "LIVE_DBG_FALLBACK epoch=$epoch labeled=${mergedQuality.labeledSegments}/${merged.size} speakers=${mergedQuality.distinctSpeakers} labels=[$labelDist]")
                            }
                        } else {
                            Log.w(TAG, "Diarization $reason: epoch=$epoch latest=$diarizationEpoch stopping=$isStopping saving=$isSavingFinalResult")
                        }
                        return@withContext
                    }

                    if (forceFinal) {
                        Log.d(TAG, "Diarization FINAL_RUN: epoch=$epoch raw=${rawFinalSegments.size}")
                    }

                    val logPrefix = if (forceFinal) "FINAL_" else ""
                    if (!diff.isMeaningfulChange) {
                        Log.i(TAG, "Diarization ${logPrefix}NO_CHANGE: epoch=$epoch raw=${rawFinalSegments.size} " +
                                "candRaw=(labeled=${candQuality.labeledSegments}/${candidate.size} speakers=${candQuality.distinctSpeakers} " +
                                "dur=${candQuality.totalLabeledDurationMs}) " +
                                "merged=(labeled=${mergedQuality.labeledSegments}/${merged.size} speakers=${mergedQuality.distinctSpeakers} " +
                                "dur=${mergedQuality.totalLabeledDurationMs}) " +
                                "best=(labeled=${previousBest.labeledSegments} speakers=${previousBest.distinctSpeakers} " +
                                "dur=${previousBest.totalLabeledDurationMs}) " +
                                "diff=(new=${diff.newlyLabeledSegments} changed=${diff.changedSpeakerAssignments} lost=${diff.lostLabels}) " +
                                "$protectInfo")
                    } else if (shouldAcceptAssignment(merged)) {
                        // Final-Run: Speaker-Verlust verhindern – der finale Lauf darf
                        // die Sprecherzahl nicht reduzieren, nur neue Labels ergänzen
                        if (forceFinal && previousBest.distinctSpeakers >= 2 &&
                            mergedQuality.distinctSpeakers < previousBest.distinctSpeakers
                        ) {
                            Log.w(TAG, "Diarization FINAL_SKIP_COLLAPSE: epoch=$epoch " +
                                    "${previousBest.distinctSpeakers}→${mergedQuality.distinctSpeakers} speakers " +
                                    "candRaw=(labeled=${candQuality.labeledSegments}/${candidate.size} " +
                                    "speakers=${candQuality.distinctSpeakers}) — keeping pre-stop best")
                        } else {
                            assignedFinalSegments = merged
                            bestAssignmentQuality = mergedQuality
                            renumberLiveSpeakerIds()
                            // FIRST_2SPK: erster 2-Speaker-Zustand der Session – Umschaltpunkt-Marker
                            if (!firstTwoSpeakerLogged && mergedQuality.distinctSpeakers >= 2) {
                                firstTwoSpeakerLogged = true
                                val prevSpeakerIds = prevAssigned
                                    .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                                    .distinct().toSet()
                                val firstNew = merged.firstOrNull { seg ->
                                    seg.speakerId?.let { it.isNotBlank() && it !in prevSpeakerIds } == true
                                }
                                // Stärke der Einführung: wie viele Segmente/Dauer trägt der neue Speaker im Commit?
                                val newSpeakerId = firstNew?.speakerId
                                val newSegs = if (newSpeakerId != null) {
                                    merged.filter { it.speakerId == newSpeakerId }
                                } else emptyList()
                                val newDurMs = newSegs.sumOf { it.endTimeMs - it.startTimeMs }
                                Log.i(TAG, "FIRST_2SPK epoch=$epoch newSpeaker=${newSpeakerId ?: "?"} " +
                                        "at=${firstNew?.startTimeMs ?: 0}ms end=${firstNew?.endTimeMs ?: 0}ms " +
                                        "newSegs=${newSegs.size} newDur=${newDurMs}ms " +
                                        "text=\"${firstNew?.text?.take(50) ?: ""}\"")
                            }
                            // LIVE_DBG_LABEL_NEW / LIVE_DBG_REMAP: betroffene Segmente nach Commit
                            // - from=none → erstmals gelabelt (newly labeled)
                            // - from=anderer Speaker → echte Umentscheidung (changed)
                            if (_uiState.value.debugMode) {
                                val prevById = prevAssigned.associateBy { it.segmentId }
                                for (m in merged) {
                                    val prevSpk = prevById[m.segmentId]?.speakerId?.takeIf { it.isNotBlank() }
                                    val newSpk = m.speakerId?.takeIf { it.isNotBlank() }
                                    when {
                                        newSpk != null && prevSpk == null ->
                                            Log.d(TAG, "LIVE_DBG_LABEL_NEW id=${m.segmentId.take(8)} from=none to=$newSpk start=${m.startTimeMs} end=${m.endTimeMs} text=\"${m.text.take(40)}\"")
                                        newSpk != null && prevSpk != null && prevSpk != newSpk ->
                                            Log.d(TAG, "LIVE_DBG_REMAP id=${m.segmentId.take(8)} from=$prevSpk to=$newSpk start=${m.startTimeMs} end=${m.endTimeMs} text=\"${m.text.take(40)}\"")
                                    }
                                }
                            }
                            // Speaker-Detail für Debug
                            val mergedSpeakerIds = merged
                                .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                                .distinct().map { it.removePrefix("speaker_") }.sorted().joinToString(",")
                            val prevSpeakerIds = prevAssigned
                                .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                                .distinct().map { it.removePrefix("speaker_") }.sorted().joinToString(",")
                            val newSpeakerIds = merged
                                .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                                .distinct()
                                .filter { id -> prevAssigned.none { it.speakerId == id } }
                                .map { it.removePrefix("speaker_") }.sorted().joinToString(",")
                            val lostSpeakerIds = prevAssigned
                                .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                                .distinct()
                                .filter { id -> merged.none { it.speakerId == id } }
                                .map { it.removePrefix("speaker_") }.sorted().joinToString(",")

                            Log.i(TAG, "Diarization ${logPrefix}ACCEPTED_IMPROVED: epoch=$epoch raw=${rawFinalSegments.size} " +
                                    "candRaw=(labeled=${candQuality.labeledSegments}/${candidate.size} speakers=${candQuality.distinctSpeakers} " +
                                    "dur=${candQuality.totalLabeledDurationMs}) " +
                                    "merged=(labeled=${mergedQuality.labeledSegments}/${merged.size} speakers=${mergedQuality.distinctSpeakers} " +
                                    "dur=${mergedQuality.totalLabeledDurationMs} ids=[$mergedSpeakerIds]) " +
                                    "best=(labeled=${previousBest.labeledSegments} speakers=${previousBest.distinctSpeakers} " +
                                    "dur=${previousBest.totalLabeledDurationMs} ids=[$prevSpeakerIds]) " +
                                    "diff=(new=${diff.newlyLabeledSegments} changed=${diff.changedSpeakerAssignments} lost=${diff.lostLabels})" +
                                    if (newSpeakerIds.isNotEmpty()) " newIds=[$newSpeakerIds]" else "" +
                                    if (lostSpeakerIds.isNotEmpty()) " lostIds=[$lostSpeakerIds]" else "" +
                                    " $protectInfo")
                        }
                    } else {
                        Log.w(TAG, "Diarization ${logPrefix}REJECTED: epoch=$epoch raw=${rawFinalSegments.size} " +
                                "candRaw=(labeled=${candQuality.labeledSegments}/${candidate.size} speakers=${candQuality.distinctSpeakers} " +
                                "dur=${candQuality.totalLabeledDurationMs}) " +
                                "merged=(labeled=${mergedQuality.labeledSegments}/${merged.size} speakers=${mergedQuality.distinctSpeakers} " +
                                "dur=${mergedQuality.totalLabeledDurationMs}) " +
                                "best=(labeled=${previousBest.labeledSegments} speakers=${previousBest.distinctSpeakers} " +
                                "dur=${previousBest.totalLabeledDurationMs}) " +
                                "diff=(new=${diff.newlyLabeledSegments} changed=${diff.changedSpeakerAssignments} lost=${diff.lostLabels}) " +
                                "$protectInfo")
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Diarization cancelled during IO: epoch=$epoch"); throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Diarization error: epoch=$epoch ${e.message}", e)
                }
            }
        }
    }

    /**
     * Neue Rolling-Reconciliation-Pipeline (Toggle ENABLE_CHUNKED_DIARIZATION=ON).
     *
     * Gleiche Epoch/Mutex/Quality-Guards wie [runDiarization], aber:
     * - Chunk kommt vom [DiarizationChunkWorker] (20s + 5s Overlap aus dem ChunkedAudioBuffer)
     * - Worker liefert bereits GLOBALE, session-stabile Speaker-IDs (RollingReconciler)
     *   → normalizeSpeakerIds entfällt (kein doppelter Normalizer!)
     * - Zeiten sind bereits absolut (Time-Shift im Worker) → kein Offset-Shift hier
     * - forceFinal nutzt processFinalChunk() (Rest-Audio bis Stop, kein Segmentverlust)
     */
    private suspend fun runChunkedDiarization(forceFinal: Boolean = false) {
        val epoch = ++diarizationEpoch
        Log.d(TAG, "ChunkedDiarization START: epoch=$epoch raw=${rawFinalSegments.size} stopping=$isStopping saving=$isSavingFinalResult forceFinal=$forceFinal")

        diarizationMutex.withLock {
            // Early drop: Background-Lauf nach Stop verwerfen
            if (!canApplyDiarizationResult(epoch, forceFinal)) {
                Log.d(TAG, "ChunkedDiarization DROP_STALE: epoch=$epoch latest=$diarizationEpoch stopping=$isStopping saving=$isSavingFinalResult")
                return
            }

            withContext(Dispatchers.IO) {
                ensureActive()

                val workerResult = if (forceFinal) {
                    diarizationChunkWorker.processFinalChunk(debug = _uiState.value.debugMode)
                } else {
                    diarizationChunkWorker.processNextChunk(debug = _uiState.value.debugMode)
                }
                if (workerResult == null) {
                    Log.d(TAG, "ChunkedDiarization skip: noch kein voller Chunk verfügbar (epoch=$epoch)")
                    return@withContext
                }
                val diarizationSegs = workerResult.mappedSegments
                if (diarizationSegs.isEmpty()) {
                    // Final-Run mit 0 Segmenten: konservierten Kandidaten als Fallback übernehmen
                    if (forceFinal) {
                        val fallback = lastGoodDiarizationCandidate
                        if (fallback != null) {
                            val fbQuality = computeQuality(fallback)
                            if (fbQuality.labeledSegments > bestAssignmentQuality.labeledSegments ||
                                fbQuality.distinctSpeakers > bestAssignmentQuality.distinctSpeakers
                            ) {
                                assignedFinalSegments = fallback
                                bestAssignmentQuality = fbQuality
                                renumberLiveSpeakerIds()
                                Log.w(TAG, "ChunkedDiarization FINAL_FALLBACK: epoch=$epoch worker=0 segments — restored preserved candidate " +
                                        "(labeled=${fbQuality.labeledSegments} speakers=${fbQuality.distinctSpeakers})")
                            }
                        }
                    }
                    Log.w(TAG, "ChunkedDiarization: 0 segments from engine (epoch=$epoch, mapping=${workerResult.mapping})")
                    return@withContext
                }
                lastDiarizationSegments = diarizationSegs

                // Rohsegmente vor Zuordnung kompaktieren (temporäre Kopie)
                val compactedSegs = TimelineComposer.compactRawSegmentsBeforeAssignment(rawFinalSegments)

                // Hebel 2 (0.5.52): Lange Whisper-Segmente (>8s) an Diarization-Grenzen
                // splitten – VOR dem Assignment, damit ein Wechsel im Segment nicht vom
                // dominanten Sprecher geschluckt wird. Die Split-Ergebnisse werden in
                // rawFinalSegments ZURÜCKGESPIEGELT (Ground Truth): Der Whisper-Dedupe
                // matcht über Text-Overlap + Zeit (nicht über UUIDs) – solange das lange
                // Segment in rawFinalSegments als EINES existiert, stülpt der nächste
                // REPLACE (fullPrefix) den Text wieder darüber und der Split stirbt.
                val splitRawSegs = TimelineComposer.splitLongSpeakerSegments(compactedSegs, diarizationSegs)
                val finalRawForAssignment = if (splitRawSegs.size > compactedSegs.size) {
                    Log.d(TAG, "ChunkedDiarization SPLIT(raw): ${compactedSegs.size} → ${splitRawSegs.size} " +
                            "Segmente – Ground Truth (rawFinalSegments) aktualisiert")
                    // Atomare Referenz-Ersetzung statt in-place clear+addAll (Race-Schutz)
                    rawFinalSegments = splitRawSegs.toMutableList()
                    splitRawSegs
                } else {
                    compactedSegs
                }

                // Worker liefert globale IDs → KEIN normalizeSpeakerIds nötig
                val candidate = TimelineComposer.assignSpeakersToRawSegments(finalRawForAssignment, diarizationSegs, debug = _uiState.value.debugMode)
                if (candidate.isEmpty()) return@withContext
                val candQuality = computeQuality(candidate)

                // Debug: globale IDs aus dem Worker
                val globalLabels = diarizationSegs
                    .map { it.speaker }
                    .distinct().sorted().joinToString(",")
                Log.d(TAG, "ChunkedDiarization: workerSegs=${diarizationSegs.size} globalIds=[$globalLabels] " +
                        "mapping=[${workerResult.mapping}] new=[${workerResult.newSpeakerIds.sorted().joinToString(",")}] " +
                        "vb=(bank=${workerResult.voiceBankSize} resolve=${workerResult.voiceBankResolvedCount} " +
                        "enroll=${workerResult.voiceBankEnrolledCount} skip=${workerResult.voiceBankSkipCount}) " +
                        "globalResolve=${workerResult.globalResolvedCount} globalMap=${workerResult.globalProfileMapSize}")
                TestLog.log("CHUNKED epoch=$epoch workerSegs=${diarizationSegs.size} globalIds=[$globalLabels] " +
                        "new=[${workerResult.newSpeakerIds.sorted().joinToString(",")}] " +
                        "vb=(bank=${workerResult.voiceBankSize} resolve=${workerResult.voiceBankResolvedCount} " +
                        "enroll=${workerResult.voiceBankEnrolledCount} skip=${workerResult.voiceBankSkipCount}) " +
                        "global=${workerResult.globalResolvedCount}/${workerResult.globalProfileMapSize}")

                val merged = mergeCandidateIntoBest(candidate)
                val mergedQuality = computeQuality(merged)
                val previousBest = bestAssignmentQuality
                val prevAssigned = assignedFinalSegments
                val diff = compareAssignments(prevAssigned, merged)

                // Commit-Guard: nur anwenden, wenn dieser Lauf noch aktuell ist
                if (!canApplyDiarizationResult(epoch, forceFinal)) {
                    val reason = if (isSavingFinalResult) "DROP_DURING_SAVE" else if (isStopping) "DROP_STALE" else "DROP_EPOCH"
                    // In-Flight-Ergebnis konservieren: besserer Kandidat als Fallback für Stop/Save
                    if (isStopping && !isSavingFinalResult &&
                        (mergedQuality.labeledSegments > bestAssignmentQuality.labeledSegments ||
                            mergedQuality.distinctSpeakers > bestAssignmentQuality.distinctSpeakers)
                    ) {
                        lastGoodDiarizationCandidate = merged
                        Log.w(TAG, "ChunkedDiarization $reason: epoch=$epoch — preserving better candidate as fallback " +
                                "(labeled=${mergedQuality.labeledSegments} speakers=${mergedQuality.distinctSpeakers})")
                    } else {
                        Log.w(TAG, "ChunkedDiarization $reason: epoch=$epoch latest=$diarizationEpoch stopping=$isStopping saving=$isSavingFinalResult")
                    }
                    return@withContext
                }

                if (forceFinal) {
                    Log.d(TAG, "ChunkedDiarization FINAL_RUN: epoch=$epoch raw=${rawFinalSegments.size}")
                }

                val logPrefix = if (forceFinal) "FINAL_" else ""
                if (!diff.isMeaningfulChange) {
                    Log.i(TAG, "ChunkedDiarization ${logPrefix}NO_CHANGE: epoch=$epoch raw=${rawFinalSegments.size} " +
                            "candRaw=(labeled=${candQuality.labeledSegments}/${candidate.size} speakers=${candQuality.distinctSpeakers} " +
                            "dur=${candQuality.totalLabeledDurationMs}) " +
                            "merged=(labeled=${mergedQuality.labeledSegments}/${merged.size} speakers=${mergedQuality.distinctSpeakers} " +
                            "dur=${mergedQuality.totalLabeledDurationMs}) " +
                            "best=(labeled=${previousBest.labeledSegments} speakers=${previousBest.distinctSpeakers} " +
                            "dur=${previousBest.totalLabeledDurationMs}) " +
                            "diff=(new=${diff.newlyLabeledSegments} changed=${diff.changedSpeakerAssignments} lost=${diff.lostLabels})")
                } else if (shouldAcceptAssignment(merged)) {
                    if (forceFinal && previousBest.distinctSpeakers >= 2 &&
                        mergedQuality.distinctSpeakers < previousBest.distinctSpeakers
                    ) {
                        Log.w(TAG, "ChunkedDiarization FINAL_SKIP_COLLAPSE: epoch=$epoch " +
                                "${previousBest.distinctSpeakers}→${mergedQuality.distinctSpeakers} speakers " +
                                "— keeping pre-stop best")
                    } else {
                        assignedFinalSegments = merged
                        bestAssignmentQuality = mergedQuality
                        // Heuristik (0.5.63): Führende unbestätigte Labels im Final-Lauf
                        // dem ersten bestätigten Sprecher zuordnen – MUSS vor renumber laufen,
                        // damit die Nummerierung nach der Auflösung stimmt.
                        if (forceFinal) resolveLeadingUnconfirmedSpeakerLabels()
                        // 0.6.1/0.6.4: verbleibende unlabeled/unbestätigte Segmente
                        // (z.B. kurze Fragmente ohne Bank-Bestätigung, die als eigene
                        // IDs im Bestand stehen) über den eindeutigen bestätigten
                        // Nachbarn labeln – MUSS vor renumber laufen.
                        if (forceFinal) resolveRemainingUnconfirmedByNearestConfirmed()
                        renumberLiveSpeakerIds()
                        // FIRST_2SPK: erster 2-Speaker-Zustand der Session – Umschaltpunkt-Marker
                        if (!firstTwoSpeakerLogged && mergedQuality.distinctSpeakers >= 2) {
                            firstTwoSpeakerLogged = true
                            val prevSpeakerIds = prevAssigned
                                .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                                .distinct().toSet()
                            val firstNew = merged.firstOrNull { seg ->
                                seg.speakerId?.let { it.isNotBlank() && it !in prevSpeakerIds } == true
                            }
                            val newSpeakerId = firstNew?.speakerId
                            val newSegs = if (newSpeakerId != null) {
                                merged.filter { it.speakerId == newSpeakerId }
                            } else emptyList()
                            val newDurMs = newSegs.sumOf { it.endTimeMs - it.startTimeMs }
                            Log.i(TAG, "FIRST_2SPK epoch=$epoch newSpeaker=${newSpeakerId ?: "?"} " +
                                    "at=${firstNew?.startTimeMs ?: 0}ms end=${firstNew?.endTimeMs ?: 0}ms " +
                                    "newSegs=${newSegs.size} newDur=${newDurMs}ms " +
                                    "text=\"${firstNew?.text?.take(50) ?: ""}\"")
                        }
                        val mergedSpeakerIds = merged
                            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                            .distinct().map { it.removePrefix("speaker_") }.sorted().joinToString(",")
                        val prevSpeakerIds = prevAssigned
                            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
                            .distinct().map { it.removePrefix("speaker_") }.sorted().joinToString(",")
                        Log.i(TAG, "ChunkedDiarization ${logPrefix}ACCEPTED_IMPROVED: epoch=$epoch raw=${rawFinalSegments.size} " +
                                "candRaw=(labeled=${candQuality.labeledSegments}/${candidate.size} speakers=${candQuality.distinctSpeakers} " +
                                "dur=${candQuality.totalLabeledDurationMs}) " +
                                "merged=(labeled=${mergedQuality.labeledSegments}/${merged.size} speakers=${mergedQuality.distinctSpeakers} " +
                                "dur=${mergedQuality.totalLabeledDurationMs} ids=[$mergedSpeakerIds]) " +
                                "best=(labeled=${previousBest.labeledSegments} speakers=${previousBest.distinctSpeakers} " +
                                "dur=${previousBest.totalLabeledDurationMs} ids=[$prevSpeakerIds]) " +
                                "diff=(new=${diff.newlyLabeledSegments} changed=${diff.changedSpeakerAssignments} lost=${diff.lostLabels})")
                    }
                } else {
                    Log.w(TAG, "ChunkedDiarization ${logPrefix}REJECTED: epoch=$epoch raw=${rawFinalSegments.size} " +
                            "candRaw=(labeled=${candQuality.labeledSegments}/${candidate.size} speakers=${candQuality.distinctSpeakers} " +
                            "dur=${candQuality.totalLabeledDurationMs}) " +
                            "merged=(labeled=${mergedQuality.labeledSegments}/${merged.size} speakers=${mergedQuality.distinctSpeakers} " +
                            "dur=${mergedQuality.totalLabeledDurationMs}) " +
                            "best=(labeled=${previousBest.labeledSegments} speakers=${previousBest.distinctSpeakers} " +
                            "dur=${previousBest.totalLabeledDurationMs}) " +
                            "diff=(new=${diff.newlyLabeledSegments} changed=${diff.changedSpeakerAssignments} lost=${diff.lostLabels})")
                }
            }
        }
    }

    private fun computeQuality(segments: List<TranscriptSegment>): AssignmentQuality {
        val labeled = segments.filter { !it.speakerId.isNullOrBlank() }
        return AssignmentQuality(
            labeledSegments = labeled.size,
            unlabeledSegments = segments.size - labeled.size,
            distinctSpeakers = labeled.mapNotNull { it.speakerId }.distinct().size,
            totalLabeledDurationMs = labeled.sumOf { it.endTimeMs - it.startTimeMs },
        )
    }

    /**
     * Quality Gate: entscheidet, ob ein gemergter Kandidat den bisherigen Beststand ersetzt.
     *
     * Priorität der Bewertung:
     * 1. Erster Lauf → immer akzeptieren
     * 2. Kandidat hat 0 Labels, current hat welche → ablehnen
     * 3. Speaker-Rückschritt (2→1, 3→1, 3→2) → immer ablehnen
     * 4. Coverage-Einbruch (< 50% Labels oder Dauer) → ablehnen
     * 5. Neue Sprecher: mehr distinctSpeakers als best + Coverage ≥ 70% → akzeptieren
     * 6. Gleiche Sprecherzahl: höhere labeled duration gewinnt (oder mind. 20% besser + 80% Duration)
     */
    private fun shouldAcceptAssignment(candidate: List<TranscriptSegment>): Boolean {
        // Regel 1
        if (bestAssignmentQuality.labeledSegments == 0 && bestAssignmentQuality.distinctSpeakers == 0) return true

        val candQuality = computeQuality(candidate)

        // Regel 2: 0 Labels obwohl current welche hat
        if (bestAssignmentQuality.labeledSegments > 0 && candQuality.labeledSegments == 0) return false

        // Regel 3: Speaker-Rückschritt – weniger Speaker als best → immer ablehnen
        if (bestAssignmentQuality.distinctSpeakers >= 2 &&
            candQuality.distinctSpeakers < bestAssignmentQuality.distinctSpeakers
        ) return false

        // Regel 4a: Labelanzahl < 50 %
        if (candQuality.labeledSegments < bestAssignmentQuality.labeledSegments * 0.5f) return false
        // Regel 4b: Labeled Duration < 50 %
        if (bestAssignmentQuality.totalLabeledDurationMs > 0 &&
            candQuality.totalLabeledDurationMs < bestAssignmentQuality.totalLabeledDurationMs * 0.5f
        ) return false

        // Regel 5: Neue Sprecher fördern
        if (candQuality.distinctSpeakers > bestAssignmentQuality.distinctSpeakers) {
            // Hebel F: bei ALLOW_NEW_SPEAKER_IDS wird die Coverage-Schwelle von
            // 0.7 auf 0.5 gesenkt, damit Engine-Drift-IDs (nach 60s) durchkommen
            // und der spätere Speaker-Merger sie zusammenführen kann.
            val coverageThreshold = if (ALLOW_NEW_SPEAKER_IDS) 0.5f else 0.7f
            // 1→2-Übergang: früheres Onboarding mit niedrigerer Coverage-Schwelle (0.5 statt 0.7),
            // aber nur mit Mindest-Evidenz für den NEUEN Sprecher (kein Zufalls-Label)
            val isFirst2Speaker = bestAssignmentQuality.distinctSpeakers == 1 && candQuality.distinctSpeakers == 2
            val threshold = if (isFirst2Speaker) 0.5f else coverageThreshold
            if (candQuality.labeledSegments >= bestAssignmentQuality.labeledSegments * threshold &&
                (!isFirst2Speaker || newSpeakerHasEvidence(candidate))
            ) return true
        }

        // Regel 6: Gleiche Speakerzahl – höhere Coverage gewinnt
        if (candQuality.distinctSpeakers == bestAssignmentQuality.distinctSpeakers) {
            if (candQuality.labeledSegments >= bestAssignmentQuality.labeledSegments &&
                candQuality.totalLabeledDurationMs >= bestAssignmentQuality.totalLabeledDurationMs
            ) return true
            // Coverage deutlich besser, Duration nur leicht schlechter
            if (candQuality.labeledSegments > bestAssignmentQuality.labeledSegments * 1.2f &&
                candQuality.totalLabeledDurationMs >= bestAssignmentQuality.totalLabeledDurationMs * 0.8f
            ) return true
            return false // sonst: best war besser
        }

        return true
    }

    /**
     * Mindest-Evidenz für einen NEUEN Sprecher beim 1→2-Übergang:
     * Der neue Speaker muss >= 2 gelabelte Segmente ODER >= 3s Label-Dauer tragen,
     * damit ein einzelnes Zufalls-Label keinen zweiten Sprecher materialisiert.
     */
    private fun newSpeakerHasEvidence(candidate: List<TranscriptSegment>): Boolean {
        val bestSpeakerIds = assignedFinalSegments.mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }.toSet()
        if (bestSpeakerIds.isEmpty()) return false
        val newSpeakerSegs = candidate.filter { seg ->
            val sid = seg.speakerId?.takeIf { it.isNotBlank() } ?: return@filter false
            sid !in bestSpeakerIds
        }
        if (newSpeakerSegs.isEmpty()) return false
        val newDuration = newSpeakerSegs.sumOf { it.endTimeMs - it.startTimeMs }
        val hasEvidence = newSpeakerSegs.size >= 2 || newDuration >= 3000L
        if (!hasEvidence) {
            Log.d(TAG, "shouldAcceptAssignment: 1→2 rejected – new speaker lacks evidence (${newSpeakerSegs.size} segs, ${newDuration}ms)")
        }
        return hasEvidence
    }

    /** Loggt Speaker-Set einer Save-Phase, um 2→1-Kollaps im Persistierungs-Pfad zu finden. */
    private fun logSaveSpeakerStage(stage: String, segments: List<TranscriptSegment>) {
        val labeled = segments.count { !it.speakerId.isNullOrBlank() }
        val labeledDurMs = segments
            .filter { !it.speakerId.isNullOrBlank() }
            .sumOf { it.endTimeMs - it.startTimeMs }
        val ids = segments
            .mapNotNull { it.speakerId?.takeIf { s -> s.isNotBlank() } }
            .distinct().map { it.removePrefix("speaker_") }.sorted()
        Log.i(TAG, "saveStage $stage: segments=${segments.size} labeled=$labeled labeledDur=${labeledDurMs}ms speakers=${ids.size} ids=[${ids.joinToString(",")}]")
        // Phase 10 (0.9.9): Stage-Statistik ins TestLog (vorher nur logcat –
        // deshalb fehlten uns die Save-Stufen in den Debug-Uploads!)
        TestLog.log("SAVE_STAGE $stage: segs=${segments.size} labeled=$labeled speakers=${ids.size} ids=[${ids.joinToString(",")}]")
        // DBG: Segment-Dump pro Stage – zeigt, welche IDs/Grenzen die Pipeline verändert
        if (_uiState.value.debugMode) {
            for (seg in segments) {
                TestLog.log("SAVE_DBG_${stage.replace(" ", "_")} id=${seg.segmentId.take(8)} t=${seg.startTimeMs}-${seg.endTimeMs} spk=${seg.speakerId ?: "-"} text=\"${seg.text.take(30)}\"")
                Log.d(TAG, "SAVE_DBG_${stage.replace(" ", "_")} id=${seg.segmentId.take(8)} t=${seg.startTimeMs}-${seg.endTimeMs} spk=${seg.speakerId ?: "-"} text=\"${seg.text.take(30)}\"")
            }
        }
    }

    /** DBG: Delta zwischen zwei Save-Stages – welche Segment-IDs verschwinden/neu entstehen */
    private fun logSaveStageDelta(stageFrom: String, from: List<TranscriptSegment>, stageTo: String, to: List<TranscriptSegment>) {
        if (!_uiState.value.debugMode) return
        val fromIds = from.map { it.segmentId }.toSet()
        val toIds = to.map { it.segmentId }.toSet()
        val gone = from.filter { it.segmentId !in toIds }
        val added = to.filter { it.segmentId !in fromIds }
        for (g in gone) {
            TestLog.log("SAVE_DBG_DELTA $stageFrom→$stageTo GONE id=${g.segmentId.take(8)} t=${g.startTimeMs}-${g.endTimeMs} spk=${g.speakerId ?: "-"}")
            Log.d(TAG, "SAVE_DBG_DELTA $stageFrom→$stageTo GONE id=${g.segmentId.take(8)} t=${g.startTimeMs}-${g.endTimeMs} spk=${g.speakerId ?: "-"} text=\"${g.text.take(30)}\"")
        }
        for (a in added) {
            TestLog.log("SAVE_DBG_DELTA $stageFrom→$stageTo NEW id=${a.segmentId.take(8)} t=${a.startTimeMs}-${a.endTimeMs} spk=${a.speakerId ?: "-"}")
            Log.d(TAG, "SAVE_DBG_DELTA $stageFrom→$stageTo NEW id=${a.segmentId.take(8)} t=${a.startTimeMs}-${a.endTimeMs} spk=${a.speakerId ?: "-"} text=\"${a.text.take(30)}\"")
        }
        // Auch geänderte Speaker-Zuordnung bei gleicher ID anzeigen
        val toById = to.associateBy { it.segmentId }
        var spkChanges = 0
        for (f in from) {
            val t2 = toById[f.segmentId] ?: continue
            val fs = f.speakerId?.takeIf { it.isNotBlank() }
            val ts = t2.speakerId?.takeIf { it.isNotBlank() }
            if (fs != ts) {
                spkChanges++
                if (spkChanges <= 40) {
                    TestLog.log("SAVE_DBG_DELTA $stageFrom→$stageTo SPK_CHANGE id=${f.segmentId.take(8)} from=${fs ?: "none"} to=${ts ?: "none"}")
                }
                Log.d(TAG, "SAVE_DBG_DELTA $stageFrom→$stageTo SPK_CHANGE id=${f.segmentId.take(8)} from=${fs ?: "none"} to=${ts ?: "none"} text=\"${f.text.take(30)}\"")
            }
        }
        val summary = "SAVE_STAGE_DELTA $stageFrom→$stageTo: gone=${gone.size} added=${added.size} spkChanges=$spkChanges"
        TestLog.log(summary)
        Log.d(TAG, summary)
    }

    // Phase 8 (0.7.4): Post-Processing-Indikator (finaler Lauf + Save + Export)
    private fun startPostProcessingIndicator() {
        postProcessStartMs = System.currentTimeMillis()
        _uiState.update { it.copy(postProcessing = true, postProcessingElapsedSec = 0) }
        postProcessTicker?.cancel()
        postProcessTicker = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.update { it.copy(postProcessingElapsedSec = it.postProcessingElapsedSec + 1) }
            }
        }
    }

    private fun stopPostProcessingIndicator() {
        postProcessTicker?.cancel()
        postProcessTicker = null
        val durationMs = System.currentTimeMillis() - postProcessStartMs
        _uiState.update { it.copy(postProcessing = false, postProcessingElapsedSec = 0) }
        Log.i(TAG, "POSTPROCESS took=${durationMs}ms")
        TestLog.log("POSTPROCESS took=${durationMs}ms")
    }

    fun stopRecording() {
        isStopping = true
        cancelRecordingNotification()   // Phase 8: Benachrichtigung entfernen
        _uiState.update { it.copy(recordingState = RecordingState.Idle) }
        audioCapture.stopCapture(); engine.stopSession()
        // 0.6.23: EN-Engine ebenfalls beenden
        if (engineEn.isInitialized) engineEn.stopSession()
        // Foreground-Service beenden
        com.sherpa.transcript.service.RecordingService.stop(SherpaTranscriptApp.instance)
        logRecordPermissionState()

        viewModelScope.launch {
            Log.d(TAG, "stopRecording: cancel capture")
            captureJob?.cancel()
            captureJob?.join()
            captureJob = null

            Log.d(TAG, "stopRecording: cancel diarization loop")
            diarizationJob?.cancel()
            diarizationJob?.join()
            diarizationJob = null

            // Phase 8 (0.7.4): Nachbearbeitung sichtbar machen (finaler Lauf + Save)
            startPostProcessingIndicator()

            // Finaler Lauf: forceFinal=true passiert den Guard, weil !isSavingFinalResult
            Log.d(TAG, "stopRecording: final diarization")
            if (ENABLE_CHUNKED_DIARIZATION) runChunkedDiarization(forceFinal = true) else runDiarization(forceFinal = true)
            deriveUiSegments()

            // ── Phase 7: Auto-Enroll bestätigter Kontakte in die globale Bank ──
            // Nur CONFIRMED (2-Kontakt/Quick-Confirm-gehärtet) wandert in die
            // persistente Bank; pending verfällt. Läuft VOR isSavingFinalResult
            // und VOR dem Cleanup – der mechanische Wiedererkennungs-Vorteil
            // ohne jede Nutzer-Interaktion (Host-belegt: vorbank-A/B 95% korrekt).
            if (ENABLE_GLOBAL_VOICE_BANK) {
                val confirmed = sessionVoiceBank.confirmedVoiceprints()
                if (confirmed.isNotEmpty()) {
                    val res = globalVoiceBank.autoEnrollFrom(confirmed)
                    withContext(Dispatchers.IO) { SpeakerProfiles.save() }
                    Log.i(TAG, "VB_GLOBAL: autoEnroll merged=${res.mergedIds.size} new=${res.newIds.size} " +
                            "total=${globalVoiceBank.size} (Quelle: ${confirmed.size} bestätigte Kontakte)")
                    TestLog.log("VB_GLOBAL autoEnroll merged=${res.mergedIds.size} new=${res.newIds.size} total=${globalVoiceBank.size}")
                    refreshSpeakerProfiles()
                }
            }

            // Offenes Partial als finales Segment sichern, falls vorhanden
            livePartial?.let { partial ->
                if (partial.text.isNotBlank()) {
                    val flushed = partial.copy(isFinal = true, isNew = true)
                    if (!dedupeOrMergeIntoLastSegment(flushed.text, flushed.startTimeMs, flushed.endTimeMs)) {
                        rawFinalSegments.add(flushed)
                    }
                    livePartial = null
                    deriveUiSegments()
                    Log.d(TAG, "stopRecording: flushed partial to rawFinalSegments (#${rawFinalSegments.size}): \"${partial.text.take(40)}\"")
                }
            }

            // Ab hier Save-Phase – keine Commits mehr erlaubt
            isSavingFinalResult = true
            // 0.6.7: Das Overlay enthält auch raw-ASR-Segmente ohne Diarization-Overlay
            // (z.B. Ausklang nach dem letzten Diarization-Segment) – die über die
            // bestätigten Nachbarn gelabelt werden (Geräte-Befund 0.6.6:
            // "## Unbekannt · 00:01:34" im Export).
            val overlay = correctOverlayByVoiceBank(
                resolveListByNearestConfirmed(buildAssignedOverlayForAllRawSegments())
            )
            logSaveSpeakerStage("beforeSave overlay", overlay)
            // Segment-Splitting: lange ASR-Segmente an Diarization-Grenzen aufteilen
            val splitOverlay = if (lastDiarizationSegments.isNotEmpty()) {
                TimelineComposer.splitLongSpeakerSegments(overlay, lastDiarizationSegments)
            } else overlay
            logSaveSpeakerStage("afterSplit", splitOverlay)
            // Finale Konsolidierung (Post-Processing) für History
            val segmentsToSave = if (splitOverlay.size >= 3) {
                FinalTranscriptComposer.enrichAssignmentForSave(splitOverlay)
            } else {
                splitOverlay
            }
            logSaveSpeakerStage("afterEnrich", segmentsToSave)
            // DBG: Delta-Analyse – welche Segmente/Speaker verändert die Save-Pipeline?
            logSaveStageDelta("beforeSave", overlay, "afterSplit", splitOverlay)
            logSaveStageDelta("afterSplit", splitOverlay, "afterEnrich", segmentsToSave)
            val transcriptId = currentTranscriptId
            if (segmentsToSave.isNotEmpty() && transcriptId != null) {
                val labeledCount = segmentsToSave.count { !it.speakerId.isNullOrBlank() }
                val speakerCount = segmentsToSave.mapNotNull { it.speakerId }.distinct().size
                val sourceCount = rawFinalSegments.size
                Log.i(TAG, "stopRecording: saveSegments sourceCount=$sourceCount persistedCount=${segmentsToSave.size} ($labeledCount labeled, $speakerCount speakers, zeroSegments=${speakerEngine.zeroSegmentCount}, engineOrThreshold=${speakerEngine.engineOrThresholdCount})")
                TestLog.log("SAVE source=$sourceCount persisted=${segmentsToSave.size} labeled=$labeledCount speakers=$speakerCount " +
                        "zeroSegments=${speakerEngine.zeroSegmentCount} engineOrThreshold=${speakerEngine.engineOrThresholdCount}")
                if (_uiState.value.debugMode) {
                    Log.d(TAG, "LIVE_DBG_SAVE raw=$sourceCount labeled=$labeledCount speakers=$speakerCount persisted=${segmentsToSave.size}")
                }
                saveTranscript(transcriptId, segmentsToSave)
            } else {
                stopPostProcessingIndicator()   // nichts zu speichern → sofort fertig
            }
            currentTranscriptId = null; currentUtteranceStartMs = null; lastPartialText = ""; lastForcedFlushTime = 0L; lastForcedFlushText = ""

            // 0.6.16: Debug-Upload – alle Dateien zum Server senden (nach Save)
            if (_uiState.value.debugMode) triggerDebugUpload()
        }
    }

    /**
     * Phase 9 (0.9.0): Sprachnachricht/Audiodatei importieren und automatisch
     * transkribieren + diarizieren. Aufgerufen aus dem Share-Intent
     * (MainActivity → LiveViewModel).
     *
     * Zeitbasis: Die Engine/der Pipeline rechnet mit sessionRelativeMs()
     * (= wall-clock seit recordingStartedAt). Für den Offline-Feed führen wir
     * eine virtuelle Uhr: pro 100-ms-Frame wird recordingStartedAt so
     * nachgeführt, dass sessionRelativeMs() exakt die Audiodauer abbildet –
     * dadurch funktionieren handleResult/dedupe/diarization unverändert.
     */
    fun importAudio(uri: android.net.Uri, fileName: String) {
        // Phase 9b: Nur blockieren, wenn wirklich noch ein Import LÄUFT (0..99).
        // 100 = abgeschlossen (Banner wartet auf OK) → neuer Import erlaubt.
        val p = _uiState.value.importProgress
        if (p in 0..99) return
        _uiState.update { it.copy(importProgress = 0, importFileName = fileName) }
        // Phase 9c: Globales Banner (AppNavigation) hängt an der Activity-Instanz –
        // damit es den Fortschritt sieht, spiegeln wir ihn über die Bridge.
        com.sherpa.transcript.ui.live.ImportUiBridge.set(0, fileName)
        postImportNotification(fileName, -1)   // Phase 9b: sofort sichtbar
        viewModelScope.launch {
            try {
                // 1. Dekodieren (IO)
                val samples = withContext(Dispatchers.IO) {
                    AudioImportDecoder.decodeTo16kMono(SherpaTranscriptApp.instance, uri)
                }
                if (samples == null || samples.isEmpty()) {
                    _uiState.update { it.copy(importProgress = -1, importFileName = null,
                        error = "Audioformat konnte nicht dekodiert werden") }
                    return@launch
                }

                // 2. Modelle sicherstellen (gleicher Pfad wie startRecording)
                _uiState.update { it.copy(recordingState = RecordingState.Initializing) }
                if (!engine.isInitialized) {
                    downloadWithProgress("ASR", "Lade Spracherkennungsmodell…") {
                        val ctx = SherpaTranscriptApp.instance
                        if (!ModelDownloadManager.isModelDownloaded(ctx, ASR_MODEL)) ModelDownloadManager.downloadModel(ctx, ASR_MODEL) { done, total -> _uiState.update { it.copy(downloadProgress = if (total > 0) done.toFloat() / total else 0f) } }
                        else true
                    }
                    engine.initialize(ASR_MODEL); _uiState.update { it.copy(isModelReady = true) }
                }
                if (!speakerEngine.isInitialized) {
                    downloadWithProgress("Speaker", "Lade Sprechererkennung…") {
                        if (!SpeakerModelDownloadManager.areModelsDownloaded()) SpeakerModelDownloadManager.downloadModels { _, done, total -> _uiState.update { it.copy(downloadProgress = if (total > 0) done.toFloat() / total else 0f) } }
                        else true
                    }
                    speakerEngine.initialize(clusteringMode)
                }

                // 3. Session zurücksetzen (wie startRecording, ohne Mikrofon)
                engine.startSession()
                _uiState.update { it.copy(segments = emptyList(), latestSegmentId = null, recordingState = RecordingState.Listening) }
                currentTranscriptId = java.util.UUID.randomUUID().toString()
                recordingStartedAt = System.currentTimeMillis()
                currentLanguage = null; deHasFinalText = false; enHasFinalText = false
                rawFinalSegments.clear(); assignedFinalSegments = emptyList()
                bestAssignmentQuality = AssignmentQuality(0, 0, 0)
                livePartial = null; currentUtteranceStartMs = null
                lastPartialText = ""; lastForcedFlushTime = 0L; lastForcedFlushText = ""
                isStopping = false; isSavingFinalResult = false
                lastDiarizationSegments = emptyList(); lastGoodDiarizationCandidate = null
                lastShownSpeakerIds.clear(); firstTwoSpeakerLogged = false
                speakerEngine.resetZeroSegmentCounters()
                synchronized(audioLock) { audioAccumulator.clear(); audioBaseTimeMs = 0L }
                chunkedAudioBuffer.clear(); diarizationChunkWorker.reset(); sessionVoiceBank.reset()
                pushedSampleCountMs = 0L

                // 4. Offline-Feed: Frames à 100 ms in Echtzeit-Geschwindigkeit füttern.
                //    Virtuelle Uhr: recordingStartedAt wird pro Frame zurückgesetzt, damit
                //    sessionRelativeMs() == Audiozeit. ASR ist offline schneller als
                //    Echtzeit → wir drosseln auf ~1×, damit Timestamps konsistent bleiben.
                val frameSize = 1_600   // 100 ms @16 kHz
                val totalFrames = samples.size / frameSize
                TestLog.log("IMPORT start file=$fileName samples=${samples.size} frames=$totalFrames")
                for (f in 0 until totalFrames) {
                    val frame = samples.copyOfRange(f * frameSize, (f + 1) * frameSize)

                    // Virtuelle Uhr auf Frame-Start setzen
                    recordingStartedAt = System.currentTimeMillis() - (f * 100L)

                    val result = engine.processFrame(frame)
                    if (result != null && result.text.isNotBlank()) handleResult(result.text, result.isFinal)

                    synchronized(audioLock) {
                        audioAccumulator.addLast(frame)
                        if (audioAccumulator.isEmpty()) audioBaseTimeMs = 0L
                        if (audioAccumulator.size > MAX_AUDIO_FRAMES) { audioAccumulator.removeFirst(); /* base bleibt: virtuelle Uhr deckt das ab */ }
                        chunkedAudioBuffer.push(frame, pushedSampleCountMs)
                        pushedSampleCountMs += frame.size * 1000L / 16_000L
                    }

                    if (f % 10 == 0) {   // alle ~1 s Audio
                        val pct = (f * 100 / maxOf(totalFrames, 1)).coerceIn(0, 99)
                        _uiState.update { st -> st.copy(importProgress = pct) }
                        com.sherpa.transcript.ui.live.ImportUiBridge.set(pct, fileName)
                        postImportNotification(fileName, pct)   // Phase 9b: System-Notification
                        delay(50)   // leichte Drossel (ASR läuft sonst der Diarization davon)
                    }
                }
                // Rest-Samples (< 100 ms) verwerfen – vernachlässigbar

                // 5. Finalisieren = derselbe Pfad wie stopRecording (Diarization final,
                //    Auto-Enroll, Save). Wir rufen stopRecording NICHT auf (kein Capture),
                //    sondern replizieren nur den Finalisierungs-Kern:
                isStopping = true
                cancelRecordingNotification()
                _uiState.update { it.copy(recordingState = RecordingState.Idle) }
                engine.stopSession()

                viewModelScope.launch {
                    startPostProcessingIndicator()
                    runChunkedDiarization(forceFinal = true)
                    deriveUiSegments()

                    if (ENABLE_GLOBAL_VOICE_BANK) {
                        val confirmed = sessionVoiceBank.confirmedVoiceprints()
                        if (confirmed.isNotEmpty()) {
                            val res = globalVoiceBank.autoEnrollFrom(confirmed)
                            withContext(Dispatchers.IO) { SpeakerProfiles.save() }
                            refreshSpeakerProfiles()
                            Log.i(TAG, "VB_GLOBAL(import): autoEnroll merged=${res.mergedIds.size} new=${res.newIds.size}")
                            TestLog.log("VB_GLOBAL autoEnroll merged=${res.mergedIds.size} new=${res.newIds.size} total=${globalVoiceBank.size}")
                        }
                    }

                    livePartial?.let { partial ->
                        if (partial.text.isNotBlank()) {
                            val flushed = partial.copy(isFinal = true, isNew = true)
                            if (!dedupeOrMergeIntoLastSegment(flushed.text, flushed.startTimeMs, flushed.endTimeMs)) {
                                rawFinalSegments.add(flushed)
                            }
                            livePartial = null
                        }
                    }
                    deriveUiSegments()

                    isSavingFinalResult = true
                    val overlay = correctOverlayByVoiceBank(
                        resolveListByNearestConfirmed(buildAssignedOverlayForAllRawSegments())
                    )
                    val splitOverlay = if (lastDiarizationSegments.isNotEmpty()) {
                        TimelineComposer.splitLongSpeakerSegments(overlay, lastDiarizationSegments)
                    } else overlay
                    val segmentsToSave = if (splitOverlay.size >= 3) {
                        FinalTranscriptComposer.enrichAssignmentForSave(splitOverlay)
                    } else splitOverlay

                    val transcriptId = currentTranscriptId
                    if (segmentsToSave.isNotEmpty() && transcriptId != null) {
                        // Phase 9d: "fertig"-Anzeige + Notification erst NACH dem Save-Job
                        saveTranscript(transcriptId, segmentsToSave).invokeOnCompletion {
                            postImportDoneNotification(fileName)
                            // Phase 9d: Bridge auf 100 setzen → globales Banner zeigt
                            // "Benennen/Überspringen" (fehlte bisher → blau hängend)
                            com.sherpa.transcript.ui.live.ImportUiBridge.set(100, fileName)
                            // Phase 10c (0.10.0): Debug-Upload auch für Imports (vorher
                            // fehlte triggerDebugUpload → importierte Dateien landeten nie
                            // im Upload-Server)
                            if (_uiState.value.debugMode) triggerDebugUpload()
                        }
                    } else stopPostProcessingIndicator()

                    currentTranscriptId = null
                    _uiState.update { it.copy(importProgress = 100) }
                    TestLog.log("IMPORT fertig file=$fileName segs=${segmentsToSave.size}")
                }
            } catch (e: IllegalArgumentException) {
                cancelImportNotification()
                com.sherpa.transcript.ui.live.ImportUiBridge.dismiss()
                _uiState.update { it.copy(importProgress = -1, importFileName = null,
                    error = e.message ?: "Audiodatei ungültig") }
            } catch (t: Throwable) {
                Log.e(TAG, "importAudio fehlgeschlagen", t)
                TestLog.log("IMPORT FEHLER: ${t.message}")
                cancelImportNotification()
                com.sherpa.transcript.ui.live.ImportUiBridge.dismiss()
                _uiState.update { it.copy(importProgress = -1, importFileName = null,
                    error = "Import fehlgeschlagen: ${t.message}") }
            }
        }
    }

    private fun saveTranscript(transcriptId: String, segments: List<TranscriptSegment>): Job {
        return viewModelScope.launch { withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val durationMs = if (recordingStartedAt > 0) now - recordingStartedAt else 0L
            val firstText = segments.firstOrNull()?.text?.take(60)?.trim() ?: "Transkript"
            val title = if (firstText.length > 3) firstText else "Transkript vom ${java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
            val speakerCount = segments.mapNotNull { it.speakerId }.distinct().size
            val labeledCount = segments.count { !it.speakerId.isNullOrBlank() }
            val totalDurationMs = if (recordingStartedAt > 0) now - recordingStartedAt else segments.lastOrNull()?.endTimeMs ?: 0L
            val transcript = TranscriptEntity(transcriptId = transcriptId, title = title, durationMs = durationMs, speakerCount = speakerCount, createdAt = recordingStartedAt, updatedAt = now)
            val speakerNames = buildExportProfileNames()
            val segEntities = segments.mapIndexed { i, s -> SegmentEntity(segmentId = s.segmentId, transcriptId = transcriptId, startTimeMs = s.startTimeMs, endTimeMs = s.endTimeMs, text = s.text, speakerId = s.speakerId, speakerLabel = if (s.speakerId != null) (s.speakerLabel ?: "Sprecher 1") else null, speakerName = s.speakerLabel?.let { speakerNames[it] }, isFinal = true, sequenceIndex = i, createdAt = s.timestamp) }
            val entitySpeakerIds = segEntities.mapNotNull { it.speakerId }.distinct().map { it.removePrefix("speaker_") }.sorted()
            Log.i(TAG, "saveStage beforeEntityInsert: entities=${segEntities.size} labeled=${segEntities.count { it.speakerId != null }} speakers=${entitySpeakerIds.size} ids=[${entitySpeakerIds.joinToString(",")}]")
            repository.saveTranscriptWithSegments(transcript, segEntities)
            // 0.6.13: Markdown direkt ins Testaufnahmen-Verzeichnis legen (Debug-Modus) –
            // die .md liegt dann neben WAV+Log (getExternalFilesDir(DOWNLOADS)/testaufnahmen/)
            // für den direkten Upload bereit – kein Share/Umweg mehr nötig
            if (_uiState.value.debugMode) {
                try {
                    val md = TranscriptExporter.formatMarkdown(transcript, segEntities)
                    val base = SherpaTranscriptApp.instance.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (base != null) {
                        val dir = java.io.File(base, "testaufnahmen")
                        dir.mkdirs()
                        // 0.6.15: Dateiname an die WAV koppeln (testaufnahme_<ts>.md) –
                        // der Copy-Job (testaufnahme_*-Muster) nimmt die .md dann mit.
                        // 0.6.22: lastTestWavName statt currentTestWavFile – der
                        // wavFile-Zeiger ist nach stopCapture() bereits null (Bug:
                        // die .md wurde immer als transcript_<uuid>.md benannt und
                        // von der Upload-Session-Gruppierung übersprungen).
                        val mdName = (audioCapture.lastTestWavName
                            ?: audioCapture.currentTestWavFile?.name)
                            ?.replace(".wav", ".md") ?: "transcript_${transcriptId}.md"
                        val mdFile = java.io.File(dir, mdName)
                        mdFile.writeText(md)
                        TestLog.log("EXPORT_MD: $mdName geschrieben (${md.length} Zeichen)")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "EXPORT_MD: Markdown konnte nicht geschrieben werden: ${t.message}")
                }
            }
            Log.i(TAG, "Saved '$title' — ${segEntities.size} Segmente, ${labeledCount}/$speakerCount labeled, $speakerCount Sprecher, ${totalDurationMs / 1000}s Aufnahme")
            stopPostProcessingIndicator()   // Phase 8 (0.7.4): Save fertig → Anzeige aus
        }}
    }

    /**
     * 0.6.16: Automatischer Debug-Upload – sendet alle Dateien im testaufnahmen-
     * Verzeichnis (WAV, .log, .md) an den konfigurierten Upload-Server.
     * Läuft fire-and-forget im Hintergrund; Fehler werden geloggt aber nicht
     * dem User angezeigt (Upload ist optional).
     */
    private fun triggerDebugUpload() {
        viewModelScope.launch {
            try {
                // Kurze Pause: TestLog.close() + .md-Schreiben sind async –
                // 500ms reicht in der Praxis (io-Bound)
                delay(500)
                val ctx = SherpaTranscriptApp.instance
                val base = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dir = java.io.File(base, "testaufnahmen")
                if (!dir.exists()) {
                    Log.w(TAG, "DebugUpload: kein testaufnahmen-Ordner")
                    return@launch
                }
                val sessionId = currentTranscriptId ?: "session_${System.currentTimeMillis()}"
                val result = DebugUploadClient.uploadDebugBundle(
                    dir, sessionId, skipChunks = true
                )
                result.fold(
                    onSuccess = { Log.i(TAG, "DebugUpload: $it") },
                    onFailure = { Log.w(TAG, "DebugUpload fehlgeschlagen: ${it.message}") },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "DebugUpload Error: ${t.message}")
            }
        }
    }

    private fun handleResult(text: String, isFinal: Boolean) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) return
        val now = sessionRelativeMs()

        if (!isFinal) {
            // Dedup: identischen Partial-Text nicht erneut verarbeiten (Sicherheitsebene)
            if (normalizedText == lastPartialText) return
            if (currentUtteranceStartMs == null) currentUtteranceStartMs = now

            // Forced Final: Utterance länger als 6s → als finales Segment sichern
            val utteranceDuration = now - (currentUtteranceStartMs ?: now)
            if (utteranceDuration > 6000L) {
                val forcedFinal = TranscriptSegment(
                    text = cleanLeadingPunctuation(normalizedText),
                    startTimeMs = currentUtteranceStartMs ?: now,
                    endTimeMs = now,
                    isFinal = true,
                    isNew = true,
                )
                if (!dedupeOrMergeIntoLastSegment(forcedFinal.text, forcedFinal.startTimeMs, forcedFinal.endTimeMs)) {
                    rawFinalSegments.add(forcedFinal)
                    Log.d(TAG, "rawFinalSegments add (FORCED_FLUSH >6s): \"${normalizedText.take(40)}\" at ${currentUtteranceStartMs}ms-${now}ms (#${rawFinalSegments.size})")
                }
                lastForcedFlushTime = now
                lastForcedFlushText = normalizedText
                currentUtteranceStartMs = null
                lastPartialText = ""
                livePartial = null
                _uiState.update { it.copy(recordingState = RecordingState.Listening) }
                deriveUiSegments()
                return
            }

            lastPartialText = normalizedText
            livePartial = TranscriptSegment(
                text = normalizedText,
                startTimeMs = currentUtteranceStartMs ?: now,
                endTimeMs = now,
                isFinal = false,
                isNew = false,
            )
            deriveUiSegments()
            _uiState.update { it.copy(recordingState = RecordingState.Processing) }
            return
        }

        // Final
        val finalText = cleanLeadingPunctuation(normalizedText.ifBlank { lastPartialText })
        if (finalText.isBlank()) return
        val startMs = currentUtteranceStartMs ?: now
        lastForcedFlushTime = 0L; lastForcedFlushText = ""

        // 0-ms-Segmente auf Mindestdauer von 100ms clampen
        val effectiveEndMs = maxOf(now, startMs + 100L)

        // Zentrale Dedupe: gegen letztes rawFinalSegment prüfen
        if (!dedupeOrMergeIntoLastSegment(finalText, startMs, effectiveEndMs)) {
            val finalSeg = TranscriptSegment(
                text = finalText,
                startTimeMs = startMs,
                endTimeMs = effectiveEndMs,
                isFinal = true,
                isNew = true,
            )
            rawFinalSegments.add(finalSeg)
            val durationMs = effectiveEndMs - startMs
            Log.d(TAG, "rawFinalSegments add: \"${finalText.take(40)}\" at ${startMs}ms-${effectiveEndMs}ms (#${rawFinalSegments.size})" +
                    if (durationMs > 8000L) " LONG_SEGMENT=${durationMs}ms" else if (durationMs == 0L) " ZERO_MS" else "")
        }

        // Live zurücksetzen
        currentUtteranceStartMs = null
        lastPartialText = ""
        livePartial = null

        _uiState.update { it.copy(recordingState = RecordingState.Listening) }
        deriveUiSegments()
    }

    /**
     * Phase 7a (0.7.2): Anzeige-Label eines Segments über das globale Profil
     * auflösen („Anna" statt „Sprecher 1"). Nur Display! raw/assigned bleiben
     * unangetastet (Lossless-Persistenz).
     * @return null wenn kein Profil benannt ist → Fallback „Sprecher N".
     */
    private fun resolveDisplayLabel(assigned: TranscriptSegment): String? {
        val spkNum = assigned.speakerId?.removePrefix("speaker_")?.toIntOrNull() ?: return null
        val profileId = diarizationChunkWorker.globalProfileBySessionId()[spkNum] ?: return null
        return globalVoiceBank.displayLabel(profileId, fallbackIndex = spkNum)
    }

    /**
     * Phase 7a (0.7.2): Exporter-Namens-Map. Schlüssel = speakerLabel ("Sprecher N").
     * Hinweis: Die Abbildung über die (evtl. nach renumber umnummerierten)
     * Session-IDs ist für die üblichen Fälle (Profil-IDs 0..2, keine Lücken)
     * korrekt; bei exotischen Renumbers kann ein Segment ohne Namen bleiben.
     */
    private fun buildExportProfileNames(): Map<String, String> {
        return diarizationChunkWorker.globalProfileBySessionId().entries.mapNotNull { (sessionId, profileId) ->
            val name = globalVoiceBank.nameFor(profileId) ?: return@mapNotNull null
            "Sprecher ${sessionId + 1}" to name
        }.toMap()
    }

    /**
     * Phase 7a (0.7.2): Aktualisiert die Profil-Liste im UI-State (für
     * Namens-Overlay-Diagnose und den späteren Kontakte-/Zuweisungs-Screen).
     */
    private fun refreshSpeakerProfiles() {
        val profiles = globalVoiceBank.snapshot().map { SpeakerProfileUi(it.id, it.name, it.sampleCount) }
        _uiState.update {
            it.copy(
                speakerProfiles = profiles,
                sessionProfileMap = diarizationChunkWorker.globalProfileBySessionId(),
            )
        }
    }

    /**
     * Phase 7a (0.7.2): Weist ein ASR-Segment einem Profil zu und lernt die
     * Stimme aus dem Chunk-Puffer-Fenster ein (der Puffer lebt bis onCleared –
     * Zuweisung also direkt nach Stop, ohne WAV-Speicher).
     *
     * @param segmentId  UUID des ASR-Segments (rawFinalSegments)
     * @param profileId  Vorhandenes Profil, auf das zugewiesen wird (oder null)
     * @param newName    Optional: Name für das (ggf. neue) Profil
     */
    fun assignSpeakerToSegment(segmentId: String, profileId: String?, newName: String?) {
        val seg = rawFinalSegments.firstOrNull { it.segmentId == segmentId } ?: return
        val samples = chunkedAudioBuffer.readWindow(seg.startTimeMs, seg.endTimeMs)
        if (samples.size < (2 * 16000)) {
            Log.w(TAG, "assignSpeaker: Segment ${segmentId.take(8)} hat < 2s Audio im Puffer – übersprungen")
            return
        }
        // Ziel-Profil: explizit gewählt → vorhandenes oder aus Samples identifiziert;
        // sonst: bekannte Stimme weiterverwenden, unbekannt → neues Profil
        val targetId = when {
            profileId != null -> profileId
            else -> globalVoiceBank.identifySamples(samples)
                ?: java.util.UUID.randomUUID().toString()
        }
        if (globalVoiceBank.enrollFromSamples(targetId, samples)) {
            if (newName != null) globalVoiceBank.rename(targetId, newName)
            // Phase 9e-fix2 (0.9.6): Session-GID SOFORT dem Profil zuordnen.
            // WICHTIG: Die GID steht im ASSIGNED-Overlay, nicht im raw-Segment
            // (raw hat keine speakerId – 3-Schichten-Architektur!). Der Fix aus
            // 0.9.5 las raw lesen → gid=null → wirkungslos.
            val gid = (assignedFinalSegments.firstOrNull { it.segmentId == segmentId }?.speakerId
                ?: seg.speakerId)
                ?.removePrefix("speaker_")?.toIntOrNull()
            if (gid != null) {
                diarizationChunkWorker.registerProfileMapping(gid, targetId)
                Log.i(TAG, "VB_GLOBAL_ASSIGN mapping session=$gid → profil=${targetId.takeLast(8)}")
                TestLog.log("VB_GLOBAL_MAPPING session=$gid profil=${targetId.takeLast(8)}")
            } else {
                Log.w(TAG, "VB_GLOBAL_ASSIGN: keine Session-GID für ${segmentId.take(8)} ermittelbar")
            }
            SpeakerProfiles.save()
            refreshSpeakerProfiles()
            deriveUiSegments()
            Log.i(TAG, "VB_GLOBAL_ASSIGN segment=${segmentId.take(8)} → profil=${targetId.takeLast(8)} " +
                    "name=${newName ?: "-"} (${samples.size / 16000}s Audio)")
            TestLog.log("VB_GLOBAL_ASSIGN segment=${segmentId.take(8)} → profil=${targetId.takeLast(8)} name=${newName ?: "-"}")
        } else {
            Log.w(TAG, "assignSpeaker: Enroll fehlgeschlagen (Embedding-Fehler?) für ${targetId.takeLast(8)}")
        }
    }

    /**
     * 0.6.19: Führende Satzzeichen entfernen, die durch ASR-Segmentierung entstehen.
     * Die Endpoint-Segmentierung lässt den Satzzeichen am Ende weg und das
     * nächste Segment beginnt mit ".", "?", "!" etc.
     */
    private fun cleanLeadingPunctuation(text: String): String {
        var s = text.trimStart()
        while (s.isNotEmpty() && s[0] in ".,;:!?'\"") {
            s = s.substring(1).trimStart()
        }
        return s.ifBlank { text.trim() }
    }

    private fun sessionRelativeMs(): Long = System.currentTimeMillis() - recordingStartedAt
    fun onUserScroll() { _uiState.update { it.copy(autoScrollEnabled = false) } }
    fun onScrollToLatest() { _uiState.update { it.copy(autoScrollEnabled = true) } }

    /**
     * Phase 8 (0.7.3): Display-Wach-Toggle (kein Stromsparmodus während der
     * Aufnahme). Nur STATE – das Window-Flag setzt die UI (LaunchedEffect),
     * weil das ViewModel keinen Activity-Zugriff hat.
     */
    fun toggleKeepScreenOn() {
        _uiState.update { it.copy(keepScreenOn = !it.keepScreenOn) }
    }

    /** Phase 9b (0.9.2): "Import abgeschlossen"-Banner schließen. */
    fun dismissImportBanner() {
        _uiState.update { it.copy(importProgress = -1, importFileName = null) }
    }
    fun onFontSizeChanged(newSize: Float) {
        _uiState.update { it.copy(fontSize = newSize) }
        // Phase 5 (0.6.8): persistent speichern (Flow aktualisiert andere Screens)
        SettingsStore.current.setFontSize(newSize)
    }

    override fun onCleared() {
        super.onCleared()
        cancelRecordingNotification()
        RecordingBridge.current = null
        captureJob?.cancel(); diarizationJob?.cancel()
        audioCapture.stopCapture(); engine.release(); speakerEngine.release()
        // 0.6.23: EN-Engine ebenfalls freigeben (Auto-Detection)
        engineEn.release()
        if (ENABLE_CHUNKED_DIARIZATION) {
            chunkedAudioBuffer.clear()
            diarizationChunkWorker.reset()
            sessionVoiceBank.reset()
        }
    }
}
