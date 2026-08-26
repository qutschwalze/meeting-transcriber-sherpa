# Changelog — Meeting Transcriber Sherpa

Alle Versionswechsel werden hier dokumentiert. Jeder Build erhöht `versionCode` + `versionName` (siehe `app/build.gradle.kts`).

## 0.10.3 / 150 (2026-08-26)

**Phase 10d – Strip-Guard-Lücke geschlossen (Save-Kollaps trotz korrekter Rolling-Trennung) + ID-Kollisions-Guard**

- **Geräte-Befund (10-min Meeting, testaufnahme_20260826_101317):** Die Rolling-Diarization trennte die Stimmen korrekt – gid 0 und gid 5 lösten sich abwechselnd auf (12 saubere gid5-RESOLVEs, Sims 0,67–0,81; Host-Matrix mit dem App-Embedding-Modell auf der App-eigenen WAV: Intra ≥ 0,617, Inter ≤ 0,600). Der gespeicherte Export kollabierte trotzdem: Mittelteil 01:38–06:25 als EIN Sprecherblock + 10 „Unbekannt"-Blöcke.
- **Root Cause:** Der Strip-Guard in `mergeCandidateIntoBest` (Schutz vor unbestätigten Fehlcluster-IDs) warf auch Labels **bank-bestätigter** Sprecher weg. Die Ausnahme „bestätigte Voice-Bank-Sprecher werden nie gestrippt" war seit 0.5.55 dokumentiert, existierte aber nur im Guard-Trigger (`candHasNewIds`) – nicht im per-Segment-Check. Nach dem ersten Trigger blieb nur gid 0 übrig; der Save-Pfad füllte die Restlücken per Nearest-Confirmed-Resolve → Kollaps auf einen Block.
- **Fix 1:** Per-Segment-Ausnahme ergänzt (`candLabel.speakerId !in confirmedBankIds`) – bank-bestätigte Sprecher überleben den Guard jetzt.
- **Fix 2 – ID-Kollisions-Guard (Worker):** `freshGlobalId` stammt aus dem Bestands-Maximum und kennt Bank-Pendings nicht. Im Testlauf kollidierte die Bestands-Allokation (gid 17 als Pending) mit der Fehlzuordnungs-Allokation derselben 17 → `enroll()` sah das fremde Pending und bestätigte sofort ein Phantom-Profil aus zwei verschiedenen Stimmen („NEUE ID=17 CONFIRMED"). Vor der Allokation prüft jetzt eine Schleife `hasVoiceprintFor()` (Diagnose-Zeile `VB ID_KOLLISION`).
- **Diagnose:** Zuweisungs-Entscheidungen wandern ins TestLog (`ASSIGN ACCEPTED_IMPROVED / REJECTED / NO_CHANGE / SKIP_COLLAPSE`) – sie liefen vorher nur in logcat und machten Save-Kollapse aus den Uploads unerklärlich.

## 0.10.2 / 149 (2026-08-25)

**CONTINUITY_GAP_SEC 2→12s + Bank-Aware Guard**

- **Host-Befund (62-min Meeting):** `CONTINUITY_GAP_SEC=2` greift nur auf sub-chunk-Ebene (Chunks 8-15s auseinander). KONTINUITÄT feuerte nur 16× bei 242 Chunks.
- **Fix 1 – Gap erhöht:** 2→12s (passend zum typischen Chunk-Intervall)
- **Fix 2 – Bank-Aware Guard:** Nur erben wenn Vorgänger NICHT via Global-Bank gemappt wurde (sonst könnte es ein anderer Sprecher sein). Mindestdauer 1s für aktuelle Blöcke.
- **Erwartung:** Weniger Sprecher-Fragmentierung in langen Meetings (10+ Personen).

## 0.10.0 / 147 (2026-08-25)

**Phase 10c – Debug-Upload auch für Sprachnachrichten-Importe**

- **Geräte-Befund:** Geteilte Sprachnachrichten wurden korrekt transkribiert und im Verlauf gespeichert, aber die Debug-Dateien (WAV/Log/MD) landeten **nie im Upload-Server** – `triggerDebugUpload()` wurde nur im `stopRecording`-Pfad aufgerufen, nicht im `importAudio`-Pfad.
- **Fix:** `triggerDebugUpload()` wird jetzt auch im Import-Pfad aufgerufen (nach dem Save-Job in `invokeOnCompletion`).
- **Nebenbei:** SAVE_STAGE-Diagnose (0.9.9) ins TestLog – künftige Save-Pfade sind aus den Uploads vollständig nachvollziehbar.

## 0.9.9 / 146 (2026-08-24)

**Phase 10b – Root Cause gefunden: Anzeige-IDs ↔ Bank-IDs Desync im Save-Pfad**

- **Geräte-Befund (2× 0.9.8-Meetings):** Die Phase-10-Fixes arbeiten korrekt (KONTINUITÄT 18×/14×, PENDING statt Quick-Confirm, Live-Bank stabil bei 2) – ABER Meeting 1 kollabierte im Save (`persisted=3 speakers=1`, ein 31-min-Block), Meeting 2 hatte Drift-Reste (Sprecher 3+4, 5× Unbekannt).
- **Root Cause:** `renumberLiveSpeakerIds()` nummeriert die Anzeige-IDs um (Bank-ID 8 → `speaker_1`), aber der Save-Pfad verglich diese Anzeige-IDs gegen **BANK**-confirmed-IDs `{0,8}` → jedes bestätigte Segment galt plötzlich als „unbestätigt" und fiel dem Nachbar-Resolve zum Opfer. Auch `correctOverlayByVoiceBank` schrieb Bank-Nummern in Anzeige-Segmente.
- **Fix:** Neue Brücke `originalGidToDisplayId()` (Worker: Original-GID nach erster Auftrittszeit ↔ Renumber-Reihenfolge). Alle drei Stellen (Nearest-Confirmed-Resolve, Leading-Resolve, Overlay-Korrektur) übersetzen jetzt zwischen Bank- und Anzeige-Nummerierung.
- **Diagnose:** `SAVE_STAGE`-/`SAVE_STAGE_DELTA`-Zeilen wandern ins TestLog (vorher nur logcat) – künftige Save-Kollapse sind damit direkt aus den Uploads lesbar.

## 0.9.8 / 145 (2026-08-24)

**Phase 10 – Drift-Fixes nach Host-Reproduktion (37-min-Standup, 2 Personen → 8 IDs)**

- **Host-Befund** (`scripts/host-test/standup_drift_test.py`): Bei langen Monolog-Blöcken driftet das Embedding massiv (95/144 Blöcke < 0.62 gegen die Referenz, mean 0.479) – die App *musste* neue IDs spawnen. Der VORGÄNGER ist dagegen stabil (mean 0.819).
- **Fix 1 – Sprechkontinuität:** Ein Block ohne Bank-Match, der zeitlich direkt an einen gemappten Block anschließt (< 2 s Lücke), erbt dessen ID (`VB … KONTINUITÄT global=N`). Deckt ~88 % der Drift-Splits ab, ohne die 0.62-Regel anzufassen.
- **Fix 2 – Enroll-Schutz:** Frisch gespawnte IDs (Fehlzuordnung-Zweig) werden nicht mehr per Quick-Confirm sofort bestätigt (`allowQuickConfirm=false`, `NEUE ID … PENDING`) – Bestätigung erst beim 2. unabhängigen Kontakt. Verhindert die Müll-Profile (+7 pro Meeting).
- **Kontakte:** Neue Aktion „Alle unbenannten löschen (N)" mit Bestätigungsdialog – räumt Auto-Enroll-Reste auf einmal weg.

## 0.9.7 / 144 (2026-08-24)

**Phase 9g – Zuweisungs-Sheet bei vielen Profilen bedienbar**

- **Geräte-Befund:** Bei 25+ Profilen war das Zuweisungs-Sheet nicht mehr scrollbar – „Neuer Kontakt" und Abbrechen lagen außerhalb des Bildschirms.
- **Fix:** Profil-Liste ist jetzt eine scrollbare LazyColumn (max. ~40 % der Höhe), ab 7 Profilen gibt es ein **Suchfeld** zum Filtern; „Neuer Kontakt"-Feld + Buttons bleiben immer sichtbar unten.
- Nebeneffekt der Bank-Größe: Profile ohne Namen (nur „Profil xxxxxxxx") lassen sich über das Suchfeld nicht finden – Empfehlung, unbekannte Profile in den Kontakte-Einstellungen zu benennen oder zu löschen.

## 0.9.6 / 143 (2026-08-23)

**Phase 9f – Korrektur: 0.9.5-Fix war wirkungslos (falsche Datenquelle)**

- **Ehrlicher Befund:** Der 0.9.5-Fix las die Session-GID aus dem **raw-Segment** – das hat nach der 3-Schichten-Architektur gar keine `speakerId` (die steckt nur im Assigned-Overlay). Folge: `gid=null`, Mapping nie registriert, Verhalten unverändert.
- **Korrektur:** Die GID kommt jetzt aus dem Assigned-Overlay (Fallback raw) → `registerProfileMapping` greift wirklich; neue Diagnose-Logs (`VB_GLOBAL_MAPPING session=… profil=…` bzw. Warnung, wenn keine GID ermittelbar).
- Kette verifiziert: Mapping → `resolveDisplayLabel` (Live-Anzeige) → `buildExportProfileNames` (Export) nutzen dieselbe Tabelle; `deriveUiSegments` läuft nach der Zuweisung.

## 0.9.5 / 142 (2026-08-23)

**Phase 9e – Fix: Fingerprint-Zuweisung ändert Namen sofort**

- **Geräte-Befund:** Nach dem Setzen eines Fingerprints im Live-Screen blieben Anzeige UND Verlauf bei „Sprecher x" – erst die nächste Aufnahme zeigte den neuen Namen. Ursache: Das manuelle ENROLL registrierte keinen Eintrag in der Session→Profil-Mapping-Tabelle (`global=0/0` im Log), aus der Anzeige/Export die Brücke „Sprecher x → Profil" lesen.
- **Fix:** `assignSpeakerToSegment` ruft jetzt `registerProfileMapping(sessionId, profil)` auf dem Worker auf → der Name erscheint **sofort** in Live-Anzeige und folgendem Export; die nächste Aufnahme erkennt die Stimme weiterhin automatisch.

## 0.9.4 / 141 (2026-08-23)

**Phase 9d – Fix: doppelte Fortschrittsbalken beim Teilen**

- Beim Teilen aus WhatsApp erschienen **zwei** Balken: das globale Banner (AppNavigation) + ein zweites lokales im LiveScreen – und der blaue globale blieb hängen, weil ihm der 100-%-Schritt fehlte.
- **Fix:** Nur noch EIN Banner (das globale über allen Tabs); das lokale LiveScreen-Banner entfernt.
- **Fix:** Die Bridge bekommt jetzt ihren 100-%-Schritt nach dem Save → „Benennen/Überspringen" erscheinen zuverlässig; Fehlerpfade räumen die Bridge auf.

## 0.9.3 / 140 (2026-08-23)

**Phase 9c – Import läuft auf dem sichtbaren Live-Screen (Fix) + Benennen/Überspringen**

- **Bugfix (leerer Live-Screen nach Teilen):** Der Import lief bisher auf einer ANDEREN LiveViewModel-Instanz als der angezeigte Live-Screen (Activity-scoped vs. Nav-scoped ViewModelStore) – die Segmente erschienen deshalb nie. Neu: MainActivity legt den Share-Intent in `PendingImport` ab, der **LiveScreen konsumiert ihn** und startet den Import auf seiner eigenen (sichtbaren) Instanz → importiertes Transkript erscheint direkt im Live-Tab.
- **Abschluss-Banner mit Aktion:** „Benennen" springt direkt in den Live-Tab (Segment-Tap → akustisches ENROLL aus dem importierten Audio → Fingerprint), „Überspringen" schließt das Banner.
- Import-Fortschritt wandert über `ImportUiBridge` (Prozess-Singleton), damit Banner + Notification immer unabhängig von der Instanz funktionieren.

## 0.9.2 / 139 (2026-08-23)

**Phase 9b – Import sichtbar machen (Banner + System-Notification)**

- **Globales Import-Banner** über allen Tabs (Live/Verlauf/Einstellungen): „Transkribiere ‚Datei' … N %" mit Fortschrittsring; nach Abschluss „Import abgeschlossen – Transkript liegt im Verlauf" mit OK-Button (statt lautlosem Verschwinden).
- **System-Notification mit Fortschritt:** Während des Imports persistente Notification (Prozent + Balken) – auch sichtbar außerhalb der App / bei gesperrtem Display. Abschluss-Notification „Transkript fertig – liegt im Verlauf".
- **Bugfix:** Nach dem ersten Import blockierte `importProgress=100` weitere Importe – jetzt nur noch laufende Importe (0–99) als Guard.

## 0.9.1 / 138 (2026-08-23)

**Phase 9a – Sprecher im Detail-Screen nachträglich benennen**

- Im Transkript-Detail das Sprecher-Label antippen (z. B. „Sprecher 2") → Dialog → Name eingeben: **alle Segmente dieses Labels** bekommen `speakerName` (Anzeige sofort, Export nutzt ihn automatisch). Leer = Zuweisung entfernen.
- Schließt die Lücke für alte/importierte Transkripte, deren Audio-Puffer weg ist (akustisches ENROLL geht dort nicht mehr – aber der Name steuert Anzeige + Export).
- DAO: `assignSpeakerName(tid, label, name)` (UPDATE über Label); DetailViewModel lädt Segmente nach dem Umbenennen neu.

## 0.9.0 / 137 (2026-08-23)

**Phase 9 – Sprachnachrichten teilen → automatisch transkribieren**

- **Share-Intent:** Die App erscheint im Teilen-Sheet von WhatsApp/Telegram/etc. für Audiodateien (`audio/*`) – Sprachnachricht antippen → teilen → Sherpa Transcript wählt sich die Datei automatisch transkribiert + diariziert in den Verlauf.
- **Audio-Import-Decoder:** `MediaExtractor` + `MediaCodec` → PCM16 → Mono-Downmix → linearer Resampler auf 16 kHz (`AudioResampler`, JVM-getestet). Deckt Opus/OGG (WhatsApp), M4A/AAC, MP3, AMR ab – alles, was MediaCodec dekodiert.
- **Offline-Feed mit virtueller Uhr:** Frames à 100 ms werden mit nachgeführter Zeitbasis durch die normale ASR-Pipeline gefüttert; danach läuft derselbe Finalisierungs-Pfad wie bei Aufnahmen (Diarization forceFinal, Auto-Enroll in die Speaker-DB, Save) – importierte Nachrichten profitieren also auch von der Wiedererkennung.
- **Fortschritts-Anzeige:** „Transkribiere ‚Datei' … N %" über der BottomBar während des Imports; Limit 30 min pro Datei (Schutz vor Speicher-Explosion).
- Titel = Dateiname bzw. „Sprachnachricht"; keine Storage-Permission nötig (URI-Zugriff kommt vom Share-Intent).

## 0.8.0 / 136 (2026-08-23)

**Re-Versionierung: Phase 8 komplett → 0.8.x** (Konsistenz zur Phasen-Nummerierung; identischer Code zu 0.7.5/135). Erste 0.8er-Release mit signierten APKs.

*Enthält die Phase-8-Features aus den Builds 0.7.3–0.7.5:*

- Aufnahme-Notification mit Stop/scr-Aktionen, Display-Wach-Toggle (`scr`)
- Nachbearbeitungs-Anzeige nach dem Stoppen (+ `POSTPROCESS took`-Log)
- Export-Namen aus der History (`segments.speakerName`, Room-Migration v2)
- Stabile Sprecherfarben über Sessions (Profil-UUID als Farb-Key)
- Suche über Sprecher-Namen und Segmenttexte (`searchTranscriptsFull`)

## 0.7.5 / 135 (2026-08-23)

**Phase 8 – Stabile Sprecherfarben, Namenssuche, Scroll-Diagnose**

- **Stabile Sprecherfarben:** Farb-Key ist jetzt die Profil-UUID statt der Session-ID – dieselbe Person behält über alle Aufnahmen dieselbe Farbe (Fallback Session-ID bei unzugeordneten Speakern).
- **Suche findet Namen und Text:** Die Verlaufs-Suche durchsucht jetzt zusätzlich Segmenttexte und Sprecher-Namen (`searchTranscriptsFull`, DISTINCT-Query mit EXISTS-Subquery) – ein gesuchter Kontaktname listet damit alle Aufnahmen, in denen er vorkommt.
- **Scroll-Repro-Log** (`SCROLL_REPRO …` im TestLog, nur Debug-Modus): protokolliert Auto-Scroll-Aktivierung + Listenposition + Sheet-Zustand zur Diagnose des Zuweisungs-Sprung-Bugs.

## 0.7.4 / 134 (2026-08-23)

**Phase 8 komplett – Post-Processing-Sichtbarkeit, Export-Namen, Aufnahme-Benachrichtigung**

- **Nachbearbeitungs-Anzeige:** Nach dem Stoppen zeigt der Live-Screen „Nachbearbeitung… Ns" (LinearProgressIndicator über der BottomBar) – sichtbar, ob und wie lange der finale Diarization-Lauf + Save dauern; `POSTPROCESS took=…ms` im Log.
- **Export-Namen aus der History:** `segments.speakerName` (Room-Migration 1→2, zerstörungsfrei per ALTER). Beim Save werden die Profil-Namen mitgespeichert → Share/History-Export (.md/.txt) zeigt `## Anna` statt `## Sprecher 1`; alte Transkripte bleiben „Sprecher N".
- **Aufnahme-Benachrichtigung:** Während der Aufnahme persistente Notification mit Aktions-Buttons **Stop** und **scr** (Screen wach) – funktioniert über `RecordingActionReceiver` + `RecordingBridge` (Singleton-Ref aufs aktive ViewModel). Verschwindet beim Stop.
- WACH-Button im Live-Screen auf **„scr"** gekürzt (dezent, 36 dp).

## 0.7.3 / 133 (2026-08-23)

**Phase 8 – Display-Wach-Toggle**

- Dezenter WACH-Button im Live-Screen (36-dp-Kreis, neben dem DBG-Toggle): hält das Display während der Aufnahme wach (`FLAG_KEEP_SCREEN_ON`), kein Stromsparmodus. Aktiv = Primärfarbe; Flag wird bei Deaktivierung entfernt. State im UiState, Flag setzt die UI per `LaunchedEffect` (kein Activity-Zugriff im ViewModel nötig).

## 0.7.2 / 132 (2026-08-22)

**Phase 7a – Namens-UI + Kontakt-Verwaltung**

- `SpeakerProfile.name` (optional) + Store-Version 2 (alte Dateien laden ohne Bruch); `GlobalVoiceBank`-Namens-API (`rename`/`nameFor`/`displayLabel`).
- **Zuweisung nach dem Stoppen:** Segment antippen → Sheet mit bekannten Profilen oder „Neuer Kontakt" (Name). ENROLL aus dem Chunk-Puffer (`readWindow`) – kein WAV-Speicher nötig.
- **Namens-Overlay** in Live- und Detail-Ansicht (nur Anzeige, raw/assigned unangetastet); Export (`.md`/`.txt`) zeigt `## Anna` statt `## Sprecher 1`.
- **Kontakt-Verwaltung** im Einstellungs-Screen: Profile umbenennen, zusammenführen (sample-gewichtet), löschen – zentrale `SpeakerProfiles`-Instanz (Live + Settings teilen denselben Stand).
- Worker exponiert Session→Profil-Zuordnung; `VB_GLOBAL_ASSIGN`-Logs.
- 90+ Unit-Tests grün.

## 0.7.1 / 131 (2026-08-22)

**Bugfix (Phase 7):** Global-Resolve lernt die Stimme in die Session-Bank ein

- **Behoben:** `SAVE persisted=1 speakers=1` trotz korrekt erkannter Profile. Ursache: Das Mapping-only-Design ließ global gemappte Session-IDs für die finale confirmed-Logik unsichtbar → der Nearest-Confirmed-Resolve mappte die ganze Session auf den einzigen bestätigten Nachbarn.
- **Fix:** Beim Global-Match wird die Stimme zusätzlich in die Session-Bank eingelernt (`VB_GLOBAL_LEARN … enroll=CONFIRMED`). Kein Doppel-ID-Risiko (der erste ≥ 2 s-Kontakt einer Stimme läuft immer durch den Global-Match).
- **Verifiziert (Gerät):** Politik-Podcast, 2 Aufnahmen: Session 1 baut 3 Profile auf, Session 2 erkennt alle 3 ab dem ersten Chunk wieder (`VB_GLOBAL_RESOLVE`), Export mit 3 korrekt getrennten Sprechern, `labeled=11/11`.

## 0.7.0 / 130 (2026-08-22)

**Phase 7 – Persistente Speaker-Datenbank** (Versionssprung 0.6.x → 0.7)

- `SpeakerProfileStore`: Geräteweite, persistente Stimmen-Fingerprints (JSON in `filesDir`, atomarer Save, korruptionssicher).
- `GlobalVoiceBank`: Nur bestätigte Profile, feste 0.62-Match-Schwelle (keine 0.35-pending!), rolling average, UUID-Profil-IDs. `autoEnrollFrom()` übernimmt bestätigte Kontakte am Session-Ende automatisch (ohne UI/Namen).
- Worker-Integration: Global-Bank-identify nach der Session-Bank → Session-ID-Stabilität über Profile; Diagnose-Zähler `globalResolve`/`globalMap` in den `CHUNKED`-Logs.
- `VB_GLOBAL autoEnroll` + `VB_GLOBAL_RESOLVE` Log-Zähler im TestLog; Feature-Flag `ENABLE_GLOBAL_VOICE_BANK` (A/B).
- 80 Unit-Tests grün inkl. neuer Tests für Store, Bank, Auto-Enroll und Worker-Resolve.
- **Host-belegt:** Gleiche Person über Sessions 0.62–0.81, fremde Stimmen ≤ 0.54; Vorbank-A/B: 12→4 IDs, 95,2 % korrekt; 5-Min-Meeting-Split: 24,8 % → 75,2 % korrekt.

## Vor 0.7.0

Die Versionen 0.6.x (bis 0.6.24/129):
- 0.6.24: ASR-Sprachmodus (DE_ONLY Standard, optional DE_EN_AUTO), Upload-Dedup (server.py)
- 0.6.23: `VB_RESOLVE_UNLABELED` (akustische Auflösung unlabeled Segmente, 46 % → ~14 %)
- 0.6.22: `lastTestWavName`-Fix (Debug-WAV nach Stop), ASR-Sprachdetektion
- 0.6.20: `VB_DRIFT_ABFANG` (Phantom-Confirmed-Speaker, 7 → 4)
- 0.6.14: `VB_CORRECT` (Backchannel-Korrektur der Overlay-Zuordnung)
- 0.6.12: ERes2Net-Embedding-Modell (Titanet mergte ähnliche Stimmen)
- 0.6.11: Quick-Confirm 4 s, Multi-Speaker-Lever-Paket
- 0.6.7: Erster GitHub-Release mit debug + release APK