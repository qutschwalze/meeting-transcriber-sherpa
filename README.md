# Sherpa Transcript — Android

100 % offline speech-to-text app for Android built on **Sherpa-ONNX**, **Jetpack Compose** and **Kotlin**.

On-device live transcription including **speaker diarization** and a **persistent speaker database** — no cloud, no network.

**Sprache / Language:** [English](#english) · [Deutsch](#deutsch)

---

<a name="english"></a>
## English

### Features (as of 0.10.7)

**Recognition & diarization**

- Live ASR: Sherpa-ONNX OnlineRecognizer (streaming), Kroko Zipformer transducer (German); optional German + English mode (`DE_EN_AUTO`, loads EN Zipformer ~38 MB, auto-detection after 3 s)
- Speaker diarization: ReVerb v1 segmentation + ERes2Net embeddings, fully offline, 2–4 speakers on microphone/meeting recordings
- Rolling diarization: 15 s chunk pipeline with overlap, temporal-vote reconciler and `SessionVoiceBank` (acoustic memory against engine drift)
- Hardened speaker assignment chain: drift pre-check rejects phantom IDs from chunk-boundary drift (`VB_DRIFT_ABFANG`), Quick-Confirm for long first contacts, bank-aware continuity inheritance across chunks (12 s gap), acoustic resolution of unknown blocks between confirmed speakers (`VB_RESOLVE_UNLABELED`), duplicate-profile consolidation at save (`VB_DUP_MERGE`, 0.10.7) so a drifted known voice no longer survives as several speakers

**Speakers & data**

- Persistent speaker DB (Phase 7): device-wide voice fingerprints (`GlobalVoiceBank`). Confirmed contacts are auto-enrolled at session end and re-recognized **from the first chunk** of any future recording — no manual assignment
- Naming UI: tap a segment after stopping → assign an existing profile or name a new contact (acoustic enroll straight from the audio buffer); rename any speaker label afterwards in the detail view — also for old/imported transcripts without audio access
- Contact management: rename, merge (sample-weighted), delete, bulk-delete unnamed profiles; stable speaker colors keyed by profile UUID
- History with Room database (SQLite), full-text search across titles, segment texts and speaker names

**Import & export**

- Voice message import (Phase 9): share audio from WhatsApp/Telegram etc. → automatic transcription through the same diarization/speaker-DB pipeline (MediaCodec decoder for Opus/M4A/MP3/AMR, 30 min limit), visible via global banner + notification, result appears on the live screen with Name/Skip banner
- Export: TXT / Markdown / JSON via share sheet with speaker blocks and real names (also for history entries)

**Comfort & diagnostics**

- Recording notification with Stop and screen-wake actions; discreet screen-wake toggle; post-processing indicator (final pass + save duration)
- Screen-off robustness (0.10.6): CPU wake lock for the whole recording (no timeout, released when the service dies) + thermal guard that pauses diarization inference while the device throttles (audio stays buffered, no loss)
- Debug mode: raw WAV + diagnostic log (`TestLog`) per test recording, uploaded to a companion Flask server (`debug-server/`, port 8520, web dashboard) — no adb needed. Assignment decisions (`ASSIGN ACCEPTED/REJECTED/NO_CHANGE/SKIP_COLLAPSE`), save stages and all voice-bank events are logged
- Host analysis scripts: `scripts/host-test/` Python pipeline simulation matching the app configuration exactly (voice-bank calibration, drift tests, A/B tests)

Device-verified: politics podcast, 3/3 speakers re-recognized across sessions (0.7.1). Duo podcasts with two very similar male voices remain a documented acoustic edge case.

### Architecture

```
UI Layer   →   Domain Layer   →   Engine (JNI/Native)
─────────       ───────────       ───────────────────
Compose         AudioCapture      Sherpa-ONNX C++
ViewModel       Diarization       ONNX Runtime
Navigation      VoiceBank         Zipformer transducer
                ChunkedBuffer     Pyannote + ERes2Net
```

Three-layer lossless data model: `rawFinalSegments` (ASR ground truth, never mutated) → `assignedFinalSegments` (speaker overlay) → `displaySegments` (UI merge, renumbered by first appearance).

### Downloads

Current signed APKs are available under [Releases](https://github.com/qutschwalze/meeting-transcriber-sherpa/releases/latest) (`app-release.apk` for regular use, `app-debug.apk` with diagnostic upload/test log).

### Requirements & setup

- Android Studio Ladybug (2024.3+) · Gradle 8.10+ · Android SDK 35 · JDK 17 · device with microphone (API 26+)

```bash
git clone <repo> && cd sherpa-transcript
gradle wrapper --gradle-version 8.10.2
```

Models ship in `app/src/main/assets/`: ASR `sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06`, diarization `segmentation.onnx` (ReVerb v1) + `embedding.onnx` (ERes2Net). Open in Android Studio (`File → Open`, sync Gradle) and run on device (emulator: enable mic via `adb emu avd hostmic`).

### Privacy

All processing happens on the device. Voice profiles are biometric data and stay in the app's private storage (`filesDir/`) — they are never included in debug uploads. Debug uploads (opt-in) contain test recordings and logs only.

---

<a name="deutsch"></a>
## Deutsch

100 % offline Speech-to-Text App für Android mit **Sherpa-ONNX**, **Jetpack Compose** und **Kotlin**.

Live-Transkription auf dem Gerät inkl. **Speaker Diarization** und **persistenter Speaker-Datenbank** – keine Cloud, kein Netzwerk.

### Funktionen (Stand 0.10.7)

**Erkennung & Diarization**

- Live-ASR: Sherpa-ONNX OnlineRecognizer (Streaming), Kroko-Zipformer-Transducer (Deutsch); optional Deutsch + Englisch (`DE_EN_AUTO`, lädt EN-Zipformer ~38 MB, Auto-Detection nach 3 s)
- Speaker Diarization: ReVerb v1 (Segmentation) + ERes2Net (Embedding), komplett offline, 2–4 Sprecher auf Mikrofon-/Meeting-Aufnahmen
- Rolling-Diarization: 15s-Chunk-Pipeline mit Overlap, Reconciler (temporales Voting) und `SessionVoiceBank` (akustisches Gedächtnis gegen Engine-Drift)
- Gehärtete Sprecher-Zuweisungskette: Drift-Vorprüfung verwirft Phantom-IDs aus Chunk-Grenzen (`VB_DRIFT_ABFANG`), Quick-Confirm bei langen Erstkontakten, bank-bewusste Kontinuitätsvererbung über Chunk-Grenzen (12 s Gap), akustische Auflösung unbekannter Blöcke zwischen bestätigten Speakern (`VB_RESOLVE_UNLABELED`), Duplikat-Profil-Konsolidierung im Save (`VB_DUP_MERGE`, 0.10.7) – eine gedriftete bekannte Stimme überlebt nicht mehr als mehrere Sprecher

**Sprecher & Daten**

- Persistente Speaker-DB (Phase 7): geräteweite Stimmen-Fingerprints (`GlobalVoiceBank`). Bestätigte Kontakte werden am Session-Ende automatisch eingelernt und in künftigen Aufnahmen **ab dem ersten Chunk** wiedererkannt – ganz ohne manuelle Zuweisung
- Namens-UI: Segment antippen nach dem Stoppen → Profil zuweisen oder „Neuer Kontakt" benennen (akustisches Enroll direkt aus dem Audio-Puffer); Sprecher nachträglich im Detail-Screen umbenennen – auch für alte/importierte Transkripte ohne Audio-Zugriff
- Kontakt-Verwaltung: Umbenennen, Zusammenführen (sample-gewichtet), Löschen, Sammel-Löschen unbenannter Profile; stabile Sprecherfarben per Profil-UUID
- Verlauf mit Room-Datenbank (SQLite), Volltextsuche über Titel, Segmenttexte und Sprechernamen

**Import & Export**

- Sprachnachrichten-Import (Phase 9): Audiodatei aus WhatsApp/Telegram & Co. teilen → automatische Transkription durch dieselbe Diarization-/Speaker-DB-Pipeline (MediaCodec-Decoder für Opus/M4A/MP3/AMR, Limit 30 min), sichtbar über globales Banner + Notification, Ergebnis erscheint im Live-Screen mit Benennen/Überspringen
- Export: TXT / Markdown / JSON per ShareSheet mit Sprecherblöcken und echten Namen (auch für Verlaufs-Einträge)

**Komfort & Diagnose**

- Aufnahme-Benachrichtigung mit Stop- und Display-Wach-Aktion; dezenter Screen-Wake-Toggle; Nachbearbeitungs-Anzeige (finaler Lauf + Save-Dauer)
- Screen-off-Robustheit (0.10.6): CPU-WakeLock für die gesamte Aufnahme (ohne Timeout, Freigabe beim Service-Ende) + Thermal-Guard, der Diarization-Inferenz bei Drosselung pausiert (Audio bleibt gepuffert, kein Verlust)
- Debug-Modus: Roh-WAV + Diagnose-Log (`TestLog`) pro Testaufnahme, Upload an den Flask-Begleitserver (`debug-server/`, Port 8520, Web-Dashboard) – kein adb nötig. Zuweisungs-Entscheidungen (`ASSIGN ACCEPTED/REJECTED/NO_CHANGE/SKIP_COLLAPSE`), Save-Stufen und alle Voice-Bank-Ereignisse werden geloggt
- Host-Analyse-Skripte: `scripts/host-test/` (Python-Pipeline-Simulation exakt wie die App: Voice-Bank-Kalibrierung, Drift-Tests, A/B-Tests)

Geräte-verifiziert: Politik-Podcast, 3/3 Sprecher über Sessions wiedererkannt (0.7.1). Duo-Podcasts mit zwei sehr ähnlichen Männerstimmen bleiben dokumentierter akustischer Grenzfall.

### Architektur

```
UI Layer   →   Domain Layer   →   Engine (JNI/Native)
─────────       ───────────       ───────────────────
Compose         AudioCapture      Sherpa-ONNX C++
ViewModel       Diarization       ONNX Runtime
Navigation      VoiceBank         Zipformer-Transducer
                ChunkedBuffer     Pyannote + ERes2Net
```

Dreischichtiges verlustfreies Datenmodell: `rawFinalSegments` (ASR-Ground-Truth, nie verändert) → `assignedFinalSegments` (Speaker-Overlay) → `displaySegments` (UI-Merge, Nummerierung nach erstem Auftreten).

### Downloads

Aktuelle signierte APKs gibt es unter [Releases](https://github.com/qutschwalze/meeting-transcriber-sherpa/releases/latest) (`app-release.apk` für den normalen Betrieb, `app-debug.apk` mit Debug-Upload/TestLog für Diagnose).

### Voraussetzungen & Einrichtung

- Android Studio Ladybug (2024.3+) · Gradle 8.10+ · Android SDK 35 · JDK 17 · Gerät mit Mikrofon (API 26+)

```bash
git clone <repo> && cd sherpa-transcript
gradle wrapper --gradle-version 8.10.2
```

Modelle liegen in `app/src/main/assets/`: ASR `sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06`, Diarization `segmentation.onnx` (ReVerb v1) + `embedding.onnx` (ERes2Net). In Android Studio öffnen (`File → Open`, Gradle synchronisieren) und auf dem Gerät ausführen (Emulator: Mikrofon via `adb emu avd hostmic` aktivieren).

### Datenschutz

Die gesamte Verarbeitung passiert auf dem Gerät. Stimmen-Profile sind biometrische Daten und bleiben im privaten App-Speicher (`filesDir/`) – sie sind **nie** Teil des Debug-Uploads. Debug-Uploads (Opt-in) enthalten ausschließlich Testaufnahmen und Logs.

## Lizenz

MIT
