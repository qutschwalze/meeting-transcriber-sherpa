#!/usr/bin/env python3
"""Vollständige Host-Simulation der SherpaApp-Diarization-Pipeline (App 0.5.60):

ChunkedAudioBuffer (20s chunk + 5s overlap) → DiarizationChunkWorker:
  normalizeAudio → engine.process (FIXED_2: numClusters=2, threshold=0.3,
  minOn=0.1, minOff=0.1) → Retry-Offsets (3/5/7/10s) → Time-Shift →
  RollingReconciler (Zone, Fragment-Filter 0.4s, minMatch 0.3s, Greedy,
  Majority-Voting) → mergeIntoGlobalBestand.
Danach TimelineComposer: compactRawSegmentsBeforeAssignment →
  splitLongSpeakerSegments → assignSpeakersToRawSegments (300ms/35%) →
  mergeSegmentsForDisplay (1.2s).
"""
import json
import os
import sys
import wave

import numpy as np
import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(BASE, "..", ".."))
WAV = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BASE, "clip16k.wav")
ASR_JSON = sys.argv[2] if len(sys.argv) > 2 else os.path.join(BASE, "asr_out.json")
SEG_MODEL = os.path.join(REPO, "app/src/main/assets/segmentation.onnx")
# Phase 6: Embedding-Modell per Env wählbar (z.B. EMB_MODEL=/tmp/sherpa-test/eres2net.onnx)
EMB_MODEL = os.environ.get("EMB_MODEL", os.path.join(REPO, "app/src/main/assets/embedding.onnx"))
CHUNK_SEC, OVERLAP_SEC = 15.0, 5.0  # LiveViewModel: chunkSec=15f, Overlap-Default 5f
SAMPLE_RATE = 16000
EPS = 0.01
RETRY_OFFSETS = [3.0, 5.0, 7.0, 10.0]
MIN_MATCH_OVERLAP = 0.3
MIN_FRAGMENT = 0.4
VB_MATCH_THRESHOLD = 0.62
VB_PENDING_CONFIRM = 0.35
VB_MIN_ENROLL_SEC = 2.0
VB_MIN_IDENTIFY_SEC = 2.0


# ── normalizeAudio (1:1 aus DiarizationChunkWorker.kt) ────────────────────────
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


# ── RollingReconciler (1:1 aus RollingReconciler.kt) ──────────────────────────
MIN_AGGREGATE_SEC = 2.0


def aggregate_fragments(local):
    """0.5.64: Alle lokalen Segmente < min_frag → pro Engine-ID aggregieren,
    wenn die Redezeit zusammen >= MIN_AGGREGATE_SEC (konsistent mit der Bank)."""
    if not local:
        return []
    by_spk = {}
    for s in local:
        by_spk.setdefault(s["speaker"], []).append(s)
    out = []
    for spk, segs in by_spk.items():
        total = sum(s["end"] - s["start"] for s in segs)
        if total < MIN_AGGREGATE_SEC:
            continue
        out.append({"start": min(s["start"] for s in segs),
                    "end": max(s["end"] for s in segs), "speaker": spk})
    return out


def reconcile(local, zone_start, zone_end, prev_global, min_match=MIN_MATCH_OVERLAP, min_frag=MIN_FRAGMENT):
    if not local:
        return [], {}, set(), 0.0
    sig_local = [s for s in local if (s["end"] - s["start"]) >= min_frag]
    sig_global = [s for s in prev_global if (s["end"] - s["start"]) >= min_frag]
    if not sig_local:
        # 0.5.64: Fragment-Aggregation statt komplettem Verwerfen
        aggregated = aggregate_fragments(local)
        if not aggregated:
            return [], {}, set(), 0.0
        return reconcile(aggregated, zone_start, zone_end, prev_global, min_match, min_frag)

    global_ranges = {}
    max_gid = -1
    for seg in sig_global:
        if seg["end"] <= seg["start"]:
            continue
        global_ranges.setdefault(seg["speaker"], []).append((seg["start"], seg["end"]))
        max_gid = max(max_gid, seg["speaker"])

    votes = {}
    zone_total = 0.0
    for ls in sig_local:
        c_start = max(ls["start"], zone_start)
        c_end = min(ls["end"], zone_end)
        zo = max(0.0, min(zone_end, ls["end"]) - max(zone_start, ls["start"]))
        if zo <= 0:
            continue
        zone_total += zo
        for gid, ranges in global_ranges.items():
            acc = 0.0
            for (gs, ge) in ranges:
                acc += max(0.0, min(c_end, ge) - max(c_start, gs))
            if acc > 0:
                v = votes.setdefault(ls["speaker"], {})
                v[gid] = v.get(gid, 0.0) + acc

    pairs = sorted(
        ((l, g, o) for l, gv in votes.items() for g, o in gv.items()),
        key=lambda t: -t[2],
    )
    mapping = {}
    assigned_l, assigned_g = set(), set()
    for (l, g, o) in pairs:
        if o < min_match:
            break
        if l in assigned_l or g in assigned_g:
            continue
        mapping[l] = g
        assigned_l.add(l)
        assigned_g.add(g)

    new_ids = set()
    next_new = max_gid + 1
    for l in sorted({s["speaker"] for s in sig_local}):
        if l not in assigned_l:
            mapping[l] = next_new
            new_ids.add(l)
            next_new += 1

    mapped = [{**s, "speaker": mapping.get(s["speaker"], s["speaker"])} for s in sig_local]
    return mapped, mapping, new_ids, zone_total


