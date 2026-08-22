#!/usr/bin/env python3
"""VoiceBank (embedding-basiert, 1:1-Semantik zu SessionVoiceBank.kt)
plus A/B-Runner fuer den Vorbank-Test."""
import numpy as np
from vb_common import cosine, VB_MATCH_THRESHOLD, VB_PENDING_CONFIRM, VB_MIN_ENROLL_SEC, QUICK_CONFIRM_SEC


class VoiceBank:
    def __init__(self):
        self.confirmed = {}   # gid -> emb (gewichteter Mittelwert)
        self.pending = {}     # gid -> emb
        self.counts = {}      # gid -> n fuer rolling average

    def preload(self, embs):
        """Vorbank: Profile aus frueherer Session als confirmed einspeisen."""
        for gid, emb in embs.items():
            self.confirmed[gid] = emb
            self.counts[gid] = 1

    def identify(self, emb, confirmed_only=False):
        """Gibt (gid, sim, is_pending) zurueck oder None. 1:1 zu identify()."""
        if emb is None:
            return None
        if not self.confirmed and not self.pending:
            return None
        best_id, best_sim, best_pending = None, 0.0, False
        for gid, vp in self.confirmed.items():
            sim = cosine(emb, vp)
            if sim > best_sim:
                best_sim, best_id, best_pending = sim, gid, False
        if not confirmed_only:
            for gid, p in self.pending.items():
                sim = cosine(emb, p)
                if sim > best_sim:
                    best_sim, best_id, best_pending = sim, gid, True
        if best_id is None:
            return None
        thr = VB_PENDING_CONFIRM if best_pending else VB_MATCH_THRESHOLD
        if best_sim <= thr:
            return None
        if best_pending and best_id in self.pending:
            # Drift-Vorpruefung (0.6.20): 2. Kontakt matcht eigenen pending (sim~1.0);
            # wenn er gleichzeitig eine ANDERE Stimme >= 0.35 matcht -> zusammenfuehren.
            drift = self._drift_match(emb, best_id)
            if drift is not None:
                self.pending.pop(best_id, None)
                return (drift, best_sim, False)
            self._confirm(best_id, emb)
        return (best_id, best_sim, best_pending)

    def enroll(self, gid, emb, dur_sec):
        """1:1 zu enroll(): pending/confirm/quick-confirm; True wenn confirmed."""
        if dur_sec < VB_MIN_ENROLL_SEC or emb is None:
            return False
        if gid in self.confirmed:
            c = self.counts.get(gid, 1)
            self.confirmed[gid] = (self.confirmed[gid] * c + emb) / (c + 1)
            self.counts[gid] = c + 1
            return True
        if gid in self.pending:
            sim = cosine(self.pending[gid], emb)
            if sim >= VB_PENDING_CONFIRM:
                drift = self._drift_match(emb, gid)
                if drift is not None:
                    self.pending.pop(gid, None)
                    return False
                self._confirm(gid, emb)
                return True
            self.pending[gid] = emb
            return False
        self.pending[gid] = emb
        if QUICK_CONFIRM_SEC > 0 and dur_sec >= QUICK_CONFIRM_SEC:
            drift = self._drift_match(emb, gid)
            if drift is None:
                self._confirm(gid, emb)
        return gid in self.confirmed

    def _confirm(self, gid, emb):
        self.confirmed[gid] = emb
        self.counts[gid] = 1
        self.pending.pop(gid, None)

    def _drift_match(self, emb, exclude_gid):
        for gid, vp in self.confirmed.items():
            if gid != exclude_gid and cosine(emb, vp) >= VB_PENDING_CONFIRM:
                return gid
        for gid, p in self.pending.items():
            if gid != exclude_gid and cosine(emb, p) >= VB_PENDING_CONFIRM:
                return gid
        return None

    def n_confirmed(self):
        return len(self.confirmed)


def run_ab(segments, vorbank):
    """Ein Lauf: Segmente (abs Zeiten + emb + dur) durch die Bank.

    segments: Liste von dicts {start, end, emb, dur}
    vorbank: dict gid -> emb (leer fuer Baseline)
    Rueckgabe: dict mit Statistiken + Zuweisung je Segment.
    """
    bank = VoiceBank()
    if vorbank:
        bank.preload(vorbank)
    next_gid = max(vorbank.keys(), default=-1) + 1 if vorbank else 0
    assigned = []
    resolve_to_vorbank = 0
    new_ids = 0
    for seg in segments:
        emb = seg["emb"]
        if emb is None or seg["dur"] < 2.0:
            assigned.append({"start": seg["start"], "end": seg["end"], "gid": None})
            continue
        hit = bank.identify(emb)
        if hit is not None:
            gid = hit[0]
            # rolling update bei confirmed-Match (wie App: enroll-path activiert
            # den confirmed-Zweig nur bei neuen Kontakten; hier konservativ kein Update)
            assigned.append({"start": seg["start"], "end": seg["end"], "gid": gid})
            if vorbank and gid in vorbank:
                resolve_to_vorbank += 1
            continue
        # kein Match -> neue ID + enroll
        gid = next_gid
        next_gid += 1
        confirmed = bank.enroll(gid, emb, seg["dur"])
        new_ids += 1
        assigned.append({"start": seg["start"], "end": seg["end"], "gid": gid,
                         "confirmed": confirmed})
    stats = {
        "n_segments": len(assigned),
        "n_unassigned": sum(1 for a in assigned if a["gid"] is None),
        "resolve_to_vorbank": resolve_to_vorbank,
        "new_ids_created": new_ids,
        "n_confirmed_bank": bank.n_confirmed(),
    }
    return stats, assigned