#!/usr/bin/env python3
"""Phase-Host-Test: 62-Min-Sprachnachricht/Meeting (151848) – Embedding-Similarities messen.

Misst Cosine-Similarities zwischen verschiedenen Zeitabschnitten desselben Datensatzes,
um zu sehen, ob ERes2Net innerhalb des Tracks ein stabiles Klangbild liefert oder ob
der Drift (sim < 0.62) reproduzierbar ist.

WAV: debug-server/uploads/2026-08-25/audio/wav/testaufnahme_20260825_151848.wav
"""
import glob
import numpy as np
import sherpa_onnx

SR = 16000
EMBED_MODEL = '/root/sherpa-app/app/src/main/assets/embedding.onnx'

wav = glob.glob('/root/sherpa-app/debug-server/uploads/2026-08-25/audio/wav/testaufnahme_20260825_151848.wav')[0]
import wave
w = wave.open(wav, 'rb')
assert w.getframerate() == SR and w.getnchannels() == 1, (w.getframerate(), w.getnchannels())
samples = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
w.close()
dur = len(samples) / SR
print(f"WAV: {dur:.1f}s ({dur/60:.1f} min), {w.getnframes()} samples")

config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=EMBED_MODEL, num_threads=2, provider='cpu')
ext = sherpa_onnx.SpeakerEmbeddingExtractor(config)

def emb(start, end):
    seg = samples[int(start*SR):int(end*SR)]
    if len(seg) < SR//2: return None
    s = ext.create_stream(); s.accept_waveform(SR, seg); s.input_finished()
    v = ext.compute(s)
    return None if v is None else np.asarray(v)

def cos(a,b):
    return float(np.dot(a,b)/(np.linalg.norm(a)*np.linalg.norm(b)+1e-12))

# 15-Sekunden-Blöcke im Raster der App (15s Chunks)
print("\n=== Intra-Speaker-Test: cos(Blöcke desselben Tracks) ===")
blocks = []
t = 0
while t+15 <= dur:
    blocks.append((t, t+15))
    t += 15
embs = [emb(a,b) for a,b in blocks]
valid = [(i,e) for i,e in enumerate(embs) if e is not None]

# Cosine zwischen aufeinanderfolgenden Blöcken
consec = [(i, cos(embs[i], embs[i+1])) for i in range(len(embs)-1) if embs[i] is not None and embs[i+1] is not None]
vals = [c for _,c in consec]
print(f"Consecutive blocks: n={len(vals)} min={min(vals):.3f} max={max(vals):.3f} mean={np.mean(vals):.3f} std={np.std(vals):.3f}")
below_62 = sum(1 for v in vals if v < 0.62)
print(f"Blöcke <0.62 (Drift-Vorfall): {below_62}/{len(vals)} ({below_62/len(vals)*100:.0f}%)")

# Cosine zwischen Block 0 und allen anderen (App: identify())
ref = embs[0]
to_ref = [(i, cos(embs[i], ref)) for i in range(1, len(embs)) if embs[i] is not None]
rvals = [r for _,r in to_ref]
print(f"\nBlock 0 → alle: n={len(rvals)} min={min(rvals):.3f} max={max(rvals):.3f} mean={np.mean(rvals):.3f}")
below_ref = sum(1 for v in rvals if v < 0.62)
print(f"Blöcke <0.62 gegen Referenz: {below_ref}/{len(rvals)} ({below_ref/len(rvals)*100:.0f}%)")

# Cosine zwischen jedem Block und seinem nähesten Nachbarn in Zeit (Kontinuitäts-Test: ist der Vorgänger stabiler als die Referenz?)
# Wir vergleichen: sim(block[i], block[i-1]) vs sim(block[i], block[0])
print("\n=== Kontinuitäts-Heuristik: Vorgänger vs. Referenz ===")
better = sum(1 for i in range(1,len(embs)) if embs[i] is not None and embs[i-1] is not None
             and cos(embs[i], embs[i-1]) > cos(embs[i], embs[0]))
print(f"Vorgängerstabiler als Referenz: {better}/{len(vals)} ({better/len(vals)*100:.0f}%)")

# Am Ende: was sagt das für die App-Heuristik? Die Continuity Gap=2s.
# Bei 15s Chunks sind die Blöcke im Bestand ~15s voneinander entfernt → GAP > 2s!
# Die App-Kontinuität greift nur bei <2s Lücke zwischen den Anzeige-Segmenten,
# NICHT zwischen den Chunk-Zeitpunkten. Das erklärt, warum sie selten greift.
print(f"\nApp-Kontinuitäts-Gap (CONTINUITY_GAP_SEC): 2s")
print(f"Reale Chunk-Abstände: ~15s (7s Overlap → effectiv 8-15s)")
print(f"→ Kontinuitäts-Heuristik greift NUR wenn zwei Engine-Segmente innerhalb")
print(f"  desselben Chunks direkt aufeinanderfolgen (sub-chunk-level)")
