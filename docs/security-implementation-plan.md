# Sherpa Transcript — Sicherheits-Implementierungsplan

**Quelle:** [Threat Model](https://wiki.heddrich.com/books/projekte/page/sherpa-transcript-threat-model) (02.09.2026)
**Status:** Planung
**Prinzip:** Usability darf nicht leiden — jede Änderung muss für den Nutzer transparent oder unsichtbar sein.
**Kontext:** Debug-Server läuft nur lokal. Voice-Bank-Backup wird nicht aktiv genutzt. Debug-Upload-Pfad wird aus Release-Builds entfernt.

---

## Lage-Zusammenfassung

| Kategorie | Threats | Controls fehlend | Code-Bestand |
|-----------|---------|-------------------|--------------|
| **Kritisch** | T5, T18 | C6 (Debug Server Auth) | Debug Server: 0 Auth, plain HTTP, Port 8520 |
| **Hoch** | T3, T4, T8, T13, T15, T16, T19 | C4, C5, C7, C8, C9 | allowBackup=true, HTTP Upload, JSON unverschlüsselt |
| **Mittel** | 13 Threats | — | Teilweise abgedeckt |
| **Niedrig** | 3 Threats | — | — |

### Entscheidungen
- **Debug-Server:** Nur lokal → TLS auf Server-Seite nicht nötig (kein Netzwerk-Exposure)
- **Release-Builds:** Debug-Upload-Pfad komplett entfernen (stärkster Schutz)
- **Voice-Bank-Backup:** Nicht aktiv → Phase 4 deprioritisiert (nur wenn Bedarf steigt)

---

## Phase 1: Quick Wins — Config-only (kein UX-Impact)
**Zeitaufwand:** ~0.5 Tag | **Version:** 0.12.0 | **Status: ✅ FERTIG (03.09.2026)**

### 1.1 allowBackup=false
- **Datei:** `AndroidManifest.xml` Zeile 26
- **Änderung:** `android:allowBackup="true"` → `android:allowBackup="false"`
- **Threat:** T8 (Backup Exposure)
- **Control:** C4
- **UX:** Keine Änderung. ADB-Backup wird verhindert, Nutzer merkt nichts.

### 1.2 network_security_config — DEFERRED
- **Status:** Abgebrochen — XML-Parser-Error auf Xiaomi-Geräten. `<domain>` mit CIDR-Notation nicht unterstütz. Minimale Variante ohne `<domain>` crasht ebenfalls.
- **Entscheidung:** `usesCleartextTraffic="true"` beibehalten (wie vorher). Sicherheit kommt aus Phase 2 (Release-Strip). Separat lösen wenn nötig.

### 1.3 Manifest Permissions bereinigen
- `android.permission.WRITE_EXTERNAL_STORAGE` mit `android:maxSdkVersion="28"` — bereits korrekt ✓
- Prüfen ob `ACCESS_NETWORK_STATE` noch nötig (Debug-Upload)
- **UX:** Keine Änderung.

---

## Phase 2: Debug Server Auth + Release-Strip (kritisch)
**Zeitaufwand:** ~1-2 Tage | **Version:** 0.12.x | **Status: ✅ FERTIG (03.09.2026)**

### 2.1 Debug-Upload aus Release-Builds entfernen
- **Ansatz:** Build-Flag `DEBUG_UPLOAD_ENABLED` (Debug: true, Release: false)
- **Dateien:** `app/build.gradle.kts` (buildTypes), `LiveViewModel.kt`, `DebugUploadClient.kt`
- **Änderung:**
  - `build.gradle.kts`: `buildFeatures { buildConfig = true }` + `buildConfigField` pro buildType
  - `LiveViewModel.triggerDebugUpload()`: `if (BuildConfig.DEBUG_UPLOAD_ENABLED)` → gar nicht erst aufrufen
  - `DebugUploadClient`: `@RequiresApi` oder `if (BuildConfig.DEBUG_UPLOAD_ENABLED)` als Gate
- **Threat:** T5, T18 — vollständig eliminiert (kein Code im Release)
- **Control:** C6 (effectiv: Attack Surface = 0)
- **UX:** Keine Änderung. Release-User sehen nie Debug-Upload.

### 2.2 Debug Server: API-Key Auth (nur für Debug-Builds)
- **Dateien:** `debug-server/server.py` + `DebugUploadClient.kt`
- **Ansatz:** Static API-Key (Zufall generiert beim ersten Start, in server-Config + in App Settings)
- **Server-Änderungen:**
  - `@app.before_request` → Header `X-API-Key` prüfen (nur /upload, /api/files, DELETE)
  - Dashboard (`/`) bleibt ohne Auth
  - API-Key in `.env` oder `config.json` neben `server.py`
- **App-Änderungen:**
  - `SettingsStore.debugApiKey` (neues Feld, Default="")
  - `DebugUploadClient.uploadFile()` → Header `X-API-Key` anhängen
  - Settings-Screen: "Debug Server API-Key" Eingabefeld (unter Debug-Server-URL)
  - Nur wenn Key gesetzt → Upload erlauben; sonst Hinweis
- **Threat:** T5 (Missing Authentication), T18 (Unauthenticated File Access)
- **Control:** C6
- **UX:** Nur Debug-Builds: Nutzer muss beim ersten Mal den API-Key in Settings eingeben (einmalig). Release-Builds: kein Impact.

### 2.3 Debug Server: Upload-Size-Limit
- **Datei:** `debug-server/server.py`
- **Änderung:** `MAX_CONTENT_LENGTH = 100 * 1024 * 1024` (100 MB)
- **Threat:** C7
- **UX:** Keine Änderung.

### 2.4 Debug Server: DELETE-Endpoint Hardening
- **Datei:** `debug-server/server.py`
- **Änderung:** DELETE nur mit API-Key, Session-Key validieren (nur Stem, keine Sonderzeichen)
- **Threat:** T19 (Path Traversal)
- **Control:** C11 (existiert teilweise, erweitern)
- **UX:** Keine Änderung.

---

## Phase 3: TLS für Debug Upload (hoch — reduziert)
**Zeitaufwand:** ~0.5 Tage | **Version:** 0.13.0

### 3.1 Network Security Config (Debug-Only)
- **Datei:** `app/src/main/res/xml/network_security_config.xml`
- **Ansatz:** Nur für Debug-Builds — `<debug-overrides>` erlaubt User-Certs für localhost
- **Änderung:**
  - `AndroidManifest.xml`: `android:networkSecurityConfig="@xml/network_security_config"`
  - Release-Builds: Kein HTTP erlaubt (default deny)
  - Debug-Builds: HTTP zu localhost erlaubt (Development-Komfort)
- **Threat:** T4 (Cleartext Traffic)
- **Control:** C5
- **UX:** Keine Änderung.

### 3.2 DebugUploadClient: HTTPS-Support (vorbereiten)
- **Datei:** `DebugUploadClient.kt`
- **Änderung:** URL-Schema-Check → Debug erlaubt http://, Release blockiert http://
- **UX:** Keine Änderung (Release hat keinen Upload-Code, Phase 2.1).

---

## Phase 4: Daten-Schutz (hoch — DEPRIORITISIERT)
**Zeitaufwand:** ~2-3 Tage | **Version:** 0.16.0 (wenn Bedarf)
**Status:** Nur umsetzen, wenn Voice-Bank-Backup tatsächlich aktiv genutzt wird.

### 4.1 Voice-Bank Backup Verschlüsselung
- **Dateien:** `SpeakerProfileStore.kt`, `ContactsSection.kt`
- **Ansatz:** AES-256-GCM Verschlüsselung beim Export/Import
- **Schlüssel:** Android Keystore + Biometrie (Fingerprint) — kein Passwort nötig
- **UX:**
  - Export: Biometrie-Bestätigung → verschlüsselte JSON
  - Import: Biometrie-Bestätigung → Entschlüsselung
- **Threat:** T13 (Biometric Data Theft), T15 (Cross-App Data Leakage)
- **Control:** C9

### 4.2 Transkripte: SQLCipher (optional)
- **Ansatz:** Room + SQLCipher für Verschlüsselung der SQLite-DB
- **Überlegung:** Hilft gegen Root-Zugriff (T3/T16), aber: Nutzer hat physischen Zugriff auf Gerät
- **Priorität:** Mittel — erst nach Phase 1-3
- **UX:** Keine Änderung (transparent).

### 4.3 Biometric Data nicht im Backup (Android Backup)
- **Änderung:** `android:allowBackup="false"` (Phase 1) löst das bereits
- **Zusätzlich:** `android:fullBackupContent="@xml/backup_rules"` mit `<exclude domain="sharedpref" path="speakerProfiles.json"/>`
- **Threat:** T8 (Backup Exposure)
- **Control:** C8

---

## Phase 5: Hardening (mittel/niedrig)
**Zeitaufwand:** ~1-2 Tage | **Version:** 0.15.0

### 5.1 Intent-Filter Validation
- **Datei:** `AndroidManifest.xml`
- **Änderung:** `<intent-filter>` für Audio-Import mit explizitem `android:grantUriPermissions="false"`
- **Threat:** T12 (Intent Injection)

### 5.2 ProGuard/R8 für Release Builds
- **Status:** Prüfen ob aktiviert
- **Ziel:** Code obfuscatio → Reverse Engineering erschweren
- **UX:** Keine Änderung.

### 5.3 Logging-Reduktion (Release)
- **Änderung:** `BuildConfig.DEBUG` → nur im Debug-Modus loggen
- **Threat:** T14 (Information Disclosure)
- **UX:** Keine Änderung.

---

## Usability-Check pro Phase

| Phase | Nutzer-Sichtbar? | Aktion nötig? | Erklärung |
|-------|-------------------|---------------|-----------|
| 1 | ❌ Nein | Keine | Reine Config-Änderungen |
| 2 | ❌ Nein (Release) / ⚠️ Minimal (Debug) | 1x API-Key | Nur Debug-Builds |
| 3 | ❌ Nein | Keine | Network-Config nur im Hintergrund |
| 4 | ⚠️ Biometrie | Fingerprint | Nur bei Backup (wenn aktiv) |
| 5 | ❌ Nein | Keine | Hintergrund-Hardening |

---

## Abfolge & Dependencies

```
Phase 1 (Config) ──→ Phase 2 (Release-Strip + Debug Auth) ──→ Phase 3 (Network Config)
                                                                    ↓
                                                              Phase 5 (Hardening)
                                                                    ↓
                                                              Phase 4 (Backup-Verschlüsselung, wenn Bedarf)
```

- Phase 1: Sofort umsetzbar, 5 Minuten, kein Risiko
- Phase 2: Stärkster Schutz — Debug-Upload gar nicht im Release. Priorität 1.
- Phase 3: Network Security Config — passt gut zu Phase 2 (gleicher Build-Zyklus)
- Phase 5: Kann parallel laufen
- Phase 4: Deprioritisiert — nur wenn Backup-Szenario entsteht

---

*Erstellt: 02.09.2026 · Basierend auf Threat Model + Code-Review*
*Stand: 02.09.2026 — Kontextualisiert mit Nutzer-Feedback (Debug lokal, kein Backup, Release-Strip)*
