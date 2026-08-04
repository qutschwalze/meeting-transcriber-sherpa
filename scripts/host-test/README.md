# Host-Test der SherpaApp-Pipeline

Lokale Reproduktion der kompletten App-Pipeline (ASR + Rolling-Diarization) mit
sherpa-onnx **1.13.4** – exakt die App-Version und -Konfiguration (Stand 0.5.63).

Zweck: Qualitäts-Checks / A/B-Tests ohne Geräte-Build, z.B. wenn das externe
GPT-Log-Analysetool eine Behauptung aufstellt ("Engine-Fehler", "Cross-Talk",
"falscher Umschaltpunkt") – Claims erst hier gegen Code/Logs/Host verifizieren.

## Dateien

| Datei | Funktion |
|---|---|
| `run_asr.py` | Streaming-ASR (Kroko Zipformer, App-Config: greedy_search, Endpoint 0,4/0,25 s) → `asr_out.json` |
| `run_pipeline.py` | Komplette Rolling-Diarization: 15-s-Chunks + 5 s Overlap (exakte `takeChunk`-Geometrie), FIXED_2, Reconcile, Voice-Bank, Leading-Resolve, TimelineComposer → konsolidierte Sprecher-Timeline |
| `compare_words.py` | WER-Vergleich ASR vs. Referenz-Transkript (Anhang) |
| `REFERENZ_TEST.md` | Befund-Doku des Referenztests (Testclip Di._07.52, 2 Sprecher, Wechsel 61 s) |

## Setup

```bash
# 1) venv + pinned Engine-Version (immer die App-AAR-Version verwenden!)
python3 -m venv /tmp/onnxenv
/tmp/onnxenv/bin/pip install sherpa-onnx==1.13.4

# 2) ASR-Modell (Kroko Deutsch) – wie scripts/download-model.sh
mkdir -p /tmp/kroko-de && cd /tmp/kroko-de
for f in encoder.onnx decoder.onnx joiner.onnx tokens.txt; do
  curl -sL "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06/resolve/main/$f" -o "$f"
done

# 3) Testclip → 16 kHz mono WAV (im Skript-Ordner)
ffmpeg -y -i dein_clip.m4a -ar 16000 -ac 1 -c:a pcm_s16le clip16k.wav
```

Die Diarization-Modelle (`segmentation.onnx`, `embedding.onnx`) kommen direkt aus
`app/src/main/assets/` – kein Download nötig.

## Ablauf

```bash
cd scripts/host-test
/tmp/onnxenv/bin/python run_asr.py clip16k.wav /tmp/kroko-de   # → asr_out.json
/tmp/onnxenv/bin/python run_pipeline.py clip16k.wav asr_out.json  # → pipeline_out.json
/tmp/onnxenv/bin/python compare_words.py asr_out.json           # WER vs. Referenz
```

## Bekannte Befunde (Testclip Di._07.52, 2 Sprecher, Blockwechsel bei ~62 s)

- ASR: WER ≈ 7,8 % ggü. Anhang-Referenz (meiste Diff. = Schreibvarianten).
- Umschaltpunkt: 61,04 s (Referenz 1:02) – FIRST_2SPK-Indikator.
- Titanet-Trennung: A-intra 0,91 / B-intra 0,92 / A-vs-B 0,07.
- 0–10-s-Fragment („Nicht mehr merken, aber") ist akustisch ein eigener Cluster
  (Sim 0,05 zu A) → per `resolveLeadingUnconfirmedSpeakerLabels()` (App 0.5.63)
  dem ersten bestätigten Sprecher zugeordnet.
- Referenz-Rückwechsel bei 1:51 akustisch nicht bestätigbar (Segment liegt näher an B).

## Python-API-Quirks (sherpa-onnx 1.13.4)

- `decode_stream(s)` (nicht `decode`), `get_result(s)` liefert direkt `str`.
- `from_transducer(...)`: kein `rule1_min_utterance_length` – nur `rule1/2_min_trailing_silence`.
- Diarization: `OfflineSpeakerDiarization(config)` (kein `from_config`);
  Ergebnis via `res.sort_by_start_time()`.
- `SpeakerEmbeddingExtractor`: `accept_waveform(16000, x)` – sample_rate ist Pflicht.
