#!/usr/bin/env python3
"""Meeting-Split-Test (07.08., ~24 min, 10 Export-Sprecher = Phantom-Fall):
Haelfte 1 simuliert eine abgeschlossene Session (Auto-Enroll bestaetigter
Kontakte -> persistente Bank). Haelfte 2 laeuft dann MIT vs. OHNE Vorbank.
Metriken: erzeugte IDs, resolve-Rate, bestaetigte Personen, Konsistenz
gegen full-buffer FIXED_5-Wahrheit der Haelfte 2."""
import os
from vb_common import (load_wav, diarize, embed, cosine, build_chunks, normalize_audio)
from vb_bank import VoiceBank, run_ab

WAV = os.environ.get("VB_MEETING_WAV", "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260807_110319.wav")
SPLIT_S = float(os.environ.get("VB_MEETING_SPLIT", "716"))  # Haelfte 1 = 0..SPLIT_S

x = load_wav(WAV)
h1 = x[:int(SPLIT_S * 16000)]
h2 = x[int(SPLIT_S * 16000):]
print(f"Meeting: {len(x)/16000:.0f}s -> H1 {len(h1)/16000:.0f}s, H2 {len(h2)/16000:.0f}s", flush=True)


import numpy as np
_cache_meeting = f"/tmp/vb_meeting_{os.path.basename(WAV)}.npz"


def chunk_segments(sig, tag):
    """Chunk-Segmente (App-Geometrie) mit Embeddings; gecacht unter /tmp."""
    cache_key_start = f"seg_start_{tag}"
    total = len(sig) / 16000.0
    if os.path.exists(_cache_meeting):
        c = np.load(_cache_meeting, allow_pickle=True)
        if cache_key_start in c.files:
            segs = []
            for i in range(len(c[cache_key_start])):
                emb = c[f"seg_emb_{tag}"][i]
                segs.append({"start": float(c[cache_key_start][i]), "end": float(c[f"seg_end_{tag}"][i]),
                             "dur": float(c[f"seg_dur_{tag}"][i]),
                             "emb": None if np.isnan(emb).any() else emb})
            print(f"  [{tag}] {len(segs)} Segmente aus Cache", flush=True)
            return segs
    segs = []
    for ci, c in enumerate(build_chunks(total)):
        cwin = sig[int(c["start"] * 16000): int(c["end"] * 16000)]
        cseg = diarize(normalize_audio(cwin), -1, 0.35)
        for (s, e, spk) in cseg:
            abs_s, abs_e = c["start"] + s, c["start"] + e
            dur = abs_e - abs_s
            emb = embed(sig, abs_s, abs_e) if dur >= 1.0 else None
            segs.append({"start": abs_s, "end": abs_e, "dur": dur, "emb": emb})
        print(f"  H-Chunk {ci}: [{c['start']:.0f}-{c['end']:.0f}] {len(cseg)} Seg ", flush=True)
    try:
        old = dict(np.load(_cache_meeting, allow_pickle=True)) if os.path.exists(_cache_meeting) else {}
        save = dict(old)
        save[cache_key_start] = np.array([s["start"] for s in segs])
        save[f"seg_end_{tag}"] = np.array([s["end"] for s in segs])
        save[f"seg_dur_{tag}"] = np.array([s["dur"] for s in segs])
        save[f"seg_emb_{tag}"] = np.array([(s["emb"] if s["emb"] is not None else np.full(512, np.nan)) for s in segs])
        np.savez(_cache_meeting, **save)
    except Exception as e:
        print(f"  [Cache-Write fehlgeschlagen: {e}]", flush=True)
    return segs


def run_with_bank(segments, vorbank):
    """Wie run_ab, gibt aber die Bank zurueck (fuer Auto-Enroll-Profile)."""
    bank = VoiceBank()
    if vorbank:
        bank.preload(vorbank)
    next_gid = max(vorbank.keys(), default=-1) + 1 if vorbank else 0
    resolve = 0
    new_ids = 0
    for seg in segments:
        if seg["emb"] is None or seg["dur"] < 2.0:
            continue
        hit = bank.identify(seg["emb"])
        if hit is not None:
            if vorbank and hit[0] in vorbank:
                resolve += 1
            continue
        gid = next_gid
        next_gid += 1
        bank.enroll(gid, seg["emb"], seg["dur"])
        new_ids += 1
    return bank, resolve, new_ids


