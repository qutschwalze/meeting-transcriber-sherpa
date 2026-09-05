#!/usr/bin/env python3
"""
Debug Upload Server for Android Transcription App
Accepts file uploads via POST, serves a web dashboard, and provides a JSON API.

0.12.0: Security hardening (Threat Model T5/T18/T7/T19):
  - API-Key Auth (X-API-Key header) for /upload, /api/files, DELETE endpoints
  - Upload size limit (MAX_CONTENT_LENGTH)
  - Path traversal hardening on DELETE
"""
import os, json, secrets, time
from datetime import datetime
from pathlib import Path
from functools import wraps
from flask import Flask, request, jsonify, send_from_directory, abort

app = Flask(__name__)
UPLOAD_DIR = Path(__file__).parent / "uploads"
UPLOAD_DIR.mkdir(exist_ok=True)
METADATA_DIR = Path(__file__).parent / "metadata"
METADATA_DIR.mkdir(exist_ok=True)
CONFIG_PATH = Path(__file__).parent / "config.json"

# ── 0.12.0: Upload size limit (100 MB) ───────────────────────────────
MAX_UPLOAD_BYTES = 100 * 1024 * 1024
app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD_BYTES

# ── 0.12.0: API-Key management ────────────────────────────────────────
def _load_or_create_api_key() -> str:
    """Load existing API key from config.json or generate a new one."""
    if CONFIG_PATH.exists():
        try:
            cfg = json.loads(CONFIG_PATH.read_text())
            key = cfg.get("api_key", "")
            if key:
                return key
        except (json.JSONDecodeError, KeyError):
            pass
    # Generate new key
    key = secrets.token_urlsafe(32)
    cfg = {"api_key": key}
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2))
    print(f"[setup] Generated new API key: {key}")
    print(f"[setup] Saved to {CONFIG_PATH}")
    return key

API_KEY = _load_or_create_api_key()

def require_api_key(f):
    """Decorator: check X-API-Key header on protected endpoints."""
    @wraps(f)
    def decorated(*args, **kwargs):
        provided = request.headers.get("X-API-Key", "")
        if not secrets.compare_digest(provided, API_KEY):
            return jsonify({"error": "Unauthorized – provide X-API-Key header"}), 401
        return f(*args, **kwargs)
    return decorated

# ── Metadata helpers ──────────────────────────────────────────────────
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
    meta["stem"] = Path(fp.name).stem
    return meta

def _scan():
    files = []
    if not UPLOAD_DIR.exists(): return files
    for p in sorted(UPLOAD_DIR.rglob("*")):
        if p.is_file() and not p.name.endswith(".meta.json"):
            files.append(_load_meta(p))
    return files

# ── 0.12.0: Path traversal hardening ──────────────────────────────────
def _safe_relative(relpath):
    """Path-Traversal-Schutz: nur echte relative Pfade unter UPLOAD_DIR.
    Additional: reject . .. and empty segments."""
    if not relpath or relpath.startswith("/") or relpath.startswith("\\"):
        return None
    parts = Path(relpath).parts
    if any(p in (".", "..", "") for p in parts):
        return None
    p = (UPLOAD_DIR / relpath).resolve()
    if not p.is_relative_to(UPLOAD_DIR.resolve()):
        return None
    return p

# ── Routes ────────────────────────────────────────────────────────────
@app.route("/upload", methods=["POST"])
@require_api_key
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
    fname = Path(f.filename).name

    # Dedup – gleicher Dateiname + gleiche Größe existiert bereits
    try:
        if hasattr(f.stream, "seek"):
            f.stream.seek(0, 2)
            blob_len = f.stream.tell()
            f.stream.seek(0)
        else:
            blob_len = None
    except OSError:
        blob_len = None
    existing = None
    for p in UPLOAD_DIR.rglob("*"):
        if p.is_file() and not p.name.endswith(".meta.json") and p.name == fname:
            try:
                if blob_len is not None and p.stat().st_size == blob_len:
                    existing = p
                    break
            except OSError:
                pass
    if existing is not None:
        f.stream.read()
        rel = str(existing.relative_to(UPLOAD_DIR))
        return jsonify({"ok": True, "dedup": True, "path": rel,
                        "size": existing.stat().st_size, "existing": True}), 200

    today = datetime.now().strftime("%Y-%m-%d")
    dest = UPLOAD_DIR / today / file_type
    dest.mkdir(parents=True, exist_ok=True)
    out = dest / fname
    f.save(str(out))
    _save_meta(out, device, session_id, file_type, app_version)
    return jsonify({"ok": True, "path": str(out.relative_to(UPLOAD_DIR)), "size": out.stat().st_size}), 201

@app.route("/api/files")
@require_api_key
def api_files(): return jsonify(_scan())

@app.route("/files/<path:relpath>")
@require_api_key
def serve_file(relpath):
    p = _safe_relative(relpath)
    if p is None: return jsonify({"error": "Invalid path"}), 400
    if not p.exists() or not p.is_file(): return jsonify({"error": "Not found"}), 404
    return send_from_directory(str(UPLOAD_DIR), relpath, as_attachment=True)

@app.route("/files/<path:relpath>", methods=["DELETE"])
@require_api_key
def delete_file(relpath):
    """Löscht eine einzelne hochgeladene Datei + zugehörige Metadaten."""
    p = _safe_relative(relpath)
    if p is None: return jsonify({"error": "Invalid path"}), 400
    if not p.exists() or not p.is_file(): return jsonify({"error": "Not found"}), 404
    meta = METADATA_DIR / (p.name + ".meta.json")
    try:
        p.unlink()
        if meta.exists(): meta.unlink()
        return jsonify({"ok": True, "deleted": str(p.relative_to(UPLOAD_DIR))})
    except OSError as e:
        return jsonify({"error": str(e)}), 500

@app.route("/session/<session_key>", methods=["DELETE"])
@require_api_key
def delete_session(session_key):
    """Löscht alle Dateien einer Session (gleicher Dateiname ohne Extension)."""
    if not session_key or "/" in session_key or "\\" in session_key or session_key.startswith("."):
        return jsonify({"error": "Invalid session key"}), 400
    deleted, missing = [], []
    for p in sorted(UPLOAD_DIR.rglob("*")):
        if p.is_file() and p.name.endswith(".meta.json"):
            continue
        if p.stem == session_key:
            p.unlink()
            meta = METADATA_DIR / (p.name + ".meta.json")
            if meta.exists(): meta.unlink()
            deleted.append(str(p.relative_to(UPLOAD_DIR)))
    if not deleted:
        return jsonify({"error": "No files for session found"}), 404
    return jsonify({"ok": True, "deleted": deleted})

@app.route("/")
def dashboard():
    return send_from_directory(str(Path(__file__).parent), "dashboard.html")

if __name__ == "__main__":
    print(f"[info] API key: {API_KEY}")
    print(f"[info] Listening on 0.0.0.0:8520")
    app.run(host="0.0.0.0", port=8520, debug=False)
