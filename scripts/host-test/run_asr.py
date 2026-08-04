#!/usr/bin/env python3
"""Host-Reproduktion der SherpaApp-ASR-Pipeline (SherpaOnnxEngine.kt, App-Version 0.5.60).

Stellt exakt die App-Konfiguration nach:
- OnlineRecognizer, greedy_search, enableEndpoint=true
- Endpoint: rule1 (nonSilence=false, trailing=0.4s), rule2 (nonSilence=true, trailing=0.25s)
- FeatureConfig: 16 kHz, featureDim=80, dither=0.0
- Modell: sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06
Gibt finale Segmente mit Start/End-Timestamps als JSON aus.
"""
import json
import os
import sys
import wave

import numpy as np
import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
WAV = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BASE, "clip16k.wav")
MODEL_DIR = sys.argv[2] if len(sys.argv) > 2 else "/tmp/kroko-de"
FRAME = 512  # 32 ms @16 kHz (App-Frame-Größe)

recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
    encoder=f"{MODEL_DIR}/encoder.onnx",
    decoder=f"{MODEL_DIR}/decoder.onnx",
    joiner=f"{MODEL_DIR}/joiner.onnx",
    tokens=f"{MODEL_DIR}/tokens.txt",
    num_threads=2,
    sample_rate=16000,
    feature_dim=80,
    enable_endpoint_detection=True,
    rule1_min_trailing_silence=0.4,
    rule2_min_trailing_silence=0.25,
    decoding_method="greedy_search",
    provider="cpu",
    dither=0.0,
    debug=False,
)

with wave.open(WAV, "rb") as w:
    assert w.getframerate() == 16000, w.getframerate()
    assert w.getnchannels() == 1, w.getnchannels()
    pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

total_samples = len(pcm)
s = recognizer.create_stream()
segments = []
seg_start_sample = 0  # Sample-Offset, an dem das aktuelle Segment begann

for i in range(0, total_samples, FRAME):
    chunk = pcm[i : i + FRAME]
    s.accept_waveform(16000, chunk)
    while recognizer.is_ready(s):
        recognizer.decode_stream(s)
    if recognizer.is_endpoint(s):
        end_sample = min(i + FRAME, total_samples)
        text = recognizer.get_result(s).strip()
        if text:
            segments.append({
                "text": text,
                "start_sec": round(seg_start_sample / 16000.0, 2),
                "end_sec": round(end_sample / 16000.0, 2),
            })
        recognizer.reset(s)
        seg_start_sample = end_sample

# Rest decodieren und letztes Segment abschließen
while recognizer.is_ready(s):
    recognizer.decode_stream(s)
tail = recognizer.get_result(s).strip()
if tail:
    segments.append({
        "text": tail,
        "start_sec": round(seg_start_sample / 16000.0, 2),
        "end_sec": round(total_samples / 16000.0, 2),
    })

out = {"total_sec": round(total_samples / 16000.0, 2), "n_segments": len(segments), "segments": segments}
print(json.dumps(out, ensure_ascii=False, indent=1))
