# Changelog — Meeting Transcriber Sherpa

Alle Versionswechsel werden hier dokumentiert. Jeder Build erhöht `versionCode` + `versionName` (siehe `app/build.gradle.kts`).

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