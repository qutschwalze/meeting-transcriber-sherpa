# Sherpa Transcript — Android

100% offline Speech-to-Text App für Android mit **Sherpa-ONNX** + **Jetpack Compose** + **Kotlin**.

Live-Transkription auf dem Gerät inkl. **Speaker Diarization** + **persistenter Speaker-Datenbank** – keine Cloud, kein Netzwerk.

## Features (Stand 0.8.0)

- ✅ Live-ASR: Sherpa-ONNX OnlineRecognizer (Streaming), Kroko Zipformer-Transducer (Deutsch)
- ✅ **Speaker Diarization**: ReVerb v1 (Segmentation) + ERes2Net (Embedding, seit 0.6.12 für 3+ Sprecher), offline auf dem Gerät
- ✅ Rolling-Diarization: 15s-Chunk-Pipeline mit Overlap, Reconciler (temporales Voting) + **SessionVoiceBank** (akustisches Gedächtnis gegen Engine-Drift, 2-Kontakt-Härtung)
- ✅ **Drift-Vorprüfung** (0.6.20): Phantom-Speaker durch ID-Drift über Chunk-Grenzen werden nicht mehr fälschlich bestätigt (`VB_DRIFT_ABFANG` – host-verifiziert 7→4 bestätigte bei 4 realen Stimmen)
- ✅ **Unlabeled-Auflösung** (0.6.23): "Unbekannt"-Blöcke zwischen verschiedenen Speakern werden akustisch aufgelöst (`VB_RESOLVE_UNLABELED`, confirmed-only 0.62)
- ✅ 2–4-Sprecher-Trennung auf Mikrofon-/Meeting-Aufnahmen; unlabeled-Anteil <15% (Testlauf 0.6.22)
- ✅ **ASR-Sprachmodus** (0.6.24): Deutsch Standard (`DE_ONLY`), optional "Deutsch + Englisch" in den Einstellungen (lädt EN-Zipformer ~38 MB, Auto-Detection in 3s, gewinnende Engine gewinnt)
- ✅ 3-Schichten-Architektur: `rawFinalSegments` (Ground Truth) → `assignedFinalSegments` (Speaker-Overlay) → `displaySegments` (UI-Merge)
- ✅ Leading-Resolve im Final: führende unbestätigte/unlabeled Segmente → erster bestätigter Sprecher
- ✅ Text-Bereinigung (0.6.19): keine führenden Satzzeichen am Segmentanfang; Ein-Sprecher-Modus merge großzügiger (Pause bis 5s)
- ✅ Debug-Mode: Testaufnahme als Roh-WAV + Diagnose-Log-Datei (`TestLog`) für Host-Analyse (Xiaomi-logcat ist unbrauchbar)
- ✅ **Debug-Upload-Server** (0.6.19): Flask-Server (`debug-server/`, Port 8520) – WAV/Log/Markdown direkt vom Gerät hochladen, kein adb nötig; Web-Dashboard mit Sortierung + Löschen (Datei/Session); automatischer Upload nach jeder Aufnahme im Debug-Modus
- ✅ `scripts/host-test/`: Python-Pipeline-Simulation (exakt App-Konfiguration) für A/B-Analysen + Host-Analyse-Skripte (`analyze_unknown_speaker.py`, `timeline_compare.py`)
- ✅ Export (0.6.2): Detail-Screen → Share-Icon → TXT / Markdown / JSON (Referenz-Stil mit Sprecherblöcken, ShareSheet via FileProvider)
- ✅ Room-Datenbank (0.6.6): SQLite statt JSON-Datei-Store – schnelle Metadaten-Queries (App-Start/Verlauf skalieren), einmalige JSON→SQLite-Migration, JSON-Dateien bleiben als Backup
- ✅ **Persistente Speaker-DB** (Phase 7, 0.7.0): Geräteweite Stimmen-Fingerprints (`GlobalVoiceBank` + JSON-Store in `filesDir/`) – bestätigte Kontakte werden automatisch am Session-Ende eingelernt (Auto-Enroll, 0.62-Schwelle confirmed-only) und in künftigen Aufnahmen **ab dem ersten Chunk wiedererkannt** – ganz ohne Namenszuweisung
- ✅ **Namens-UI** (Phase 7a, 0.7.x): Nach dem Stoppen Segment antippen → Profil zuweisen oder „Neuer Kontakt" benennen (ENROLL aus dem Chunk-Puffer, kein WAV-Speicher); Namen erscheinen live, in History und Export (`## Anna`)
- ✅ **Kontakt-Verwaltung** (0.7.2): Einstellungen → Profile umbenennen, zusammenführen (sample-gewichtet), löschen – zentrale `SpeakerProfiles`-Instanz, sofort persistiert
- ✅ Geräte-verifiziert: Politik-Podcast 3/3 Sprecher über Sessions wiedererkannt (0.7.1); Duo-Podcast mit sehr ähnlichen Stimmen ist dokumentierter Grenzfall (Inter-Sim > 0.62)
- ✅ **Aufnahme-Benachrichtigung** (0.7.4): Persistente Notification während der Aufnahme mit Aktionen **Stop** und **scr** – Bedienung auch ohne App im Vordergrund
- ✅ **Display-Wach-Toggle** (0.7.3): dezenter `scr`-Button im Live-Screen (`FLAG_KEEP_SCREEN_ON`) – kein Stromsparmodus während der Aufnahme
- ✅ **Nachbearbeitungs-Anzeige** (0.7.4): Nach dem Stoppen sichtbar, ob/wie lange der finale Diarization-Lauf + Save noch dauert (+ `POSTPROCESS took`-Logzeile)
- ✅ **Export-Namen aus der History** (0.7.4): `segments.speakerName` (Room-Migration v2) – auch der Share-Export zeigt `## Koschi`; alte Transkripte bleiben „Sprecher N"
- ✅ **Stabile Sprecherfarben** (0.7.5): Farb-Key = Profil-UUID – dieselbe Person behält über alle Aufnahmen dieselbe Farbe
- ✅ **Suche über Namen + Texte** (0.7.5): Verlaufs-Suche durchsucht Titel, Segmenttexte und Sprecher-Namen („Koschi" listet alle seine Aufnahmen)

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
| **Phase 4** | Export: TXT, Markdown, JSON + ShareSheet | ✅ (0.6.2) |
| **Phase 5** | History/Detail-Screen + **Room-Datenbank** (0.6.6: JSON-Store abgelöst, einmalige JSON→SQLite-Migration) | ✅ |
| **Phase 6** | Einstellungen: Dark Mode (System/hell/dunkel), Schriftgröße persistent, Debug-Schalter, Modell-Info (0.6.8) – Modellwahl/Privacy offen | ✅ (0.6.8) |
| **Phase 7** | **Persistente Speaker-DB**: `SpeakerProfileStore` (JSON, atomar), `GlobalVoiceBank` (0.62 confirmed-only, rolling average), Auto-Enroll beim Stoppen, Worker-Integration (`VB_GLOBAL_RESOLVE`/`VB_GLOBAL_LEARN`) | ✅ (0.7.0/0.7.1) |
| **Phase 7a** | **Namens-UI + Kontakt-Verwaltung**: Zuweisung nach Stop (Sample-Fenster aus Chunk-Puffer), Namens-Overlay (Live/Detail), Export mit Namen, Umbenennen/Zusammenführen/Löschen | ✅ (0.7.2) |
| **Phase 8** | **Komfort & Sichtbarkeit**: Aufnahme-Notification mit Stop/scr-Aktionen, Display-Wach-Toggle (`scr`), Nachbearbeitungs-Anzeige nach dem Stoppen (+`POSTPROCESS`-Log), Export-Namen aus der History (`speakerName`, Room v2) | ✅ (0.8.0) |
| **Phase 8+** | **Konsistenz**: Stabile Sprecherfarben über Sessions (Profil-UUID als Farb-Key), Suche über Sprecher-Namen + Segmenttexte | ✅ (0.8.0) |

**Downloads:** Aktuelle signierte APKs gibt es unter [Releases](https://github.com/qutschwalze/meeting-transcriber-sherpa/releases/latest) (`app-release.apk` für den normalen Betrieb, `app-debug.apk` mit Debug-Upload/TestLog für Diagnose).

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
│   ├── data/local/
│   │   ├── SpeakerProfile.kt            # Persistiertes Sprecher-Profil (UUID, Embedding, Name)
│   │   └── SpeakerProfileStore.kt       # JSON-Persistenz (atomar, Version 2, korruptionssicher)
│   ├── domain/
│   │   ├── audio/
│   │   │   ├── AudioCaptureManager.kt  # AudioRecord-Capture (+ Channel-Puffer, WAV-Debug)
│   │   │   ├── ChunkedAudioBuffer.kt   # Rolling-Chunk-Quelle (15s + Overlap, readWindow für ENROLL)
│   │   │   └── TestLog.kt              # Diagnose-Log-Datei (Debug-Mode)
│   │   └── model/
│   │       └── TranscriptSegment.kt    # Datenmodell
│   ├── engine/
│   │   ├── SherpaOnnxEngine.kt         # ASR-Engine (JNI)
│   │   ├── SpeakerDiarizationEngine.kt # Diarization (ReVerb + ERes2Net)
│   │   ├── DiarizationChunkWorker.kt   # Chunk-Pipeline (normalize, Retry, Reconciler, Voice-Banks)
│   │   ├── RollingReconciler.kt        # Temporales ID-Matching über Overlap-Zone
│   │   ├── SessionVoiceBank.kt         # Akustisches Session-Gedächtnis (Voiceprints)
│   │   ├── GlobalVoiceBank.kt          # Persistente Speaker-DB (0.62 confirmed-only, rolling average, Namen)
│   │   ├── SpeakerProfiles.kt          # Zentrale Bank-Instanz (Live + Settings teilen den Stand)
│   │   ├── TimelineComposer.kt         # Segment-Zusammenführung
│   │   ├── ModelDownloadManager.kt     # HF-Download
│   │   └── util/
│   │       └── ModelCopier.kt          # Asset-Kopierer
│   └── ui/
│       ├── navigation/AppNavigation.kt # Bottom Nav
│       ├── theme/                      # Material 3 Theme
│       ├── live/                       # LiveScreen + VM (+ AssignSpeakerSheet: Sprecher-Zuweisung)
│       └── settings/                   # Einstellungen (+ ContactsSection: Profil-Verwaltung)
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
