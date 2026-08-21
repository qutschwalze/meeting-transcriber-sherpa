#!/usr/bin/env python3
"""
Debug Upload Server for Android Transcription App
Accepts file uploads via POST, serves a web dashboard, and provides a JSON API.
"""
import os, json, time
from datetime import datetime
from pathlib import Path
from flask import Flask, request, jsonify, send_from_directory, abort

app = Flask(__name__)
UPLOAD_DIR = Path(__file__).parent / "uploads"
UPLOAD_DIR.mkdir(exist_ok=True)
METADATA_DIR = Path(__file__).parent / "metadata"
METADATA_DIR.mkdir(exist_ok=True)

def _meta_path(fp): return METADATA_DIR / (fp.name + ".meta.json")

def _save_meta(fp, device, session_id, file_type, app_version=""):
    meta = {"filename": fp.name, "device": device or "", "session_id": session_id or "",
            "file_type": file_type or "", "app_version": app_version or "",
            "size": fp.stat().st_size, "uploaded_at": datetime.now().isoformat(),
            "relative_path": str(fp.relative_to(UPLOAD_DIR))}
    _meta_path(fp).write_text(json.dumps(meta, indent=2))

def _load_meta(fp):
    mp = _meta_path(fp)
    meta = None
    if mp.exists():
        try: meta = json.loads(mp.read_text())
        except: pass
    if meta is None:
        meta = {"filename": fp.name, "device": "", "session_id": "", "file_type": "",
                "size": fp.stat().st_size if fp.exists() else 0,
                "uploaded_at": datetime.fromtimestamp(fp.stat().st_mtime).isoformat() if fp.exists() else "",
                "relative_path": str(fp.relative_to(UPLOAD_DIR))}
    # 0.6.22: stem (Dateiname ohne Extension) für die Session-Löschung im Dashboard
    meta["stem"] = Path(fp.name).stem
    return meta

def _scan():
    files = []
    if not UPLOAD_DIR.exists(): return files
    for p in sorted(UPLOAD_DIR.rglob("*")):
        if p.is_file() and not p.name.endswith(".meta.json"):
            files.append(_load_meta(p))
    return files

@app.route("/upload", methods=["POST"])
def upload():
    if "file" not in request.files: return jsonify({"error": "No file"}), 400
    f = request.files["file"]
    if not f.filename: return jsonify({"error": "Empty filename"}), 400
    device = request.form.get("device_model", "") or request.form.get("device", "")
    session_id = request.form.get("session_id", "")
    file_type = request.form.get("file_type", "")
    app_version = request.form.get("app_version", "")
    if not file_type:
        ext = Path(f.filename).suffix.lower()
        file_type = {".wav": "audio", ".mp3": "audio", ".json": "json",
                     ".txt": "log", ".log": "log", ".md": "markdown",
                     ".png": "image", ".jpg": "image"}.get(ext, "other")
    today = datetime.now().strftime("%Y-%m-%d")
    dest = UPLOAD_DIR / today / file_type
    dest.mkdir(parents=True, exist_ok=True)
    out = dest / Path(f.filename).name
    f.save(str(out))
    _save_meta(out, device, session_id, file_type, app_version)
    return jsonify({"ok": True, "path": str(out.relative_to(UPLOAD_DIR)), "size": out.stat().st_size}), 201

@app.route("/api/files")
def api_files(): return jsonify(_scan())

@app.route("/files/<path:relpath>")
def serve_file(relpath): return send_from_directory(str(UPLOAD_DIR), relpath, as_attachment=True)

def _safe_relative(relpath):
    """Path-Traversal-Schutz: nur echte relative Pfade unter UPLOAD_DIR."""
    p = (UPLOAD_DIR / relpath).resolve()
    if not p.is_relative_to(UPLOAD_DIR.resolve()):
        return None
    return p

@app.route("/files/<path:relpath>", methods=["DELETE"])
def delete_file(relpath):
    """Löscht eine einzelne hochgeladene Datei + zugehörige Metadaten."""
    p = _safe_relative(relpath)
    if p is None:
        return jsonify({"error": "Ungültiger Pfad"}), 400
    if not p.exists() or not p.is_file():
        return jsonify({"error": "Nicht gefunden"}), 404
    # Metadaten-Datei liegt flach in METADATA_DIR (Name + .meta.json)
    meta = METADATA_DIR / (p.name + ".meta.json")
    try:
        p.unlink()
        if meta.exists():
            meta.unlink()
        return jsonify({"ok": True, "deleted": str(p.relative_to(UPLOAD_DIR))})
    except OSError as e:
        return jsonify({"error": str(e)}), 500

@app.route("/session/<session_key>", methods=["DELETE"])
def delete_session(session_key):
    """Löscht alle Dateien einer Session (gleicher Dateiname ohne Extension)."""
    if not session_key or "/" in session_key or "\\" in session_key or session_key.startswith("."):
        return jsonify({"error": "Ungültiger Session-Key"}), 400
    deleted, missing = [], []
    for p in sorted(UPLOAD_DIR.rglob("*")):
        if p.is_file() and p.name.endswith(".meta.json"):
            continue
        if p.stem == session_key:
            p.unlink()
            meta = METADATA_DIR / (p.name + ".meta.json")
            if meta.exists():
                meta.unlink()
            deleted.append(str(p.relative_to(UPLOAD_DIR)))
    if not deleted:
        return jsonify({"error": "Keine Dateien für Session gefunden"}), 404
    return jsonify({"ok": True, "deleted": deleted})

@app.route("/")
def dashboard():
    # 0.6.20: Dashboard als separate HTML-Datei (kein Escape-Wahnsinn in Python-Strings)
    return send_from_directory(str(Path(__file__).parent), 'dashboard.html')


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8520, debug=False)
