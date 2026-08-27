#!/usr/bin/env python3
"""Host-Verifikation der finalen Speaker-Zuordnung (testaufnahme_20260827_160823).

Parst die End-Segmente aus dem App-Log (SAVE_DBG_afterEnrich), zieht pro
Sprecher 2-3 Fenster a 4s aus der App-WAV und berechnet die ERes2Net-
Kosinus-Matrix: intra (gleiche Stimme) vs inter (verschiedene Sprecher).
"""
import re, wave
import numpy as np
import sherpa_onnx

WAV = "/root/sherpa-app/debug-server/uploads/2026-08-27/audio/wav/testaufnahme_20260827_160823.wav"
LOG = "/root/sherpa-app/debug-server/uploads/2026-08-27/text/plain/testaufnahme_20260827_160823.log"
EMBED = "/root/sherpa-app/app/src/main/assets/embedding.onnx"
SR = 16000
WIN_SEC = 4.0

# 1) Finale Segmente aus dem Log
segs = {}
for line in open(LOG):
    if "SAVE_DBG_afterEnrich" not in line:
        continue
    m = re.search(r"t=(\d+)-(\d+)\s+spk=(\S+)", line)
    if not m:
        continue
    spk = m.group(3)
    if not spk.startswith("speaker_"):
        continue  # "-" = unlabeled (Unbekannt-Block), kein Embedding-Check
    segs.setdefault(spk, []).append((int(m.group(1)), int(m.group(2))))

print(f"Sprecher im Final-Overlay: {len(segs)}")
for spk in sorted(segs, key=lambda s: int(s.split('_')[1])):
    durs = [(b - a) for a, b in segs[spk]]
    print(f"  {spk}: {len(durs)} Segmente, {sum(durs)/1000:.0f}s gesamt, "
          f"max {max(durs)/1000:.1f}s, min {min(durs)/1000:.1f}s")

# 2) WAV laden
w = wave.open(WAV)
assert w.getframerate() == SR and w.getnchannels() == 1
samples = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
w.close()

# 3) Fenster pro Sprecher: über die Session verteilt (erstes/Mitte/letztes Segment)
def normalize_audio(x):
    """1:1 aus DiarizationChunkWorker.kt (App-Pipeline)."""
    if x.size == 0:
        return x
    c = x - float(x.mean())
    rms_raw = float(np.sqrt((c ** 2).mean()))
    if rms_raw < 0.0001:
        return c
    gate = max(rms_raw * 0.1, 0.00001)
    out = np.where(np.abs(c) < gate, 0.0, c)
    rms = float(np.sqrt((out ** 2).mean()))
    if rms < 0.0001:
        return out
    gain = 1.0
    if rms < 0.1:
        gain = min(0.1 / rms, 10.0)
        max_peak = float(np.abs(out).max())
        if max_peak * gain > 0.99:
            gain = 0.99 / max_peak
    return out * gain

def window(a_ms, b_ms):
    """4s-Fenster zentriert im Segment (oder ganzes Segment falls kürzer), app-normalisiert."""
    a, b = a_ms / 1000.0, b_ms / 1000.0
    if b - a < 2.0:
        return None  # zu kurz für ein stabiles Embedding
    if b - a < WIN_SEC:
        seg = samples[int(a * SR):int(b * SR)]
    else:
        center = (a + b) / 2.0
        t0 = center - WIN_SEC / 2
        t0 = max(a + 0.3, min(t0, b - WIN_SEC - 0.3))
        t1 = t0 + WIN_SEC
        seg = samples[int(t0 * SR):int(t1 * SR)]
    return normalize_audio(seg)

config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=EMBED, num_threads=2, provider="cpu")
ext = sherpa_onnx.SpeakerEmbeddingExtractor(config)

def embed(audio):
    s = ext.create_stream()
    s.accept_waveform(SR, audio)
    s.input_finished()
    v = ext.compute(s)
    return None if v is None else np.asarray(v)

spk_windows = {}
for spk in sorted(segs, key=lambda s: int(s.split('_')[1])):
    ordered = sorted(segs[spk])  # zeitlich sortiert
    n = len(ordered)
    # erstes, mittleres, letztes Segment – prüft Stabilität über die Session
    picks = [ordered[0], ordered[n // 2], ordered[-1]] if n >= 3 else ordered
    wins = []
    for a, b in picks:
        wav = window(a, b)
        if wav is None:
            continue
        e = embed(wav)
        if e is not None:
            wins.append(e)
    spk_windows[spk] = wins
    print(f"  {spk}: {len(wins)} Embeddings ({n} Segmente, zeitlich verteilt)")

def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))

# 4) Matrix
spks = sorted(spk_windows, key=lambda s: int(s.split('_')[1]))
n = len(spks)
print("\n=== Inter-Speaker-Matrix (Mittelwerte) ===")
print("      " + "".join(f"{s.split('_')[1]:>7}" for s in spks))
mat = np.zeros((n, n))
for i, si in enumerate(spks):
    row = ""
    for j, sj in enumerate(spks):
        if i == j:
            # intra: Mittel über eigene Fenster
            vals = [cos(spk_windows[si][k], spk_windows[si][l])
                    for k in range(len(spk_windows[si])) for l in range(k + 1, len(spk_windows[si]))]
            v = np.mean(vals) if vals else float("nan")
            mat[i, j] = v
            row += f"{v:>7.2f}"
        else:
            vals = [cos(a, b) for a in spk_windows[si] for b in spk_windows[sj]]
            v = np.mean(vals)
            mat[i, j] = v
            row += f"{v:>7.2f}"
    print(f"spk_{sps[i] if False else si.split('_')[1]:>3} " + row)

# 5) Bewertung
print("\n=== Bewertung ===")
intra = [mat[i, i] for i in range(n)]
inter = [mat[i, j] for i in range(n) for j in range(i + 1, n)]
print(f"Intra (gleiche Stimme): min={min(intra):.3f} mean={np.mean(intra):.3f} max={max(intra):.3f}")
print(f"Inter (verschiedene):   min={min(inter):.3f} mean={np.mean(inter):.3f} max={max(inter):.3f}")
risky = [(spks[i].split('_')[1], spks[j].split('_')[1], mat[i, j])
         for i in range(n) for j in range(i + 1, n) if mat[i, j] >= 0.60]
if risky:
    print(f"⚠ Paare >= 0.60 (Trenn-Risiko, evtl. gleiche Stimme gesplittet): {risky}")
else:
    print("Keine Paare >= 0.60 – alle Sprecher akustisch getrennt.")
print(f"Grenzbereich 0.55-0.60: "
      f"{[(spks[i].split('_')[1], spks[j].split('_')[1], round(mat[i,j],3)) for i in range(n) for j in range(i+1,n) if 0.55 <= mat[i,j] < 0.60]}")
