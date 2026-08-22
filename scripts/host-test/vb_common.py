#!/usr/bin/env python3
"""Gemeinsame Funktionen fuer den Vorbank-A/B-Test (1:1 zur App-Pipeline)."""
import wave
import numpy as np
import sherpa_onnx

REPO = "/root/sherpa-app"
SEG_MODEL = f"{REPO}/app/src/main/assets/segmentation.onnx"
EMB_MODEL = f"{REPO}/app/src/main/assets/embedding.onnx"
SAMPLE_RATE = 16000
CHUNK_SEC = 15.0
OVERLAP_SEC = 5.0
VB_MATCH_THRESHOLD = 0.62
VB_PENDING_CONFIRM = 0.35
VB_MIN_ENROLL_SEC = 2.0
QUICK_CONFIRM_SEC = 4.0


def load_wav(path):
    with wave.open(path, "rb") as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return x


def diarize(x, num_clusters, threshold):
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(SEG_MODEL)),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=EMB_MODEL, num_threads=2, provider="cpu"),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=num_clusters, threshold=threshold),
        min_duration_on=0.1,
        min_duration_off=0.05,
    )
    d = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    res = d.process(x)
    return [(seg.start, seg.end, seg.speaker) for seg in res.sort_by_start_time()]


def extractor():
    return sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(EMB_MODEL, num_threads=2, provider="cpu"))


def embed(x, start, end):
    """Embedding eines Fensters; None wenn < 1s oder Fehler."""
    seg = x[int(start * SAMPLE_RATE): int(end * SAMPLE_RATE)]
    if len(seg) < SAMPLE_RATE:
        return None
    ex = extractor()
    s = ex.create_stream()
    s.accept_waveform(16000, seg)
    s.input_finished()
    if not ex.is_ready(s):
        return None
    return np.asarray(ex.compute(s), dtype=np.float32)


def cosine(a, b):
    if a is None or b is None or len(a) != len(b):
        return 0.0
    na = float(np.dot(a, a)) ** 0.5
    nb = float(np.dot(b, b)) ** 0.5
    return float(np.dot(a, b)) / (na * nb) if na > 0 and nb > 0 else 0.0


def normalize_audio(x):
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


def build_chunks(total):
    """Chunk-Geometrie wie ChunkedAudioBuffer.takeChunk/takeRemainingChunk."""
    chunks = []
    prev_end = 0.0
    while True:
        next_end = prev_end + CHUNK_SEC
        if next_end > total + 0.05:
            break
        is_first = prev_end == 0.0
        win_start = next_end - CHUNK_SEC - (0.0 if is_first else OVERLAP_SEC)
        chunks.append({"start": win_start, "end": next_end, "overlap": 0.0 if is_first else OVERLAP_SEC})
        prev_end = next_end
    if prev_end < total - 0.05:
        rest_start = max(0.0, prev_end - OVERLAP_SEC)
        rest_end = min(total, prev_end + CHUNK_SEC)
        if rest_end > rest_start + 0.05:
            chunks.append({"start": rest_start, "end": rest_end, "overlap": prev_end - rest_start})
    return chunks