# ── mergeIntoGlobalBestand (1:1 aus DiarizationChunkWorker.kt) ────────────────
def merge_bestand(bestand, new_mapped, zone_start, zone_end):
    zs, ze = zone_start - EPS, zone_end + EPS
    kept = []
    for seg in bestand:
        if seg["end"] <= zs:
            kept.append(seg)
        elif seg["start"] >= ze:
            continue
        elif seg["start"] < zone_start:
            kept.append({**seg, "end": zone_start})
        else:
            continue
    added = [{"start": s["start"], "end": s["end"], "speaker": s["speaker"]} for s in new_mapped]
    return sorted(kept + added, key=lambda s: s["start"])


# ── Chunks bauen (ChunkedAudioBuffer.takeChunk/takeRemainingChunk) ────────────
with wave.open(WAV, "rb") as w:
    pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
total = len(pcm) / SAMPLE_RATE
asr = json.load(open(ASR_JSON))["segments"]

chunks = []
prev_end = 0.0
# takeChunk: nextEnd = prevEnd + chunkSec; window = [nextEnd-chunkSec-overlap, nextEnd]
while True:
    next_end = prev_end + CHUNK_SEC
    if next_end > total + 0.05:
        break
    is_first = prev_end == 0.0
    win_start = next_end - CHUNK_SEC - (0.0 if is_first else OVERLAP_SEC)
    chunks.append({"start": win_start, "end": next_end, "overlap": 0.0 if is_first else OVERLAP_SEC, "is_first": is_first})
    prev_end = next_end
# takeRemainingChunk: [max(frame0, prevEnd-overlap), min(newest, prevEnd+chunkSec)]
if prev_end < total - 0.05:
    rest_start = max(0.0, prev_end - OVERLAP_SEC)
    rest_end = min(total, prev_end + CHUNK_SEC)
    if rest_end > rest_start + 0.05:
        chunks.append({"start": rest_start, "end": rest_end, "overlap": prev_end - rest_start, "is_first": False})

print(f"# chunks: {len(chunks)}", file=sys.stderr)
for c in chunks:
    print(f"  [{c['start']:.1f}-{c['end']:.1f}] overlap={c['overlap']}", file=sys.stderr)

