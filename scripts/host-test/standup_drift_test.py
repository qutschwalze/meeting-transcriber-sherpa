#!/usr/bin/env python3
"""Phase-10-Host-Reproduktion: Standup-Monolog → Embedding-Drift → ID-Splits.

Simuliert die App-Pipeline (19,9s-Blöcke aus ENGINE_SEGS) mit dem exakten
App-Embedding (ERes2Net) und misst:
  A) Drift: Cosine jedes Blocks gegen den ersten Block derselben Stimme
     (erwartet: sinkt über die Zeit unter 0.62 → "KEIN Match" → neue ID)
  B) Fix 1 (Kontinuität): Cosine jedes Blocks gegen seinen VORGÄNGER
     (erwartet: konstant hoch ~0.8+ → Fortsetzungs-Erbe stabil)
  C) Fix 2 (Enroll-Schutz): Wie viele der gespawnten IDs hätten bei
     Wiederholungs-Bestätigung (2. Kontakt) wieder zur Haupt-ID gematcht?

WAV: debug-server/uploads/2026-08-24/audio/wav/testaufnahme_20260824_140443.wav
(16 kHz mono; App hat Blöcke [0.03-19.98] pro 15s-Fenster geliefert.)
"""
import glob
import sys

import numpy as np
import sherpa_onnx

SR = 16000
MODEL = '/root/sherpa-app/app/src/main/assets/embedding.onnx'
DUR = 2212  # 36:52

wav = glob.glob('/root/sherpa-app/debug-server/uploads/2026-08-24/audio/wav/testaufnahme_20260824_140443.wav')[0]

# WAV laden (16-bit PCM → f32 normalisiert)
import wave
w = wave.open(wav, 'rb')
assert w.getframerate() == SR and w.getnchannels() == 1, (w.getframerate(), w.getnchannels())
raw = w.readframes(w.getnframes())
samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
w.close()
print(f"Samples: {len(samples)/SR:.1f}s")

config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
    model=MODEL, num_threads=2, debug=False, provider='cpu')
ext = sherpa_onnx.SpeakerEmbeddingExtractor(config)

def emb(start_s: float, end_s: float):
    seg = samples[int(start_s * SR):int(end_s * SR)]
    if len(seg) < SR // 2:
        return None
    stream = ext.create_stream()
    stream.accept_waveform(SR, seg)
    stream.input_finished()
    v = ext.compute(stream)
    return None if v is None else np.asarray(v)

def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))

# Die App lieferte pro 15s-Fenster einen Block [0.03 .. 19.98] relativ zum
# Chunk-Start (Chunks: 0,10,25,40,... im 15s-Takt ab 10s Overlap-Phase).
# Wir nehmen die Blockfolge wie in ENGINE_SEGS protokolliert:
blocks = []
t = 0.03
while t + 5 <= DUR - 20:
    end = min(t + 19.95, DUR - 0.02)
    blocks.append((t, end))
    t += 15.0
print(f"Blöcke (à ~19,9s): {len(blocks)}")

embs = []
for (a, b) in blocks:
    e = emb(a, b)
    embs.append(e)

# Referenz = erster Block (der Sprecher, der fast durchgehend spricht)
ref = embs[0]
prev = None
drift_to_ref = []
cont_prev = []
below_thr_vs_ref = 0
below_thr_vs_prev = 0
for i, e in enumerate(embs):
    if e is None:
        continue
    d = cos(e, ref)
    drift_to_ref.append((i, d))
    if d < 0.62:
        below_thr_vs_ref += 1
    if prev is not None:
        c = cos(e, prev)
        cont_prev.append((i, c))
        if c < 0.62:
            below_thr_vs_prev += 1
    prev = e

print("\n=== A) Drift gegen Referenzblock 0 (App-Sicht: identify()) ===")
print(f"Blöcke < 0.62 (= 'KEIN Match' → neue ID in der App): {below_thr_vs_ref}/{len(drift_to_ref)}")
vals = [d for _, d in drift_to_ref]
print(f"min={min(vals):.3f} max={max(vals):.3f} mean={np.mean(vals):.3f}")
print("Verlauf (Block: sim):", ", ".join(f"{i}:{d:.2f}" for i, d in drift_to_ref[::4]))

print("\n=== B) Kontinuität: Block vs. VORGÄNGER (Fix 1) ===")
print(f"Blöcke < 0.62 gegen Vorgänger: {below_thr_vs_prev}/{len(cont_prev)}")
vals2 = [c for _, c in cont_prev]
print(f"min={min(vals2):.3f} max={max(vals2):.3f} mean={np.mean(vals2):.3f}")

# C) Enroll-Schutz-Simulation: Wie oft würde ein Split-Block beim NÄCHSTEN
# Kontakt (2. unabhängiger Kontakt, hier: nächster Block) wieder >= 0.620
# gegen die HAUPT-Stimme matchen?
recovered = sum(1 for i, d in drift_to_ref if d < 0.62 and i + 1 < len(drift_to_ref)
                and dict(drift_to_ref)[i + 1] >= 0.50)
print("\n=== C) Enroll-Schutz (Fix 2, Simulation) ===")
print(f"Split-Blöcke (<0.62), deren Nachfolger wieder >=0.50 zur Haupt-ID: "
      f"{recovered} → diese hätten NICHT als eigenes Profil enrolled werden dürfen")

print("\n=== Fazit ===")
print(f"Fix 1 (Kontinuitätserbe) würde {(1 - below_thr_vs_prev/max(len(cont_prev),1))*100:.0f}% "
      f"der Blöcke korrekt beim Vorgänger halten.")
print(f"Fix 2 würde verhindern, dass {below_thr_vs_ref - recovered if below_thr_vs_ref > recovered else 0}"
      f"-… Split-IDs als Profile landen (Rest prüfen im Detail).")
