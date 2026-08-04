# Sherpa Transcript — Android

100% offline Speech-to-Text App für Android mit **Sherpa-ONNX** + **Jetpack Compose** + **Kotlin**.

Live-Transkription auf dem Gerät inkl. **Speaker Diarization** – keine Cloud, kein Netzwerk.

## Features (Stand 0.6.0)

- ✅ Live-ASR: Sherpa-ONNX OnlineRecognizer (Streaming), Kroko Zipformer-Transducer (Deutsch)
- ✅ **Speaker Diarization**: ReVerb v1 (Segmentation) + NeMo Titanet Small (Embedding), offline auf dem Gerät
- ✅ Rolling-Diarization: 15s-Chunk-Pipeline mit Overlap, Reconciler (temporales Voting) + **SessionVoiceBank** (akustisches Gedächtnis gegen Engine-Drift, 2-Kontakt-Härtung)
- ✅ 2-Speaker-Trennung auf Mikrofon-Aufnahmen (Referenz-Testclip: Wechsel bei ~1:02, alle Segmente gelabelt)
- ✅ 3-Schichten-Architektur: `rawFinalSegments` (Ground Truth) → `assignedFinalSegments` (Speaker-Overlay) → `displaySegments` (UI-Merge)
- ✅ Leading-Resolve im Final: führende unbestätigte/unlabeled Segmente → erster bestätigter Sprecher
- ✅ Debug-Mode: Testaufnahme als Roh-WAV + Diagnose-Log-Datei (`TestLog`) für Host-Analyse (Xiaomi-logcat ist unbrauchbar)
- ✅ `scripts/host-test/`: Python-Pipeline-Simulation (exakt App-Konfiguration) für A/B-Analysen

## Architektur

```
UI Layer   →   Domain Layer   →   Engine (JNI/Native)
─────────       ───────────       ───────────────────
Compose         AudioCapture      Sherpa-ONNX C++
ViewModel       Diarization       ONNX Runtime
Navigation      VoiceBank         Zipformer-Transducer
                ChunkedBuffer     Pyannote + Titanet
```

## Phasen

| Phase | Feature | Status |
|---|---|---|
| **Phase 1** | Live-Transkription (MVP) | ✅ |
| **Phase 3** | Speaker Diarization (2 Sprecher) | ✅ (0.6.0) |
| **Phase 2** | Room-Datenbank, HistoryScreen | offen |
| **Phase 4** | Export: TXT, Markdown, JSON, ShareSheet | offen |
| **Phase 5** | Einstellungen, Dark Mode, Modellwahl, Privacy | offen |

## Voraussetzungen

- Android Studio Ladybug (2024.3+) oder neuer
- Gradle 8.10+
- Android SDK 35
- JDK 17
- Ein Android-Gerät mit Mikrofon (API 26+)

## Einrichtung

1. **Projekt klonen**

   ```bash
   git clone <repo>
   cd sherpa-transcript
   ```

2. **Gradle Wrapper generieren**

   ```bash
   gradle wrapper --gradle-version 8.10.2
   ```

3. **Modelle** (liegen in `app/src/main/assets/` oder werden per App bei Erststart geladen):
   - ASR: `sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06`
   - Diarization: `segmentation.onnx` (ReVerb v1), `embedding.onnx` (NeMo Titanet Small)

4. **In Android Studio öffnen**:
   - `File → Open → /path/to/sherpa-transcript`
   - Sync Gradle
   - Auf Gerät ausführen (Emulator: Mikrofon via `adb emu avd hostmicon` aktivieren)

## Projektstruktur

```
app/
├── src/main/java/com/sherpa/transcript/
│   ├── SherpaTranscriptApp.kt        # Application
│   ├── MainActivity.kt               # Entry + Permission
│   ├── domain/
│   │   ├── audio/
│   │   │   ├── AudioCaptureManager.kt  # AudioRecord-Capture (+ Channel-Puffer, WAV-Debug)
│   │   │   ├── ChunkedAudioBuffer.kt   # Rolling-Chunk-Quelle (15s + Overlap)
│   │   │   └── TestLog.kt              # Diagnose-Log-Datei (Debug-Mode)
│   │   └── model/
│   │       └── TranscriptSegment.kt    # Datenmodell
│   ├── engine/
│   │   ├── SherpaOnnxEngine.kt         # ASR-Engine (JNI)
│   │   ├── SpeakerDiarizationEngine.kt # Diarization (ReVerb + Titanet)
│   │   ├── DiarizationChunkWorker.kt   # Chunk-Pipeline (normalize, Retry, Reconciler)
│   │   ├── RollingReconciler.kt        # Temporales ID-Matching über Overlap-Zone
│   │   ├── SessionVoiceBank.kt         # Akustisches Gedächtnis (Voiceprints)
│   │   ├── TimelineComposer.kt         # Segment-Zusammenführung
│   │   ├── ModelDownloadManager.kt     # HF-Download
│   │   └── util/
│   │       └── ModelCopier.kt          # Asset-Kopierer
│   └── ui/
│       ├── navigation/AppNavigation.kt # Bottom Nav
│       ├── theme/                      # Material 3 Theme
│       └── live/                       # LiveScreen + VM
└── libs/
    └── sherpa-onnx-1.13.4.aar         # Native SDK

scripts/host-test/                      # Python-Simulation (A/B-Analyse)
├── run_asr.py                          # ASR mit Kroko-Modell
├── run_pipeline.py                     # Vollständige Diarization-Pipeline (1:1 App-Logik)
├── compare_words.py                    # WER-Vergleich gegen Referenz
├── REFERENZ_TEST.md                    # Testclip-Referenz (Di._07.52)
└── README.md                           # Anleitung
```

## Lizenz

MIT