# ── SessionVoiceBank (1:1 aus SessionVoiceBank.kt, Hebel G) ───────────────────
class VoiceBank:
    def __init__(self, extractor):
        self.ext = extractor
        self.voiceprints = {}   # globalId -> np.ndarray
        self.enroll_counts = {}
        self.pending = {}       # globalId -> np.ndarray

    @staticmethod
    def cosine(a, b):
        if a is None or b is None or len(a) != len(b):
            return 0.0
        dot = float(np.dot(a, b))
        na = float(np.dot(a, a)) ** 0.5
        nb = float(np.dot(b, b)) ** 0.5
        return dot / (na * nb) if na > 0 and nb > 0 else 0.0

    def _embed(self, samples):
        s = self.ext.create_stream()
        s.accept_waveform(16000, samples)
        s.input_finished()
        if not self.ext.is_ready(s):
            return None
        return np.asarray(self.ext.compute(s), dtype=np.float32)

    def identify(self, samples, confirmed_only=False):
        if samples.size == 0 or (not self.voiceprints and not self.pending):
            return None
        if samples.size < int(VB_MIN_IDENTIFY_SEC * 16000):
            return None
        emb = self._embed(samples)
        if emb is None:
            return None
        best_id, best_sim, best_is_pending = None, 0.0, False
        sims = []
        for gid, vp in self.voiceprints.items():
            sim = self.cosine(emb, vp)
            sims.append((gid, sim, "confirmed"))
            if sim > best_sim:
                best_sim, best_id, best_is_pending = sim, gid, False
        if not confirmed_only:
            for gid, p in self.pending.items():
                sim = self.cosine(emb, p)
                sims.append((gid, sim, "pending"))
                if sim > best_sim:
                    best_sim, best_id, best_is_pending = sim, gid, True
        # 0.5.76-Abgleich mit der Kotlin-identify: bestSim startet bei 0 (nicht bei
        # matchThreshold) und die Schwelle ist effektiv (0.35 pending / 0.62 confirmed);
        # ohne Match wird null zurueckgegeben (Return-Bug war in der App, hier nie).
        threshold = VB_PENDING_CONFIRM if best_is_pending else VB_MATCH_THRESHOLD
        if best_id is not None and best_sim > threshold:
            if best_is_pending and best_id in self.pending:
                self._confirm(best_id, emb)
            return best_id
        return None

    def enroll(self, gid, samples, dur_ms):
        if dur_ms < VB_MIN_ENROLL_SEC * 1000 or samples.size == 0:
            return False
        emb = self._embed(samples)
        if emb is None:
            return False
        if gid in self.voiceprints:
            c = self.enroll_counts.get(gid, 1)
            self.voiceprints[gid] = (self.voiceprints[gid] * c + emb) / (c + 1)
            self.enroll_counts[gid] = c + 1
            return True
        if gid in self.pending:
            sim = self.cosine(self.pending[gid], emb)
            if sim >= VB_PENDING_CONFIRM:
                self._confirm(gid, emb)
                return True
            self.pending[gid] = emb
            return False
        self.pending[gid] = emb
        # Phase 6 (Quick-Confirm): langer 1. Kontakt (>= QUICK_CONFIRM_SEC) wird
        # sofort bestätigt – etabliert einmalige Kurzbeiträge (z.B. eine Stimme
        # mit 6s Redezeit in einer Podiumsrunde) als eigenen Sprecher. Kurze
        # Fragmente (< 4s) bleiben pending (konservativ).
        if QUICK_CONFIRM_SEC > 0 and dur_ms >= QUICK_CONFIRM_SEC * 1000:
            self._confirm(gid, emb)
            return True
        return False

    def _confirm(self, gid, emb):
        p = self.pending.pop(gid, None)
        if p is None:
            return
        self.voiceprints[gid] = (p + emb) / 2.0
        self.enroll_counts[gid] = 2

    @property
    def speaker_count(self):
        return len(self.voiceprints)


# ── Diarization-Engine (einmal instanziieren, pro Chunk process) ──────────────
# Phase 6: Clustering-Modus per Umgebungsvariable testbar
#   NUM_CLUSTERS=-1 (oder 0) = AUTO (threshold-basiert), 2 = FIXED_2, 4 = FIXED_4
#   THRESHOLD=0.3 (Standard)
import os
NUM_CLUSTERS = int(os.environ.get("NUM_CLUSTERS", "2"))
THRESHOLD = float(os.environ.get("THRESHOLD", "0.3"))
# Phase 6: Quick-Confirm – langer 1. Kontakt sofort bestätigen (0 = aus)
QUICK_CONFIRM_SEC = float(os.environ.get("QUICK_CONFIRM_SEC", "0"))
cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
    segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
        pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(SEG_MODEL)),
    embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=EMB_MODEL, num_threads=2, provider="cpu"),
    clustering=sherpa_onnx.FastClusteringConfig(num_clusters=NUM_CLUSTERS, threshold=THRESHOLD),
    min_duration_on=0.1,
    # 0.5.71-Abgleich: App nutzt minDurationOff=0.05 (SpeakerDiarizationEngine.kt),
    # nicht 0.1 – korrigiert, damit die Simulation die echte Segmentierung
    # (und damit die 2s-Bank-Schwelle) exakt nachbildet.
    min_duration_off=0.05,
)
diarizer = sherpa_onnx.OfflineSpeakerDiarization(cfg)
emb_extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
    sherpa_onnx.SpeakerEmbeddingExtractorConfig(EMB_MODEL, num_threads=2, provider="cpu"))
