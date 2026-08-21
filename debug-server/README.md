# Sherpa Debug Upload Server

Leichtgewichtiger Flask-Server für den Empfang von Debug-Dateien aus der Android-Transkriptions-App – kein `adb` mehr nötig.

## Quick Start

```bash
# In das debug-server Verzeichnis wechseln
cd debug-server

# Flask installieren (einzige Abhängigkeit)
pip install flask

# Server starten
python server.py
```

Der Server läuft auf **http://0.0.0.0:8520**.

## Endpoints

| Methode | Pfad | Beschreibung |
|---------|------|-------------|
| `GET`   | `/` | Web-Dashboard – zeigt alle Uploads nach Datum gruppiert |
| `POST`  | `/upload` | Datei-Upload (multipart form) |
| `GET`   | `/api/files` | JSON-Liste aller hochgeladenen Dateien mit Metadaten |
| `GET`   | `/files/<path>` | Einzelne Datei herunterladen |

## Dateien hochladen

```bash
# Einfacher Upload
curl -F "file=@recording.wav" http://localhost:8520/upload

# Mit Metadaten
curl -F "file=@recording.wav" \
     -F "device_model=23117RK55P" \
     -F "session_id=abc123" \
     -F "file_type=audio" \
     -F "app_version=0.6.16" \
     http://localhost:8520/upload
```

### Metadaten-Felder

| Feld | Pflicht | Beschreibung |
|------|---------|-------------|
| `file` | ✅ | Die Datei zum Hochladen |
| `device_model` | ❌ | Geräte-Modell (z.B. `23117RK55P`, `Pixel 7`) |
| `session_id` | ❌ | Transkriptions-Session-ID |
| `file_type` | ❌ | `audio`, `json`, `log`, `markdown` oder `other`. Wird automatisch erkannt wenn leer |
| `app_version` | ❌ | App-Version (z.B. `0.6.16`) |

## Datei-Struktur

Uploads werden gespeichert unter:
```
uploads/
  YYYY-MM-DD/
    audio/
      testaufnahme_20260821_143000.wav
    log/
      testaufnahme_20260821_143000.log
    markdown/
      testaufnahme_20260821_143000.md
    json/
      transcript.json
```

## Android-Integration

Die App sendet Dateien automatisch nach jeder Aufnahme (wenn Debug-Modus aktiv ist).
Der `DebugUploadClient` (Kotlin) nutzt `java.net.HttpURLConnection` – keine externen Abhängigkeiten.

**Server-URL in der App konfigurieren:**
- Einstellungen → Debug-Modus aktivieren → Server-URL eingeben
- Standard: `http://10.0.2.2:8520` (Emulator)
- Für echtes Gerät: `http://<PC-IP>:8520` (USB-Tethering oder WLAN)

## Features

- Verarbeitet große Dateien (50+ MB WAV) – Flask streamt Uploads auf Disk
- Dashboard aktualisiert sich alle 30 Sekunden automatisch
- Dark Theme, mobilfreundlich
- Einziges Dependency: `flask`
- Alle anderen Imports sind Python-stdlib
