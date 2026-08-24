# Sherpa Transcript — Android

100% offline speech-to-text app for Android built on **Sherpa-ONNX**, **Jetpack Compose** and **Kotlin**.

On-device live transcription including **speaker diarization** and a **persistent speaker database** — no cloud, no network.

**Sprache / Language:** [English](#english) · [Deutsch](#deutsch)

---

<a name="english"></a>
## English

### Features (as of 0.9.6)

- Live ASR: Sherpa-ONNX OnlineRecognizer (streaming), Kroko Zipformer transducer (German)
- Speaker diarization: ReVerb v1 segmentation + ERes2Net embeddings (3+ speakers since 0.6.12), fully offline
- Rolling diarization: 15 s chunk pipeline with overlap, rolling reconciler (temporal voting) + SessionVoiceBank (acoustic memory against engine drift)
- Drift pre-check (0.6.20): phantom speakers from ID drift across chunk boundaries are no longer confirmed (`VB_DRIFT_ABFANG`, host-verified 7→4 confirmed for 4 real voices)
- Unlabeled resolution (0.6.23): unknown blocks between different speakers are resolved acoustically (`VB_RESOLVE_UNLABELED`, confirmed-only 0.62)
- 2–4 speaker separation on microphone/meeting recordings
- ASR language mode (0.6.24): German default (`DE_ONLY`), optional German + English (loads EN zipformer ~38 MB, auto-detection after 3 s)
- Three-layer architecture: `rawFinalSegments` (ground truth) → `assignedFinalSegments` (speaker overlay) → `displaySegments` (UI merge)
- Text cleanup: no leading punctuation at segment starts; generous merging in single-speaker mode
- Export (0.6.2): TXT / Markdown / JSON via share sheet with speaker blocks (FileProvider)
- Room database (0.6.6): SQLite instead of JSON store, one-time JSON→SQLite migration, JSON kept as backup
- Debug mode: raw WAV recording + diagnostic log (`TestLog`) per test recording; Flask upload server (`debug-server/`, port 8520) with web dashboard — no adb needed
- Host analysis scripts: `scripts/host-test/` Python pipeline simulation matching the app configuration exactly
- **Persistent speaker DB** (Phase 7, 0.7.0): device-wide voice fingerprints (`GlobalVoiceBank`, JSON store in `filesDir/`). Confirmed contacts are auto-enrolled at session end (confirmed-only 0.62 threshold) and re-recognized **from the first chunk** in any future recording — no manual assignment required
- **Naming UI** (Phase 7a): tap a segment after stopping → assign a profile or create a new contact (acoustic ENROLL from the chunk buffer, no WAV storage); names appear live, in history and in exports
- **Contact management** (0.7.2): rename, merge (sample-weighted), delete profiles in settings
- **Recording notification** (0.7.4): persistent notification with Stop and screen-wake actions while recording
- **Screen-wake toggle** (0.7.3): discreet `scr` button in the live screen (`FLAG_KEEP_SCREEN_ON`)
- **Post-processing indicator** (0.7.4): after stopping, shows whether/how long the final diarization pass + save takes
- **Export names from history** (0.7.4): `segments.speakerName` (Room migration v2) — shared exports show real names too
- **Stable speaker colors** (0.7.5): color key = profile UUID, so the same person keeps the same color across recordings
- **Search across names + texts** (0.7.5): history search covers titles, segment texts and speaker names
- **Voice message import** (Phase 9, 0.9.0): share audio from WhatsApp/Telegram etc. → automatic transcription (MediaCodec decoder for Opus/M4A/MP3/AMR, 30 min limit), runs through the same diarization + speaker-DB pipeline as recordings
- **Rename speakers afterwards** (Phase 9a, 0.9.1): tap a speaker label in the detail screen → name applies to all segments of that label (display + export); works for old/imported transcripts without audio access
- **Visible import** (Phase 9b, 0.9.2): global banner above all tabs + system notification with progress while transcribing
- **Import on the live screen** (Phase 9c, 0.9.3): the imported transcript appears directly in the live tab; completion banner offers **Name** (jumps to segments → acoustic enroll from the imported audio → fingerprint) or **Skip**
- **Import fixes** (Phase 9d–f, 0.9.4–0.9.6): single progress indicator only, reliable completion state, instant fingerprint naming (session→profile mapping registered immediately)

Device-verified: politics podcast 3/3 speakers re-recognized across sessions (0.7.1). Duo podcasts with two very similar male voices remain a documented acoustic edge case.

### Architecture

```
UI Layer   →   Domain Layer   →   Engine (JNI/Native)
─────────       ───────────       ───────────────────
Compose         AudioCapture      Sherpa-ONNX C++
ViewModel       Diarization       ONNX Runtime
Navigation      VoiceBank         Zipformer transducer
                ChunkedBuffer     Pyannote + ERes2Net
```

### Phases

| Phase | Feature | Status |
|---|---|---|
| Phase 1 | Live transcription (MVP) | done |
| Phase 3 | Speaker diarization (2 speakers) | done (0.6.0) |
| Phase 4 | Export: TXT, Markdown, JSON + share sheet | done (0.6.2) |
| Phase 5 | History/detail screen + Room database | done (0.6.8) |
| Phase 6 | Settings: dark mode, font size, debug switch, model info | done (0.6.8) |
| Phase 7 | Persistent speaker DB: store, GlobalVoiceBank, auto-enroll, worker integration | done (0.7.0/0.7.1) |
| Phase 7a | Naming UI + contact management | done (0.7.2) |
| Phase 8 | Comfort & visibility: notification, screen wake, post-processing indicator, export names | done (0.8.0) |
| Phase 8+ | Consistency: stable speaker colors, search over names/texts | done (0.8.0) |
| Phase 9 | Share voice message → transcribe: share intent, MediaCodec decoder, offline feed | done (0.9.0) |
| Phase 9a | Rename speakers afterwards in detail screen | done (0.9.1) |
| Phase 9b | Visible import: global banner + system notification | done (0.9.2) |
| Phase 9c | Import on the live screen + Name/Skip banner | done (0.9.3) |
| Phase 9d–f | Import fixes: single progress bar, instant fingerprint naming | done (0.9.4–0.9.6) |

### Downloads

Current signed APKs are available under [Releases](https://github.com/qutschwalze/meeting-transcriber-sherpa/releases/latest) (`app-release.apk` for regular use, `app-debug.apk` with diagnostic upload/test log).

### Requirements

- Android Studio Ladybug (2024.3+) or newer
- Gradle 8.10+
- Android SDK 35
- JDK 17
- An Android device with a microphone (API 26+)

### Setup

1. Clone the project

   ```bash
   git clone <repo>
   cd sherpa-transcript
   ```

2. Generate the Gradle wrapper

   ```bash
   gradle wrapper --gradle-version 8.10.2
   ```

3. Models (bundled in `app/src/main/assets/` or downloaded on first app start):
   - ASR: `sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06`
   - Diarization: `segmentation.onnx` (ReVerb v1), `embedding.onnx` (ERes2Net)

4. Open in Android Studio: `File → Open`, sync Gradle, run on device
   (emulator: enable microphone via `adb emu avd hostmic`)

### Privacy

All processing happens on the device. Voice profiles are biometric data and stay in the app's private storage (`filesDir/`) — they are never included in debug uploads. Debug uploads (opt-in) contain test recordings and logs only.

---

<a name="deutsch"></a>
## Deutsch

100 % offline Speech-to-Text App für Android mit **Sherpa-ONNX**, **Jetpack Compose** und **Kotlin**.

Live-Transkription auf dem Gerät inkl. **Speaker Diarization** und **persistenter Speaker-Datenbank** – keine Cloud, kein Netzwerk.

### Features (Stand 0.9.8)

- Live-ASR: Sherpa-ONNX OnlineRecognizer (Streaming), Kroko-Zipformer-Transducer (Deutsch)
- Speaker Diarization: ReVerb v1 (Segmentation) + ERes2Net (Embedding, seit 0.6.12 für 3+ Sprecher), komplett offline
- Rolling-Diarization: 15s-Chunk-Pipeline mit Overlap, Reconciler (temporales Voting) + SessionVoiceBank (akustisches Gedächtnis gegen Engine-Drift)
- Drift-Vorprüfung (0.6.20): Phantom-Speaker durch ID-Drift über Chunk-Grenzen werden nicht mehr fälschlich bestätigt (`VB_DRIFT_ABFANG`, host-verifiziert 7→4 bestätigte bei 4 realen Stimmen)
- Unlabeled-Auflösung (0.6.23): „Unbekannt"-Blöcke zwischen verschiedenen Speakern werden akustisch aufgelöst (`VB_RESOLVE_UNLABELED`, confirmed-only 0.62)
- 2–4-Sprecher-Trennung auf Mikrofon-/Meeting-Aufnahmen
- ASR-Sprachmodus (0.6.24): Deutsch als Standard (`DE_ONLY`), optional Deutsch + Englisch (lädt EN-Zipformer ~38 MB, Auto-Detection nach 3 s)
- 3-Schichten-Architektur: `rawFinalSegments` (Ground Truth) → `assignedFinalSegments` (Speaker-Overlay) → `displaySegments` (UI-Merge)
- Text-Bereinigung: keine führenden Satzzeichen am Segmentanfang; großzügigeres Merging im Ein-Sprecher-Modus
- Export (0.6.2): TXT / Markdown / JSON per ShareSheet mit Sprecherblöcken (FileProvider)
- Room-Datenbank (0.6.6): SQLite statt JSON-Store, einmalige Migration, JSON bleibt als Backup
- Debug-Modus: Roh-WAV + Diagnose-Log (`TestLog`) pro Testaufnahme; Flask-Upload-Server (`debug-server/`, Port 8520) mit Web-Dashboard – kein adb nötig
- Host-Analyse-Skripte: `scripts/host-test/` (Python-Pipeline-Simulation exakt wie die App)
- **Persistente Speaker-DB** (Phase 7, 0.7.0): geräteweite Stimmen-Fingerprints (`GlobalVoiceBank`, JSON-Store in `filesDir/`). Bestätigte Kontakte werden am Session-Ende automatisch eingelernt (0,62-Schwelle confirmed-only) und in künftigen Aufnahmen **ab dem ersten Chunk** wiedererkannt – ganz ohne manuelle Zuweisung
- **Namens-UI** (Phase 7a): Segment antippen nach dem Stoppen → Profil zuweisen oder „Neuer Kontakt" benennen (akustisches ENROLL aus dem Chunk-Puffer, kein WAV-Speicher); Namen erscheinen live, im Verlauf und im Export
- **Kontakt-Verwaltung** (0.7.2): Profile umbenennen, zusammenführen (sample-gewichtet), löschen
- **Aufnahme-Benachrichtigung** (0.7.4): Persistente Notification während der Aufnahme mit Stop- und Screen-Wake-Aktion
- **Display-Wach-Toggle** (0.7.3): dezenter `scr`-Button im Live-Screen (`FLAG_KEEP_SCREEN_ON`)
- **Nachbearbeitungs-Anzeige** (0.7.4): Nach dem Stoppen sichtbar, ob/wie lange finaler Diarization-Lauf + Save dauern
- **Export-Namen aus der History** (0.7.4): `segments.speakerName` (Room-Migration v2) – auch geteilte Exporte zeigen echte Namen
- **Stabile Sprecherfarben** (0.7.5): Farb-Key = Profil-UUID – dieselbe Person behält über alle Aufnahmen dieselbe Farbe
- **Suche über Namen + Texte** (0.7.5): Verlaufssuche durchsucht Titel, Segmenttexte und Sprecher-Namen
- **Sprachnachrichten-Import** (Phase 9, 0.9.0): Audiodatei aus WhatsApp/Telegram & Co. teilen → automatische Transkription (MediaCodec-Decoder für Opus/M4A/MP3/AMR, Limit 30 min), läuft durch dieselbe Diarization- + Speaker-DB-Pipeline wie Aufnahmen
- **Sprecher nachträglich benennen** (Phase 9a, 0.9.1): Sprecher-Label im Detail-Screen antippen → Name gilt für alle Segmente dieses Labels (Anzeige + Export); auch für alte/importierte Transkripte ohne Audio-Zugriff
- **Import sichtbar** (Phase 9b, 0.9.2): Globales Banner über allen Tabs + System-Notification mit Fortschritt
- **Import auf dem Live-Screen** (Phase 9c, 0.9.3): Importiertes Transkript erscheint direkt im Live-Tab; Abschluss-Banner mit **Benennen** (springt zu den Segmenten → akustisches ENROLL aus dem importierten Audio → Fingerprint) oder **Überspringen**
- **Import-Fixes** (Phase 9d–f, 0.9.4–0.9.6): nur noch ein Fortschrittsbalken, zuverlässiger Abschlusszustand, sofortiges Fingerprint-Naming (Session→Profil-Mapping wird direkt registriert)

Geräte-verifiziert: Politik-Podcast 3/3 Sprecher über Sessions wiedererkannt (0.7.1). Duo-Podcasts mit zwei sehr ähnlichen Männerstimmen bleiben dokumentierter akustischer Grenzfall.

### Architektur

```
UI Layer   →   Domain Layer   →   Engine (JNI/Native)
─────────       ───────────       ───────────────────
Compose         AudioCapture      Sherpa-ONNX C++
ViewModel       Diarization       ONNX Runtime
Navigation      VoiceBank         Zipformer-Transducer
                ChunkedBuffer     Pyannote + ERes2Net
```

### Phasen

| Phase | Feature | Status |
|---|---|---|
| Phase 1 | Live-Transkription (MVP) | fertig |
| Phase 3 | Speaker Diarization (2 Sprecher) | fertig (0.6.0) |
| Phase 4 | Export: TXT, Markdown, JSON + ShareSheet | fertig (0.6.2) |
| Phase 5 | History/Detail-Screen + Room-Datenbank | fertig (0.6.8) |
| Phase 6 | Einstellungen: Dark Mode, Schriftgröße, Debug-Schalter, Modell-Info | fertig (0.6.8) |
| Phase 7 | Persistente Speaker-DB: Store, GlobalVoiceBank, Auto-Enroll, Worker-Integration | fertig (0.7.0/0.7.1) |
| Phase 7a | Namens-UI + Kontakt-Verwaltung | fertig (0.7.2) |
| Phase 8 | Komfort & Sichtbarkeit: Notification, Display-Wach, Nachbearbeitungs-Anzeige, Export-Namen | fertig (0.8.0) |
| Phase 8+ | Konsistenz: stabile Sprecherfarben, Suche über Namen/Texte | fertig (0.8.0) |
| Phase 9 | Sprachnachricht teilen → transkribieren: Share-Intent, MediaCodec-Decoder, Offline-Feed | fertig (0.9.0) |
| Phase 9a | Nachträgliche Sprecher-Benennung im Detail-Screen | fertig (0.9.1) |
| Phase 9b | Import sichtbar: globales Banner + System-Notification | fertig (0.9.2) |
| Phase 9c | Import auf dem Live-Screen + Benennen/Überspringen | fertig (0.9.3) |
| Phase 9d–f | Import-Fixes: ein Fortschrittsbalken, sofortiges Fingerprint-Naming | fertig (0.9.4–0.9.6) |

### Downloads

Aktuelle signierte APKs gibt es unter [Releases](https://github.com/qutschwalze/meeting-transcriber-sherpa/releases/latest) (`app-release.apk` für den normalen Betrieb, `app-debug.apk` mit Debug-Upload/TestLog für Diagnose).

### Voraussetzungen

- Android Studio Ladybug (2024.3+) oder neuer
- Gradle 8.10+
- Android SDK 35
- JDK 17
- Ein Android-Gerät mit Mikrofon (API 26+)

### Einrichtung

1. Projekt klonen

   ```bash
   git clone <repo>
   cd sherpa-transcript
   ```

2. Gradle Wrapper generieren

   ```bash
   gradle wrapper --gradle-version 8.10.2
   ```

3. Modelle (liegen in `app/src/main/assets/` oder werden bei Erststart geladen):
   - ASR: `sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06`
   - Diarization: `segmentation.onnx` (ReVerb v1), `embedding.onnx` (ERes2Net)

4. In Android Studio öffnen: `File → Open`, Gradle synchronisieren, auf Gerät ausführen
   (Emulator: Mikrofon via `adb emu avd hostmic` aktivieren)

### Datenschutz

Die gesamte Verarbeitung passiert auf dem Gerät. Stimmen-Profile sind biometrische Daten und bleiben im privaten App-Speicher (`filesDir/`) – sie sind **nie** Teil des Debug-Uploads. Debug-Uploads (Opt-in) enthalten ausschließlich Testaufnahmen und Logs.

## Lizenz

MIT