voice_bank = VoiceBank(emb_extractor)

global_bestand = []
for ci, c in enumerate(chunks):
    s0 = int(c["start"] * SAMPLE_RATE)
    s1 = int(c["end"] * SAMPLE_RATE)
    raw = pcm[s0:s1]
    norm = normalize_audio(raw)
    segs = diarizer.process(norm).sort_by_start_time()
    retry_offset = 0.0
    if not segs and norm.size >= 10 * SAMPLE_RATE:
        for off in RETRY_OFFSETS:
            os_ = int(off * SAMPLE_RATE)
            if os_ >= norm.size:
                continue
            rs = diarizer.process(norm[os_:]).sort_by_start_time()
            if rs:
                print(f"  chunk {ci}: RETRY offset {off}s -> {len(rs)} segs", file=sys.stderr)
                segs = rs
                retry_offset = off
                break
    # absolute (time-shifted) lokale Segmente – wie im Worker
    absolute = [{"start": s.start + c["start"] + retry_offset, "end": s.end + c["start"] + retry_offset, "speaker": s.speaker}
                for s in segs]
    if not absolute:
        print(f"  chunk {ci}: 0 segments (kein Retry-Erfolg)", file=sys.stderr)
        continue

    zone_start, zone_end = c["start"], c["start"] + c["overlap"]
    mapped, mapping, new_ids, _ = reconcile(absolute, zone_start, zone_end, global_bestand)

    # ── Hebel G (Phase 6): Voice-Bank für ALLE lokalen IDs befragen ──
    # Nicht nur new-IDs: Auch gemappte Segmente werden gegen die Bank identifiziert
    # (wiederkehrende Stimme → Mapping auf die Bank-ID = ID-Stabilität; 2. Kontakt
    # gegen ein pending → confirmPending passiert in identify()). Nur bei leerer
    # Bank entfällt identify (1. Kontakte werden direkt als pending enrolled).
    final_mapping = dict(mapping)
    final_new = set(new_ids)
    all_local = sorted(set(s["speaker"] for s in absolute))
    if all_local:
        first_contacts = []
        skipped_count = 0
        bank_was_empty = voice_bank.speaker_count + len(voice_bank.pending) == 0
        # Frische globale IDs für Fehlzuordnungen (müssen über allen Bestands-IDs liegen)
        next_fresh_global_id = (max([s["speaker"] for s in global_bestand], default=-1) + 1)
        for local_id in all_local:
            segs_of = [s for s in absolute if s["speaker"] == local_id]
            if not segs_of:
                continue
            best = max(segs_of, key=lambda s: s["end"] - s["start"])
            # Samples aus dem ORIGINAL-Audio (nicht normalisiert!)
            rel_start = max(0.0, min(best["start"] - c["start"], c["end"] - c["start"]))
            rel_end = max(0.0, min(best["end"] - c["start"], c["end"] - c["start"]))
            i0 = int(rel_start * SAMPLE_RATE)
            i1 = int(rel_end * SAMPLE_RATE)
            samples = raw[i0:i1] if i1 > i0 else np.array([], dtype=np.float32)
            if samples.size == 0:
                continue
            dur_ms = (best["end"] - best["start"]) * 1000.0
            # Phase 6: bank_nonempty pro lokaler ID NEU prüfen – nach einem Enroll
            # ist die Bank nicht mehr leer, der nächste Kontakt (z.B. der
            # Monologue-Split derselben Stimme) wird dann identified und auf die
            # bestehende pending-ID gemappt (statt als eigene Stimme enrolled).
            bank_nonempty = voice_bank.speaker_count + len(voice_bank.pending) > 0
            if bank_nonempty:
                matched = voice_bank.identify(samples)
                if matched is not None:
                    final_mapping[local_id] = matched
                    final_new.discard(local_id)
                    print(f"  chunk {ci}: VOICE_BANK identify local={local_id} -> global={matched} (dur={dur_ms:.0f}ms)", file=sys.stderr)
                    continue
                # Phase-6-Diagnose: warum kein Match? (sims gegen Bank + Reconciler-Zuordnung)
                emb_diag = voice_bank._embed(samples)
                sims_diag = {}
                for g, vp in voice_bank.voiceprints.items():
                    sims_diag[f"c{g}"] = round(voice_bank.cosine(emb_diag, vp), 3)
                for g, p in voice_bank.pending.items():
                    sims_diag[f"p{g}"] = round(voice_bank.cosine(emb_diag, p), 3)
                print(f"  chunk {ci}: local={local_id} KEIN Match dur={dur_ms:.0f}ms new={local_id in new_ids} "
                      f"reconciler->global={mapping.get(local_id)} sims={sims_diag}", file=sys.stderr)
            if local_id in new_ids:
                new_gid = mapping.get(local_id)
                if new_gid is not None:
                    voice_bank.enroll(new_gid, samples, dur_ms)
                    first_contacts.append(new_gid)
            else:
                # Phase 6: Fehlzuordnungs-/Phantom-Regel – der Reconciler hat die
                # lokale ID auf eine bestehende globale ID gemappt, aber die Bank
                # erkennt die Stimme nicht (identify=null). Zwei Fälle:
                # a) Phantom-ID: Ziel-ID existiert in der Bank NICHT (der 1. Kontakt
                #    dieser Stimme war zu kurz fuer das Enrollment < 2s) -> es ist
                #    eine echte neue Stimme -> unter der Ziel-ID enrollen.
                # b) Fehlzuordnung auf echte Bank-ID (Zone-Vote): z.B. Sprecher B
                #    wird auf Sprecher A gemappt, obwohl die Stimmen verschieden
                #    sind -> frische ID + enroll (2. Kontakt bestaetigt sie dann).
                target_gid = mapping.get(local_id)
                if target_gid is not None and target_gid not in voice_bank.voiceprints and target_gid not in voice_bank.pending:
                    voice_bank.enroll(target_gid, samples, dur_ms)
                    print(f"  chunk {ci}: VOICE_BANK enroll-Phantom local={local_id} -> global={target_gid} (dur={dur_ms:.0f}ms)", file=sys.stderr)
                elif target_gid is not None:
                    fresh = next_fresh_global_id
                    next_fresh_global_id += 1
                    final_mapping[local_id] = fresh
                    voice_bank.enroll(fresh, samples, dur_ms)
                    print(f"  chunk {ci}: VOICE_BANK enroll-Fehlzuordnung local={local_id} -> neue ID {fresh} (dur={dur_ms:.0f}ms, alte Ziel-ID {target_gid} in Bank)", file=sys.stderr)
        if first_contacts and bank_was_empty:
            print(f"  chunk {ci}: voice_bank enrolls (leer->pending): {sorted(set(first_contacts))}", file=sys.stderr)

    corrected = ([{**s, "speaker": final_mapping.get(s["speaker"], s["speaker"])} for s in absolute]
                 if final_mapping != mapping else mapped)
    if corrected:
        global_bestand = merge_bestand(global_bestand, corrected, zone_start, zone_end)
    print(f"  chunk {ci}: engine={len(absolute)} mapped={len(mapped)} new={sorted(final_new)} "
          f"bestand={len(global_bestand)} vb_confirmed={voice_bank.speaker_count} vb_pending={sorted(voice_bank.pending)}", file=sys.stderr)

