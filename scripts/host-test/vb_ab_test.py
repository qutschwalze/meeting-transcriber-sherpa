#!/usr/bin/env python3
"""A/B-Test: Session 14:43 mit LEERER Bank vs. mit VORBANK aus Session 07:26.
Misst den name-losen Qualitaetsvorteil: erkennt die Bank bekannte Stimmen
wieder (resolve), haelt die Identitaeten stabil, vermeidet Drift-Splits?
Wahrheit: FIXED_5-Diarization der Ziel-WAV, gemappt auf Vorbank-Personen."""
import sys
from vb_common import (load_wav, diarize, embed, cosine, build_chunks, normalize_audio)
from vb_bank import VoiceBank, run_ab

import os
WAV_QUELLE = os.environ.get("VB_SRC", "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_072605.wav")
WAV_ZIEL = os.environ.get("VB_TGT", "/root/sherpa-app/debug-server/uploads/2026-08-21/audio/wav/testaufnahme_20260821_144302.wav")

# ── 1. Vorbank aus Quelle (07:26): FIXED_5-Cluster -> laengstes Fenster -> emb ──
print("== Vorbank aus 07:26 ==", flush=True)
qx = load_wav(WAV_QUELLE)
qsegs = diarize(qx, 5, 0.3)
quad_spk = sorted(set(s[2] for s in qsegs))
vorbank = {}
for spk in quad_spk:
    spk_segs = sorted([s for s in qsegs if s[2] == spk], key=lambda s: s[1] - s[0], reverse=True)
    win = None
    for (s, e, _) in spk_segs:
        if e - s >= 2.0:
            win = (s, min(e, s + 6.0))
            break
    if win is None:
        continue
    emb = embed(qx, win[0], win[1])
    if emb is not None:
        vorbank[spk] = emb
        print(f"  Person {spk}: Fenster {win[0]:.1f}-{win[1]:.1f}s", flush=True)

# ── 2. Wahrheit der Ziel-WAV (14:43): FIXED_5 + Mapping auf Vorbank ──
import os
_cache = f"/tmp/vb_cache_{os.path.basename(WAV_ZIEL)}.npz"
print("== Wahrheit (14:43, FIXED_5) ==", flush=True)
zx = load_wav(WAV_ZIEL)
if os.path.exists(_cache):
    import numpy as np
    c = np.load(_cache, allow_pickle=True)
    zsegs = [(s, e, int(sp)) for s, e, sp in c["zsegs"]]
    tm = c["truth_map"].item()
    truth_map = {int(k): (v if v >= 0 else None) for k, v in tm.items()}
    print(f"  [Cache] Ziel-Cluster-Mapping: {truth_map}", flush=True)
else:
    zsegs = diarize(zx, 5, 0.3)
    zspk = sorted(set(s[2] for s in zsegs))
    truth_map = {}   # ziel-cluster -> vorbank-person oder None
    for spk in zspk:
        spk_segs = sorted([s for s in zsegs if s[2] == spk], key=lambda s: s[1] - s[0], reverse=True)
        win = None
        for (s, e, _) in spk_segs:
            if e - s >= 2.0:
                win = (s, min(e, s + 6.0))
                break
        if win is None:
            truth_map[spk] = None
            print(f"  Ziel-Cluster {spk}: kein Fenster", flush=True)
            continue
        emb = embed(zx, win[0], win[1])
        if emb is None:
            truth_map[spk] = None
            continue
        best = max(vorbank.items(), key=lambda kv: cosine(emb, kv[1]))
        sim = cosine(emb, best[1])
        truth_map[spk] = best[0] if sim >= 0.55 else None
        print(f"  Ziel-Cluster {spk}: -> Person {truth_map[spk]} (sim={sim:.3f})", flush=True)


def truth_at(t):
    for (s, e, spk) in zsegs:
        if s <= t < e:
            return truth_map.get(spk, None)
    return None


# ── 3. Chunk-Segmente der Ziel-WAV (App-Geometrie) ──
print("== Chunks (15s/5s) ==", flush=True)
total = len(zx) / 16000.0
segments = []
import numpy as np
if os.path.exists(_cache):
    c = np.load(_cache, allow_pickle=True)
    for i in range(len(c["seg_start"])):
        emb = c["seg_emb"][i]
        segments.append({"start": float(c["seg_start"][i]), "end": float(c["seg_end"][i]),
                         "dur": float(c["seg_dur"][i]),
                         "emb": None if np.isnan(emb).any() else emb})
    print(f"  [Cache] {len(segments)} Segmente geladen", flush=True)
