#!/usr/bin/env python3
"""
Host-Timeline (FIXED_5) der WAV – kompakt ausgeben, damit wir die
App-"Unbekannt"-Blöcke (02:01, 02:34, 03:08, 03:49) zeitlich zuordnen können.
"""
import wave
import numpy as np
import sherpa_onnx

REPO = "/root/sherpa-app"
WAV = "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_110806.wav"
SEG_MODEL = f"{REPO}/app/src/main/assets/segmentation.onnx"
EMB_MODEL = f"{REPO}/app/src/main/assets/embedding.onnx"

with wave.open(WAV, "rb") as w:
    sr = w.getframerate()
    data = w.readframes(w.getnframes())
x = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0

cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
    segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
        pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(SEG_MODEL)),
    embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=EMB_MODEL, num_threads=2, provider="cpu"),
    clustering=sherpa_onnx.FastClusteringConfig(num_clusters=5, threshold=0.3),
    min_duration_on=0.1, min_duration_off=0.05)
d = sherpa_onnx.OfflineSpeakerDiarization(cfg)
segs = d.process(x).sort_by_start_time()

def ts(sec):
    m, s = divmod(int(sec), 60)
    return f"{m:02d}:{s:02d}"

print("Host-Timeline FIXED_5 (Clustering 0.3):")
print(f"{'Start':>7} {'End':>7} {'Clu':>3} {'Dauer':>5}")
for seg in segs:
    s, e, spk = seg.start, seg.end, seg.speaker
    print(f"{ts(s):>7} {ts(e):>7} {spk:>3} {e-s:>5.1f}")

# Redezeit pro Cluster
dur = {}
for seg in segs:
    dur[seg.speaker] = dur.get(seg.speaker, 0) + (seg.end - seg.start)
print("\nRedezeit pro Cluster:")
for spk in sorted(dur):
    print(f"  Cluster {spk}: {dur[spk]:.1f}s")

# App Unbekannt-Fenster: 02:01-02:13, 02:34-02:46(?), 03:08, 03:49
print("\nApp-'Unbekannt'-Blöcke (aus Transkript, Start + ca. 10-15s):")
for start in [121, 154, 188, 229]:
    hits = [seg for seg in segs if seg.start <= start + 7 <= seg.end or abs(seg.start - start) < 6]
    if hits:
        for h in hits[:3]:
            print(f"  {ts(start)} → Cluster {h.speaker} [{ts(h.start)}-{ts(h.end)}]")
    else:
        print(f"  {ts(start)} → kein Host-Segment in der Nähe")