print(f"\n# final global segments: {len(global_bestand)}", file=sys.stderr)
for g in global_bestand:
    print(f"  [{g['start']:6.2f}-{g['end']:6.2f}] spk{g['speaker']}", file=sys.stderr)

# ── EXPERIMENT: Voice-Bank-Final-Resolve für unbestätigte Bestand-IDs ─────────
# Analog zum identifiy-Pfad im Worker: IDs, die die Bank NICHT bestätigt hat
# (pending oder nie gesehen), werden im Final gegen die bestätigten Voiceprints
# identifiziert. Match über 0.62 → ID wird auf den bestätigten Sprecher umgemappt
# (Artefakt-Auflösung, konservativ: nur bestätigte Ziele).
unconfirmed_ids = sorted({g["speaker"] for g in global_bestand} - set(voice_bank.voiceprints.keys()))
if unconfirmed_ids:
    print(f"  FINAL_RESOLVE: unbestätigte IDs {unconfirmed_ids} gegen Bank {sorted(voice_bank.voiceprints.keys())} identifizieren", file=sys.stderr)
    for gid in unconfirmed_ids:
        segs_of = [g for g in global_bestand if g["speaker"] == gid]
        best = max(segs_of, key=lambda s: s["end"] - s["start"])
        i0 = int(best["start"] * SAMPLE_RATE)
        i1 = int(best["end"] * SAMPLE_RATE)
        samples = pcm[i0:i1] if i1 > i0 else np.array([], dtype=np.float32)
        matched = voice_bank.identify(samples, confirmed_only=True)
        if matched is not None:
            print(f"  FINAL_RESOLVE: spk{gid} -> spk{matched} (Bestand ummappen, confirmed-only)", file=sys.stderr)
            for g in global_bestand:
                if g["speaker"] == gid:
                    g["speaker"] = matched
            # konsolidieren (überlappende Bereiche gleicher ID zusammenfassen)
            global_bestand.sort(key=lambda s: s["start"])
            merged_b = []
            for g in global_bestand:
                if merged_b and merged_b[-1]["speaker"] == g["speaker"] and g["start"] <= merged_b[-1]["end"] + EPS:
                    merged_b[-1]["end"] = max(merged_b[-1]["end"], g["end"])
                else:
                    merged_b.append(g)
            global_bestand = merged_b
        else:
            print(f"  FINAL_RESOLVE: spk{gid} -> KEIN Match (bleibt unbestätigt)", file=sys.stderr)
    print(f"# global segments nach FINAL_RESOLVE: {len(global_bestand)}", file=sys.stderr)
    for g in global_bestand:
        print(f"  [{g['start']:6.2f}-{g['end']:6.2f}] spk{g['speaker']}", file=sys.stderr)

