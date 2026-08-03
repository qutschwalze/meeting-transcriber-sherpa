# Sherpa Transcript — Android

100% offline Speech-to-Text App für Android mit **Sherpa-ONNX** + **Jetpack Compose** + **Kotlin**.

Live-Transkription auf dem Gerät, keine Cloud, kein Netzwerk.

## Architektur

```
UI Layer   →   Domain Layer   →   Engine (JNI/Native)
─────────       ───────────       ───────────────────
Compose         AudioCapture      Sherpa-ONNX C++
ViewModel       Transcription     ONNX Runtime
Navigation      Model Download    Zipformer-Transducer
                Ringbuffer
```

## Phase 1 – Live-Transkription (MVP)

- ✅ Kotlin + Jetpack Compose + Material 3
- ✅ AudioRecord Mikrofon-Capture (16kHz, Mono, 16bit PCM)
- ✅ Sherpa-ONNX OnlineRecognizer (Streaming ASR)
- ✅ Kroko Zipformer-Transducer — Deutsch (Banafo)
- ✅ Endpoint-basierte Segmentierung
- ✅ LiveScreen mit Auto-Scroll / Pause bei User-Scroll
- ✅ Schriftgrößenregler (12-28sp, sofort wirksam)
- ✅ Start/Stop-Aufnahme
- ✅ Finale/nicht-finale Textdarstellung (kursiv/schwarz)
- ✅ Status-Anzeige (Hört zu / Verarbeitet)
- ✅ Modell-Download bei Erststart via HuggingFace

## Nächste Phasen

| Phase | Feature |
|---|---|
| **Phase 2** | Room-Datenbank, HistoryScreen |
| **Phase 3** | Speaker Diarization (ECAPA-TDNN) |
| **Phase 4** | Export: TXT, Markdown, JSON, ShareSheet |
| **Phase 5** | Einstellungen, Dark Mode, Modellwahl, Privacy |

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

3. **Modell herunterladen** (oder per App bei Erststart)

   ```bash
   # Option A: Automatisch beim ersten Start der App
   # (App zeigt Download-Fortschritt an)

   # Option B: Manuell ins assets-Verzeichnis
   bash scripts/download-model.sh
   ```

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
│   │   │   ├── AudioCaptureManager.kt  # AudioRecord-Capture
│   │   │   └── AudioRingBuffer.kt      # Lockfreier Puffer
│   │   └── model/
│   │       └── TranscriptSegment.kt    # Datenmodell
│   ├── engine/
│   │   ├── SherpaOnnxEngine.kt         # ASR-Engine (JNI)
│   │   ├── ModelDownloadManager.kt     # HF-Download
│   │   └── util/
│   │       └── ModelCopier.kt          # Asset-Kopierer
│   └── ui/
│       ├── navigation/AppNavigation.kt # Bottom Nav
│       ├── theme/                      # Material 3 Theme
│       └── live/                       # LiveScreen + VM
└── libs/
    └── sherpa-onnx-1.13.4.aar         # Native SDK
```

## Lizenz

MIT