print("== H1: Auto-Enroll-Lauf (leere Bank) ==", flush=True)
s1 = chunk_segments(h1, "h1")
bank1, res1, new1 = run_with_bank(s1, {})
print(f"H1: {len(s1)} Segmente, {new1} neue IDs, bestaetigt: {bank1.n_confirmed()}", flush=True)
vorbank = {gid: emb for gid, emb in bank1.confirmed.items()}
print(f"Vorbank fuer H2: {len(vorbank)} Profile (confirmed)", flush=True)

print("== H2: Wahrheit (FIXED_5, full-buffer) ==", flush=True)
if os.path.exists(_cache_meeting) and "wsegs" in np.load(_cache_meeting, allow_pickle=True).files:
    c = np.load(_cache_meeting, allow_pickle=True)
    wsegs = [(s, e, int(sp)) for s, e, sp in c["wsegs"]]
    print(f"  [Cache] {len(wsegs)} Wahrheits-Segmente", flush=True)
else:
    wsegs = diarize(h2, 5, 0.3)
    try:
        old = dict(np.load(_cache_meeting, allow_pickle=True)) if os.path.exists(_cache_meeting) else {}
        old["wsegs"] = np.array([(s, e, sp) for (s, e, sp) in wsegs])
        np.savez(_cache_meeting, **old)
    except Exception as e:
        print(f"  [Cache-Write fehlgeschlagen: {e}]", flush=True)
wspk = sorted(set(s[2] for s in wsegs))
w_emb = {}
for spk in wspk:
    spk_segs = sorted([s for s in wsegs if s[2] == spk], key=lambda s: s[1] - s[0], reverse=True)
    win = None
    for (s, e, _) in spk_segs:
        if e - s >= 2.0:
            win = (s, min(e, s + 6.0))
            break
    if win is None:
        continue
    emb = embed(h2, win[0], win[1])
    if emb is not None:
        w_emb[spk] = emb
        # Mapping auf Vorbank-Person fuer die Konsistenz-Analyse
        best = max(vorbank.items(), key=lambda kv: cosine(emb, kv[1])) if vorbank else (None, None)
        sim = cosine(emb, best[1]) if best[1] is not None else 0.0
        print(f"  W-Cluster {spk}: -> Vorbank-Person {best[0] if best[0] is not None else None} (sim={sim:.3f})", flush=True)

print("== H2: Chunks ==", flush=True)
s2 = chunk_segments(h2, "h2")

print("== A/B H2 ==", flush=True)
bankA, _, newA = run_with_bank(s2, {})
print(f"Lauf A (leere Bank): {newA} neue IDs erzeugt", flush=True)
bankB, resB, newB = run_with_bank(s2, vorbank)
print(f"Lauf B (Vorbank): resolve auf Vorbank={resB}/{len(s2)}, neue IDs={newB}, bestaetigt gesamt={bankB.n_confirmed()}", flush=True)

# Konsistenz-Analyse fuer Lauf B (und A) gegen W-Cluster
from collections import defaultdict


def analyze(segments, bank, label):
    per_cluster = defaultdict(lambda: defaultdict(float))
    known_ok = known_time = 0.0
    for seg in segments:
        if seg["emb"] is None:
            continue
        mid = (seg["start"] + seg["end"]) / 2
        w = None
        for (s, e, spk) in wsegs:
            if s <= mid < e:
                w = spk
                break
        hit = bank.identify(seg["emb"])
        gid = hit[0] if hit else None
        dur = seg["dur"]
        if w is not None and w in w_emb:
            known_time += dur
            tp = gid if (gid in vorbank) else None  # gid IST die Vorbank-Person
            want = max(vorbank.items(), key=lambda kv: cosine(w_emb[w], kv[1]))[0] if w_emb[w] is not None else None
            if tp is not None and want is not None and tp == want:
                known_ok += dur
            per_cluster[w][gid if gid is not None else -1] += dur
    print(f"-- {label} --")
    if known_time > 0:
        print(f"  bekannte-Zeit korrekt: {known_ok:.0f}s / {known_time:.0f}s ({100*known_ok/max(known_time,1):.1f}%)")
    for w, gids in sorted(per_cluster.items()):
        dom = max(gids.items(), key=lambda kv: kv[1])
        print(f"  W-Cluster {w}: dominant gid={dom[0]} ({100*dom[1]/max(sum(gids.values()),1):.0f}%), IDs={len(gids)}" + (" <-- SPLIT" if len(gids) > 1 else ""))


analyze(s2, bankA, "Lauf A (leere Bank)")
analyze(s2, bankB, "Lauf B (Vorbank)")