# ── EXPERIMENT: Leading-Resolve (User-Entscheidung) ───────────────────────────
# Heuristik: Unbestätigte Segmente, die KOMPLETT VOR dem ersten bestätigten
# Sprecher liegen (Prä-Segmente), werden dem ERSTEN bestätigten Sprecher
# zugeordnet. Konservativ: nur Prä-Segmente, keine intra-Fragmente.
confirmed_ids = set(voice_bank.voiceprints.keys())
if confirmed_ids:
    confirmed_segs = [g for g in global_bestand if g["speaker"] in confirmed_ids]
    first_confirmed_start = min((g["start"] for g in confirmed_segs), default=None)
    first_confirmed_id = min(confirmed_ids)
    if first_confirmed_start is not None:
        resolved = 0
        for g in global_bestand:
            if g["speaker"] not in confirmed_ids and g["end"] <= first_confirmed_start:
                print(f"  LEADING_RESOLVE: spk{g['speaker']} [{g['start']:.2f}-{g['end']:.2f}] -> spk{first_confirmed_id} "
                      f"(vor erstem bestätigten Start {first_confirmed_start:.2f})", file=sys.stderr)
                g["speaker"] = first_confirmed_id
                resolved += 1
        if resolved:
            global_bestand.sort(key=lambda s: s["start"])
            merged_b = []
            for g in global_bestand:
                if merged_b and merged_b[-1]["speaker"] == g["speaker"] and g["start"] <= merged_b[-1]["end"] + EPS:
                    merged_b[-1]["end"] = max(merged_b[-1]["end"], g["end"])
                else:
                    merged_b.append(g)
            global_bestand = merged_b
            print(f"  LEADING_RESOLVE: {resolved} Segment(e) umgemappt -> {len(global_bestand)} Segmente", file=sys.stderr)
            for g in global_bestand:
                print(f"    [{g['start']:6.2f}-{g['end']:6.2f}] spk{g['speaker']}", file=sys.stderr)

