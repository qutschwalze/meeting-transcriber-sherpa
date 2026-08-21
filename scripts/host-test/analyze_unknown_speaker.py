#!/usr/bin/env python3
"""
Host-Analyse der App-WAV (testaufnahme_20260821_110806.wav):
Wie viele echte Sprecher sind drin? Sind die "Unbekannt"-Segmente eine eigene Stimme?

Methode:
1. OfflineSpeakerDiarization (segmentation.onnx aus Assets) auf der GANZEN WAV
   - FIXED_5 Clusters (num_clusters=5, threshold 0.3) → zeigt ob 5 Cluster trennbar sind
   - AUTO (−1) als Gegenprobe
2. Pro Cluster: Embedding (embedding.onnx = ERes2Net, gleiche Datei wie App)
3. Cos-Similarity-Matrix der Cluster → Trennbarkeit
4. Zeiten der "Unbekannt"-Segmente aus dem App-Transkript zuordnen
"""
import sys, wave, json
import numpy as np
import sherpa_onnx

REPO = "/root/sherpa-app"
WAV = "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_110806.wav"
SEG_MODEL = f"{REPO}/app/src/main/assets/segmentation.onnx"
EMB_MODEL = f"{REPO}/app/src/main/assets/embedding.onnx"

# "Unbekannt"-Segmente aus dem App-Transkript (Startzeiten)
UNKNOWN_SEGMENTS = [121, 154, 188, 229]  # 00:02:01, 00:02:34, 00:03:08, 00:03:49

def load_wav(path):
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        n = w.getnframes()
        data = w.readframes(n)
    x = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    return x, sr

def diarize(x, sr, num_clusters):
    """OfflineSpeakerDiarization mit minOn=0.1, minOff=0.05 (App-Konfig)."""
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
    # sort_by_start_time() GIBT die Segment-Liste zurück (Result-Objekt nicht iterierbar)
    return [(seg.start, seg.end, seg.speaker) for seg in res.sort_by_start_time()]

def embed_window(x, sr, start, end):
    """Embedding eines Fensters via SpeakerEmbeddingExtractor (ERes2Net/Titanet)."""
    seg = x[int(start * sr): int(end * sr)]
    if len(seg) < sr:  # < 1s
        return None
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(EMB_MODEL, num_threads=2, provider="cpu"))
    stream = extractor.create_stream()
    stream.accept_waveform(16000, seg)
    stream.input_finished()
    return extractor.compute(stream)

def cosine(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))

def main():
    print("Lade WAV...")
    x, sr = load_wav(WAV)
    dur = len(x) / sr
    print(f"  {dur:.1f}s, sr={sr}")

    for nc in [5, -1]:
        label = f"FIXED_{nc}" if nc > 0 else "AUTO(-1)"
        print(f"\n=== Diarization {label} (ganze WAV) ===")
        segs = diarize(x, sr, nc)
        speakers = sorted(set(s[2] for s in segs))
        print(f"  {len(segs)} Segmente, {len(speakers)} Cluster: {speakers}")

        # Redezeit pro Cluster
        dur_by_spk = {}
        for s, e, spk in segs:
            dur_by_spk[spk] = dur_by_spk.get(spk, 0) + (e - s)
        for spk in sorted(dur_by_spk):
            print(f"    Cluster {spk}: {dur_by_spk[spk]:.1f}s Redezeit")

        # Embedding pro Cluster (größtes Segment oder Top-3 kombinieren nicht – nimm
        # die Top-10s des Clusters)
        emb = {}
        for spk in speakers:
            spk_segs = [s for s in segs if s[2] == spk]
            spk_segs.sort(key=lambda s: s[1] - s[0], reverse=True)
            best = None
            for s in spk_segs[:3]:
                e = embed_window(x, sr, s[0], s[1])
                if e is not None:
                    best = e
                    break
            emb[spk] = best
            print(f"    Cluster {spk}: Embedding {'OK' if best is not None else 'FEHLT'}")

        # Cos-Matrix
        print(f"  Cos-Matrix (ERes2Net):")
        spk_list = sorted(emb.keys())
        for i in spk_list:
            row = []
            for j in spk_list:
                if emb[i] is None or emb[j] is None:
                    row.append("  --  ")
                else:
                    row.append(f"{cosine(emb[i], emb[j]):.3f}")
            print(f"    spk{i}: " + " ".join(row))

        # Unbekannt-Segmente welchem Cluster zuordnen?
        print(f"  Zuordnung der App-'Unbekannt'-Segmente (Zeiten {UNKNOWN_SEGMENTS}):")
        for t in UNKNOWN_SEGMENTS:
            matches = [s for s in segs if s[0] <= t + 0.5 <= s[1] or (s[0] - 0.5 <= t <= s[0] + 0.5)]
            if matches:
                s, e, spk = matches[0]
                print(f"    {t//60:02d}:{t%60:02d} → Cluster {spk} [{s:.1f}-{e:.1f}s]")
            else:
                print(f"    {t//60:02d}:{t%60:02d} → kein Treffer")

if __name__ == "__main__":
    main()