else:
    chunks = build_chunks(total)
    for ci, c in enumerate(chunks):
        cwin = zx[int(c["start"] * 16000): int(c["end"] * 16000)]
        cseg = diarize(normalize_audio(cwin), -1, 0.35)
        for (s, e, spk) in cseg:
            abs_s = c["start"] + s
            abs_e = c["start"] + e
            dur = abs_e - abs_s
            emb = embed(zx, abs_s, abs_e) if dur >= 1.0 else None
            segments.append({"start": abs_s, "end": abs_e, "dur": dur, "emb": emb})
        print(f"  Chunk {ci}: [{c['start']:.0f}-{c['end']:.0f}] {len(cseg)} Segmente", flush=True)
    print(f"  GESAMT: {len(segments)} Segmente", flush=True)
    np.savez(_cache, zsegs=[(s, e, sp) for (s, e, sp) in zsegs],
             truth_map={k: (v if v is not None else -1) for k, v in truth_map.items()},
             seg_start=[s["start"] for s in segments], seg_end=[s["end"] for s in segments],
             seg_dur=[s["dur"] for s in segments],
             seg_emb=[(s["emb"] if s["emb"] is not None else np.full(512, np.nan)) for s in segments])
    print(f"  [Cache geschrieben]", flush=True)

# ── 4. A/B-Laeufe ──
stats_base, assign_base = run_ab(segments, {})
print("== Lauf A: leere Bank ==", flush=True)
print(f"  neue IDs: {stats_base['new_ids_created']}, bestaetigte Bank: {stats_base['n_confirmed_bank']}, "
      f"ohne Zuordnung: {stats_base['n_unassigned']}", flush=True)

stats_vb, assign_vb = run_ab(segments, vorbank)
print("== Lauf B: Vorbank ==", flush=True)
print(f"  resolve auf Vorbank: {stats_vb['resolve_to_vorbank']}/{stats_vb['n_segments']} Segmente, "
      f"neue IDs: {stats_vb['new_ids_created']}, bestaetigte Bank: {stats_vb['n_confirmed_bank']}, "
      f"ohne Zuordnung: {stats_vb['n_unassigned']}", flush=True)


def report(assign, label, has_vorbank=False):
    """Konsistenz pro Wahrheits-Cluster + korrekt-Anteil."""
    from collections import defaultdict
    per_cluster = defaultdict(lambda: defaultdict(float))  # cluster -> gid -> sek
    correct_known = 0.0
    correct_new = 0.0
    wrong = 0.0
    unassigned = 0.0
    known_sec = 0.0
    for a in assign:
        t0, t1 = a["start"], a["end"]
        mid = (t0 + t1) / 2
        w = truth_at(mid)
        dur = t1 - t0
        if a["gid"] is None:
            unassigned += dur
            continue
        gid = a["gid"]
        p = gid if gid in vorbank else None  # gid IST die Vorbank-Person
        if w is not None:
            known_sec += dur
            if has_vorbank and p is not None and p == w:
                correct_known += dur
            else:
                wrong += dur
        else:
            if not has_vorbank or gid not in vorbank:
                correct_new += dur
            else:
                wrong += dur
        if w is not None:
            per_cluster[w][gid] += dur
    print(f"-- {label} --")
    if has_vorbank:
        print(f"  Zeit bekannt-Person korrekt: {correct_known:.1f}s ({100*correct_known/max(known_sec,1):.1f}% der bekannten Zeit)")
        print(f"  unbekannte Person korrekt neu: {correct_new:.1f}s | falsch zugeordnet: {wrong:.1f}s | ohne Zuordnung: {unassigned:.1f}s")
        total_time = sum(a["end"] - a["start"] for a in assign)
        print(f"  Gesamt korrekt: {100*(correct_known+correct_new)/max(total_time,1):.1f}% der Segmentzeit")
    for w, gids in sorted(per_cluster.items()):
        dom = max(gids.items(), key=lambda kv: kv[1])
        n_ids = len(gids)
        print(f"  Wahrheits-Person {w}: dominant gid={dom[0]} ({100*dom[1]/max(sum(gids.values()),1):.0f}%), "
              f"IDs die diese Person bekamen: {n_ids}" + ("  <-- SPLIT/DRIFT" if n_ids > 1 else ""))


report(assign_base, "Lauf A (leere Bank)", has_vorbank=False)
report(assign_vb, "Lauf B (Vorbank)", has_vorbank=True)