# ── 0.6.14: Overlay-Korrektur (Backchannel) ──────────────────────────────────
# Jedes ASR-Segment (>= 2s) wird zeitlich dem Diarization-Segment mit der
# größten Überlappung zugeordnet und dann AKUSTISCH gegen die bestätigten
# Voiceprints verifiziert (confirmed-only, 0.62). Klarer Match auf einen
# ANDEREN Sprecher -> Zuordnung korrigieren. (Geräte-Befund: "also Wähler..."
# akustisch Sprecher 3, aber zeitlich Sprecher 4 zugeordnet – Einwurf-Grenze)
overlay_corrected = 0
if confirmed_ids:
    for a in asr:
        s0, s1 = a["start_sec"], a["end_sec"]
        if s1 - s0 < 2.0:
            continue
        best_ov, best_spk = 0.0, None
        for d in global_bestand:
            ov = min(s1, d["end"]) - max(s0, d["start"])
            if ov > best_ov:
                best_ov, best_spk = ov, d["speaker"]
        if best_spk is None or best_spk not in confirmed_ids:
            continue
        i0, i1 = int(s0 * SAMPLE_RATE), int(s1 * SAMPLE_RATE)
        if i1 > len(raw) or i0 >= i1:
            continue
        samples = raw[i0:i1]
        matched = voice_bank.identify(samples, confirmed_only=True)
        if 225.0 <= s0 <= 250.0:
            # Diagnose: Similarities der ASR-Segmente im Backchannel-Bereich
            emb_d = voice_bank._embed(samples)
            sims_d = {g: round(voice_bank.cosine(emb_d, vp), 3) for g, vp in voice_bank.voiceprints.items()}
            print(f"  OVERLAY_DIAG: ASR [{s0:.1f}-{s1:.1f}] '{a['text'][:40].strip()}' zeitlich spk{best_spk} sims={sims_d} -> match={matched}", file=sys.stderr)
        if matched is not None and matched != best_spk:
            overlay_corrected += 1
            print(f"  OVERLAY_CORRECT: ASR [{s0:.1f}-{s1:.1f}] '{a['text'][:45].strip()}...' zeitlich spk{best_spk} -> akustisch spk{matched}", file=sys.stderr)
print(f"# OVERLAY: {len(asr)} ASR-Segmente, {overlay_corrected} akustisch korrigiert", file=sys.stderr)

# ── TimelineComposer ──────────────────────────────────────────────────────────
def compact_raw(segs):
    if len(segs) < 2:
        return segs
    out = []
    merged = 0
    for seg in segs:
        dur = seg["end_sec"] - seg["start_sec"]
        tiny = dur < 0.6 or len(seg["text"].strip()) <= 2
        if tiny and out:
            last = out[-1]
            pause = seg["start_sec"] - last["end_sec"]
            if 0 <= pause <= 1.2:
                out[-1] = {**last, "text": (last["text"].strip() + " " + seg["text"].strip()).strip(),
                           "end_sec": max(last["end_sec"], seg["end_sec"])}
                merged += 1
                continue
        out.append(seg)
    if merged:
        print(f"  compact: merged {merged} tiny segments ({len(segs)} -> {len(out)})", file=sys.stderr)
    return out


def split_long(segs, diar):
    out = []
    for seg in segs:
        dur = seg["end_sec"] - seg["start_sec"]
        if dur < 8.0:
            out.append(seg)
            continue
        a0, a1 = seg["start_sec"], seg["end_sec"]
        overlapping = [d for d in diar if d["start"] < a1 and d["end"] > a0]
        if len(overlapping) < 2 or len({d["speaker"] for d in overlapping}) < 2:
            out.append(seg)
            continue
        splits = [a0]
        for i in range(1, len(overlapping)):
            if overlapping[i]["speaker"] != overlapping[i - 1]["speaker"]:
                pt = min(max(overlapping[i]["start"], a0), a1)
                if pt > splits[-1] and pt < a1:
                    splits.append(pt)
        splits.append(a1)
        if len(splits) <= 2:
            out.append(seg)
            continue
        words = seg["text"].strip().split()
        n = len(words)
        for i in range(len(splits) - 1):
            ss, se = splits[i], splits[i + 1]
            if se - ss <= 0:
                continue
            smap = {}
            for d in overlapping:
                o = max(0.0, min(se, d["end"]) - max(ss, d["start"]))
                if o > 0:
                    smap[d["speaker"]] = smap.get(d["speaker"], 0.0) + o
            best = max(smap.items(), key=lambda kv: kv[1]) if smap else (None, 0.0)
            best_ms = best[1] * 1000.0
            r0 = (ss - a0) / (a1 - a0)
            r1 = (se - a0) / (a1 - a0)
            w0 = int(n * r0)
            w1 = int(n * r1)
            sub_text = " ".join(words[w0:w1]) if w0 < w1 else "..."
            sub_spk = f"speaker_{best[0]}" if best[0] is not None and best_ms >= 80 else seg.get("speaker")
            out.append({"text": sub_text if sub_text else seg["text"], "start_sec": ss, "end_sec": se,
                        "speaker": None, "speaker_id": sub_spk})
        print(f"  split_long: {seg['start_sec']:.1f}-{seg['end_sec']:.1f}s -> {len(splits) - 1} subs", file=sys.stderr)
    return out


