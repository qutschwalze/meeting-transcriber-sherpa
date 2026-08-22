#!/usr/bin/env python3
"""
Cross-Session Voiceprint Test (Fingerprint-DB Machbarkeit):
Werden dieselben Stimmen ueber verschiedene Aufnahme-Sessions hinweg
bei der Voice-Bank-Schwelle (0.62) wiedererkannt?

Methode:
1. 5 App-WAVs vom 2026-08-21 (Room-Mic, 16k mono)
2. Pro WAV: OfflineSpeakerDiarization FIXED_5 (full-buffer, App-Konfig)
   -> pro Speaker-Cluster das laengste stabile Fenster (Mitte, max 6s)
3. Embedding (embedding.onnx = ERes2Net, GLEICHE Datei wie App)
4. Cos-Matrix ueber alle Cluster aller Sessions
5. Hierarchisches Clustering (single linkage, Schwelle 0.62):
   - Gruppe mit Mitgliedern aus >= 2 Sessions => Cross-Session-Erkennung
   - Intra-Session Inter-Sims als Referenz (bekannt: bis ~0.45)
"""
import wave, sys, itertools
import numpy as np
import sherpa_onnx

REPO = "/root/sherpa-app"
WAVS = [
    "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_072605.wav",
    "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_091611.wav",
    "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_110806.wav",
    "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_142334.wav",
    "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_144302.wav",
]
SEG_MODEL = f"{REPO}/app/src/main/assets/segmentation.onnx"
EMB_MODEL = f"{REPO}/app/src/main/assets/embedding.onnx"
MATCH_THR = 0.62

def load_wav(path):
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return x, sr

def diarize(x, sr, num_clusters):
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(SEG_MODEL)),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=EMB_MODEL, num_threads=2, provider="cpu"),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=num_clusters, threshold=0.3),
        min_duration_on=0.1,
        min_duration_off=0.05,
    )
    d = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    res = d.process(x)
    return [(seg.start, seg.end, seg.speaker) for seg in res.sort_by_start_time()]

def extractor():
    return sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(EMB_MODEL, num_threads=2, provider="cpu"))

def embed_window(x, sr, start, end):
    seg = x[int(start * sr): int(end * sr)]
    if len(seg) < sr:
        return None
    ex = extractor()
    stream = ex.create_stream()
    stream.accept_waveform(16000, seg)
    stream.input_finished()
    return ex.compute(stream)

def stable_window(segs, spk):
    """Laengstes Fenster fuer Sprecher spk: gesamtes Segment, Mitte bis max 6s."""
    segs_spk = sorted([s for s in segs if s[2] == spk], key=lambda s: s[1] - s[0], reverse=True)
    for (s, e, _) in segs_spk:
        dur = e - s
        if dur < 2.0:
            continue
        if dur <= 8.0:
            return s, e
        mid = (s + e) / 2
        return mid - 3.0, mid + 3.0
    return None

def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))

# ── 1-3: Diarisieren + Embeddings ────────────────────────────────
clusters = []   # (session_idx, speaker, start, end, emb)
for wi, wpath in enumerate(WAVS):
    x, sr = load_wav(wpath)
    segs = diarize(x, sr, 5)
    speakers = sorted(set(s[2] for s in segs))
    print(f"[{wi}] {wpath.split('/')[-1]}: {len(segs)} Segmente, {len(speakers)} Cluster (FIXED_5)", flush=True)
    for spk in speakers:
        win = stable_window(segs, spk)
        if win is None:
            print(f"     Cluster {spk}: kein Fenster >= 2s", flush=True)
            continue
        s, e = win
        emb = embed_window(x, sr, s, e)
        if emb is None:
            print(f"     Cluster {spk}: Embedding fehlgeschlagen", flush=True)
            continue
        clusters.append((wi, spk, s, e, emb))
        print(f"     Cluster {spk}: Fenster {s:.1f}-{e:.1f}s ({e-s:.1f}s), emb dim {len(emb)}", flush=True)

# ── 4: Cos-Matrix ────────────────────────────────────────────────
n = len(clusters)
M = np.zeros((n, n))
for i, j in itertools.combinations(range(n), 2):
    v = cos(clusters[i][4], clusters[j][4])
    M[i, j] = M[j, i] = v
np.fill_diagonal(M, 1.0)

names = [f"S{wi}c{spk}" for (wi, spk, _, _, _) in clusters]
print("\nCos-Matrix (0.62-Schwelle):")
hdr = "        " + "".join(f"{nm:>9}" for nm in names)
print(hdr)
for i, nm in enumerate(names):
    print(f"{nm:>8}" + "".join(f"{M[i, j]:9.3f}" for j in range(n)))

# Referenz: Intra-Session Inter-Sim (verschiedene Sprecher DERSELBEN Session)
print("\nIntra-Session Inter-Speaker-Sims (Referenz, erwartet <= ~0.45):")
intra = []
for wi in range(len(WAVS)):
    idx = [i for i, c in enumerate(clusters) if c[0] == wi]
    vals = [M[i, j] for i, j in itertools.combinations(idx, 2)]
    if vals:
        intra += vals
        print(f"  Session {wi}: n={len(vals)} min={min(vals):.3f} max={max(vals):.3f} mean={np.mean(vals):.3f}")
if intra:
    print(f"  GESAMT: min={min(intra):.3f} max={max(intra):.3f} mean={np.mean(intra):.3f}")

# ── 5: Hierarchisches Clustering (single linkage, 0.62) ───────────
groups = []
for i in range(n):
    placed = False
    for g in groups:
        if any(M[i, j] >= MATCH_THR for j in g):
            g.append(i)
            placed = True
            break
    if not placed:
        groups.append([i])

print(f"\nClustering (single linkage, thr={MATCH_THR}): {len(groups)} Gruppen")
for gi, g in enumerate(groups):
    members = [names[i] for i in g]
    sessions = set(clusters[i][0] for i in g)
    cross = "CROSS-SESSION" if len(sessions) > 1 else "nur 1 Session"
    print(f"  Gruppe {gi}: {members}  [{cross}]")

# Kernfrage: Wie viele Cluster haben einen Match in einer ANDEREN Session?
matched_cross = 0
for i, c in enumerate(clusters):
    others = [M[i, j] for j in range(n) if j != i and clusters[j][0] != c[0]]
    best = max(others) if others else 0.0
    flag = "  <-- Match in anderer Session" if best >= MATCH_THR else ""
    print(f"  {names[i]}: best cross-session sim = {best:.3f}{flag}")
    if best >= MATCH_THR:
        matched_cross += 1
print(f"\nFAZIT: {matched_cross}/{n} Cluster haben >=0.62-Match in einer ANDEREN Session")