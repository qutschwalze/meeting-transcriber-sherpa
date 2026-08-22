#!/usr/bin/env python3
"""Inter-Sim der Podcaster-Stimmen: Session 1 (103742) Cluster-Cos-Matrix +
Abgleich mit der 10:40-Session (104024)."""
import sys
sys.path.insert(0, '/root/sherpa-app/scripts/host-test')
from vb_common import load_wav, diarize, embed, cosine

D = '/root/sherpa-app/debug-server/uploads/2026-08-22/audio/wav/'


def cluster_embs(wav, label):
    x = load_wav(wav)
    segs = diarize(x, 5, 0.3)
    out = {}
    for s_ in sorted(set(s[2] for s in segs)):
        spk_segs = sorted([s for s in segs if s[2] == s_], key=lambda s: s[1] - s[0], reverse=True)
        for (a, b, _) in spk_segs:
            if b - a >= 2.0:
                e = embed(x, a, min(b, a + 6.0))
                if e is not None:
                    out[s_] = (e, a, min(b, a + 6.0))
                break
    print(f"{label} ({wav.split('/')[-1]}): {len(out)} Cluster")
    return out, x


embs1, x1 = cluster_embs(D + 'testaufnahme_20260822_103742.wav', 'Session 1 10:37')
print("\nCos-Matrix Session 1 (gleiche Session, verschiedene Sprecher):")
names = sorted(embs1)
for i in names:
    print(f"  c{i} " + " ".join(f"{cosine(embs1[i][0], embs1[j][0]):.3f}" for j in names))

print("\nAbgleich der 10:40-Session (104024, die Profile von 9b34562e/2139da47):")
embs2, x2 = cluster_embs(D + 'testaufnahme_20260822_104024.wav', 'Session 10:40')
for i in sorted(embs2):
    e2, a, b = embs2[i]
    hits = " ".join(f"→c{j}={cosine(e2, embs1[j][0]):.3f}" for j in names)
    print(f"  104024-Cluster {i} ({a:.1f}-{b:.1f}s): {hits}")

print("\nAbgleich der Retest-Session (104941, 0.7.1):")
embs3, x3 = cluster_embs(D + 'testaufnahme_20260822_104941.wav', 'Retest 10:49')
for i in sorted(embs3):
    e3, a, b = embs3[i]
    hits = " ".join(f"→c{j}={cosine(e3, embs1[j][0]):.3f}" for j in names)
    print(f"  104941-Cluster {i} ({a:.1f}-{b:.1f}s): {hits}")