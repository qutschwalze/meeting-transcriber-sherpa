#!/usr/bin/env python3
"""ReVerb v1 vs v2 A/B auf der App-eigenen WAV (testaufnahme_20260826_123205).

Misst pro Variante:
  1. Chunk-Latenz: 12 x 20s-Fenster (Realtime-Faktor fuer den Rolling-Loop)
  2. Qualitaet:    5-Min-Fenster, AUTO(num_clusters=-1, thr=0.35) wie die App,
                   Bewertung ueber ERes2Net-Embedding-Purity (Intra vs Inter)
"""
import gc, sys, time, wave
import numpy as np
import sherpa_onnx

WAV = "/root/sherpa-app/debug-server/uploads/2026-08-26/audio/wav/testaufnahme_20260826_123205.wav"
EMB = "/root/sherpa-app/app/src/main/assets/embedding.onnx"
VARIANTS = [
    ("v1_app",   "/root/sherpa-app/app/src/main/assets/segmentation.onnx"),
    ("v2_fp32",  "/tmp/reverb_ab/v2_fp32.onnx"),
    ("v2_int8",  "/tmp/reverb_ab/v2_int8.onnx"),
]
SR = 16000

w = wave.open(WAV, 'rb')
pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
w.close()
print(f"WAV: {len(pcm)/SR:.1f}s")

def make_diarizer(seg_path):
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(seg_path)),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=EMB, num_threads=2, provider="cpu"),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=0.35),
        min_duration_on=0.1,
        min_duration_off=0.05,
    )
    return sherpa_onnx.OfflineSpeakerDiarization(cfg)

def norm(x):
    rms = float(np.sqrt(np.mean(x**2)))
    if rms > 1e-6:
        x = x * min(10.0, 0.05 / max(rms, 1e-6))
    return x

ext = sherpa_onnx.SpeakerEmbeddingExtractor(
    sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=EMB, num_threads=2, provider="cpu"))

def embed_seg(x):
    if len(x) < SR:  # <1s instabil -> skip
        return None
    st = ext.create_stream(); st.accept_waveform(SR, x); st.input_finished()
    e = ext.compute(st)
    n = np.linalg.norm(e) + 1e-9
    return e / n

results = {}
for name, path in VARIANTS:
    print(f"\n{'='*70}\nVariante {name}: {path}")
    try:
        d = make_diarizer(path)
    except Exception as ex:
        print(f"  LOAD FEHLER: {ex}"); results[name] = None; continue

    # --- 1) Chunk-Latenz: 12 x 20s ab t=120s ---
    lat = []
    for i in range(12):
        s0 = int((120 + i*20) * SR); win = norm(pcm[s0:s0+20*SR].copy())
        t0 = time.time(); segs = d.process(win).sort_by_start_time(); dt = time.time()-t0
        lat.append(dt)
    lat = np.array(lat)
    rt = 20.0  # Audio-Sekunden pro Chunk
    print(f"  Chunk-Latenz 20s-Audio: median={np.median(lat):.2f}s mean={lat.mean():.2f}s "
          f"max={lat.max():.2f}s | RT-Faktor={rt/np.median(lat):.2f}x (>3x noetig)")

    # --- 2) Qualitaet: 5-Min-Fenster 60-360s, AUTO 0.35 ---
    q0, q1 = 60, 360
    win = pcm[int(q0*SR):int(q1*SR)].copy()
    t0 = time.time(); segs = d.process(norm(win)).sort_by_start_time(); qtime = time.time()-t0
    embs, labs = [], []
    for s in segs:
        e = embed_seg(win[int(s.start*SR):int(s.end*SR)].copy())
        if e is not None:
            embs.append(e); labs.append(s.speaker)
    embs = np.array(embs); labs = np.array(labs)
    n_spk = len(set(labs))
    # Intra/Inter-Cosine
    intra, inter = [], []
    for i in range(len(embs)):
        sims = embs @ embs[i]
        for j in range(len(embs)):
            if i == j: continue
            (intra if labs[j] == labs[i] else inter).append(sims[j])
    intra = np.array(intra) if intra else np.array([0.0])
    inter = np.array(inter) if inter else np.array([0.0])
    speech = sum(s.end - s.start for s in segs)
    sep = intra.mean() - inter.mean()
    print(f"  Qualitaet 60-360s ({qtime:.1f}s Inferenz): Segmente={len(segs)} Sprecher(AUTO)={n_spk} "
          f"Sprechzeit={speech:.0f}/{300}s")
    print(f"    Intra mean={intra.mean():.3f} (p10={np.percentile(intra,10):.3f}) | "
          f"Inter mean={inter.mean():.3f} (p90={np.percentile(inter,90):.3f}) | Separation={sep:.3f}")

    results[name] = dict(med_lat=float(np.median(lat)), max_lat=float(lat.max()),
                         n_spk=n_spk, n_segs=len(segs), speech=speech,
                         intra=float(intra.mean()), inter=float(inter.mean()), sep=float(sep))
    del d; gc.collect()

print(f"\n{'='*70}\nZUSAMMENFASSUNG")
print(f"{'Variante':10s} {'Latenz med':>11s} {'RT':>6s} {'Spk':>4s} {'Intra':>6s} {'Inter':>6s} {'Sep':>6s}")
for k, v in results.items():
    if v is None:
        print(f"{k:10s}  LOAD-FEHLER"); continue
    print(f"{k:10s} {v['med_lat']:9.2f}s {20/v['med_lat']:5.1f}x {v['n_spk']:4d} "
          f"{v['intra']:6.3f} {v['inter']:6.3f} {v['sep']:6.3f}")