def assign(segs, diar):
    result = []
    for seg in segs:
        a0, a1 = seg["start_sec"], max(seg["end_sec"], seg["start_sec"] + 0.3)
        ov = {}
        for d in diar:
            o = max(0.0, min(a1, d["end"]) - max(a0, d["start"]))
            if o > 0:
                ov[d["speaker"]] = ov.get(d["speaker"], 0.0) + o
        best = max(ov.items(), key=lambda kv: kv[1]) if ov else (None, 0.0)
        best_ms = best[1] * 1000.0
        dur_ms = (a1 - a0) * 1000.0
        ratio = best_ms / dur_ms if dur_ms > 0 else 0
        has_conf = best[0] is not None and best_ms >= 80 and (best_ms >= 300 or ratio >= 0.35)
        if not has_conf:
            result.append({**seg, "speaker": None})
        else:
            result.append({**seg, "speaker": best[0]})
    return result


def merge_display(segs):
    out = []
    for seg in sorted(segs, key=lambda s: s["start_sec"]):
        if not out:
            out.append(seg)
            continue
        last = out[-1]
        can = (last.get("speaker") is not None and seg.get("speaker") == last.get("speaker")
               and seg["start_sec"] >= last["end_sec"] and (seg["start_sec"] - last["end_sec"]) <= 1.2)
        if can:
            out[-1] = {**last, "text": (last["text"].strip() + " " + seg["text"].strip()).strip(),
                       "end_sec": max(last["end_sec"], seg["end_sec"])}
        else:
            out.append(seg)
    return out


def hms(s):
    s = int(round(s))
    return f"{s // 3600:02d}:{(s % 3600) // 60:02d}:{s % 60:02d}"


# ASR-Segmente als Kopie (speaker-frei) — wie rawFinalSegments
raw = [{"text": a["text"], "start_sec": a["start_sec"], "end_sec": a["end_sec"], "speaker": None} for a in asr]
compacted = compact_raw(raw)
split = split_long(compacted, global_bestand)
assigned = assign(split, global_bestand)

# ── Leading-Resolve (0.5.65, assign-Ebene wie Kotlin): unlabeled/unbestätigte
#    Prä-Segmente (end <= erster bestätigter Start) → erste bestätigte ID ─────
confirmed_ids = set(voice_bank.voiceprints.keys())
if confirmed_ids:
    confirmed_assigned = [s for s in assigned if s.get("speaker") is not None and s["speaker"] in confirmed_ids]
    if confirmed_assigned:
        first_start = min(s["start_sec"] for s in confirmed_assigned)
        target_id = min(confirmed_ids)
        resolved = 0
        for s in assigned:
            spk = s.get("speaker")
            if (spk is None or spk not in confirmed_ids) and s["end_sec"] <= first_start:
                s["speaker"] = target_id
                resolved += 1
        if resolved:
            print(f"  LEADING_RESOLVE(assign): {resolved} Segment(e) auf spk{target_id} gemappt (vor {first_start:.2f}s)", file=sys.stderr)

# renumberLiveSpeakerIds: IDs nach erstem Auftreten (zeitlich) neu nummerieren
renumber = {}
for seg in sorted(assigned, key=lambda s: s["start_sec"]):
    spk = seg.get("speaker")
    if spk is None or spk in renumber:
        continue
    renumber[spk] = len(renumber)
assigned = [{**s, "speaker": renumber[s["speaker"]] if s.get("speaker") is not None else None} for s in assigned]

display = merge_display(assigned)

print("\n=== ENDERGEBNIS (App-Pipeline-Simulation, FIXED_2, chunked) ===", file=sys.stderr)
for m in display:
    spk = f"Sprecher {m['speaker'] + 1}" if m.get("speaker") is not None else "Sprecher ?"
    print(f"{spk} {hms(m['start_sec'])}", file=sys.stderr)
    print(m["text"], file=sys.stderr)
    print(file=sys.stderr)

json.dump({"global_segments": global_bestand, "display": display}, open(os.path.join(BASE, "pipeline_out.json"), "w"),
          ensure_ascii=False, indent=1)
