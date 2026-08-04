#!/usr/bin/env python3
"""Wortvergleich: Host-ASR (Kroko) vs. Referenz-Transkript (Anhang)."""
import json
import os
import re
import sys

# Referenz aus dem Anhang (Sprecher + Zeitstempel entfernt, Text normalisiert)
REF = """Nicht mehr merken. Aber der Erfahrungsweg ist aus. Das ist noch gar nicht abzusehen,
was das mit der Gesellschaft macht. Also n paar Folgen fallen mir schon ein, ZB mit der enorme
Verbrauch von Moral. Hängt damit zusammen. Dass die Leute in ganz großem Stil Erkenntnisse haben,
die nicht auf Erfahrungen aufbauen. Es ist so leicht, eine radikale moralische Meinung über etwas
zu haben, wenn man selber noch nie in der Situation war. Man glaubt, alles beurteilen zu können,
weil man die Erkenntnisse hat, aber man kennt die Erfahrung. Man hat sozusagen dieser Spürende Weg,
in den man sich etwas erschließt. Mit all den Irrungen und Wirrungen. Der wird völlig rausgekürzt.
Und da wundert man sich darüber, dass wir in so einer Hypererregten und hypermoralisierenden Welt
leben, wenn die Leute ihre festen Überzeugungen nicht mehr aus Erfahrung gewinnen, sondern aus
Informationen, die Ihnen per Mausklick zur Verfügung stehen. Ich hab neulich mit meinem Sohn mich
lange drüber ausgetauscht. Du weißt der der der arbeitet ja in der Branche. Und der sagt das
ähnlich. Der sagt, im Silicon Valley herrscht zum Teil nackte Panik. Weil die die Codesschreiber,
die jungen Programmierer sagen, wir arbeiten gerade an unserer eigenen Abschaffung. Mhm, da gibt es
Leute, die angehalten sind und die sagen, das ist ne Entwicklung, die da jetzt kommt, die Dauer.
Nicht n Jahr, da reden wir von 67 Monaten. Die schieben gerade Überstunden wie die Wahnsinnigen.
Und wissen ganz am Ende dieses Prozesses, ja die optimieren KI, um selber andere Intelligenz zu
programmieren und ganz am Ende ist die eigene Intelligenz nicht mehr gefragt. Danach bin ich zu
Hause und sitze arbeitslos zu Hause. Du machst die Maschine schlau und die schaff ich. Ab so. Und
das. Gleiche hat man neulich bei bei Meta gesehen."""

BASE = os.path.dirname(os.path.abspath(__file__))
HYP = json.load(open(sys.argv[1] if len(sys.argv) > 1 else os.path.join(BASE, "asr_out.json")))
hyp_text = " ".join(s["text"] for s in HYP["segments"])

def norm(t):
    t = t.lower()
    t = re.sub(r"[^a-zäöüß0-9\s]", " ", t)
    t = re.sub(r"\s+", " ", t).strip()
    return t.split()

ref_words = norm(REF)
hyp_words = norm(hyp_text)

# SequenceMatcher für Wort-Level-Ähnlichkeit
from difflib import SequenceMatcher
sm = SequenceMatcher(None, ref_words, hyp_words)
ops = sm.get_opcodes()
ins = sum(b - a for tag, a, b, c, d in ops if tag == "insert")
dele = sum(d - c for tag, a, b, c, d in ops if tag == "delete")
subs = sum(b - a for tag, a, b, c, d in ops if tag == "replace")
n_ref = len(ref_words)
n_hyp = len(hyp_words)
matches = sm.ratio()

print(f"Referenz-Wörter: {n_ref}, Hypothese-Wörter: {n_hyp}")
print(f"SequenceMatcher ratio: {matches:.3f}")
print(f"OpCodes: replace={subs} delete={dele} insert={ins}")
wer = (subs + dele + ins) / n_ref
print(f"WER (approx): {wer:.3f} ({subs+dele+ins} Abweichungen auf {n_ref})")

print("\n--- Geänderte/ersetzte Wörter (Kontext) ---")
for tag, a, b, c, d in ops:
    if tag == "replace":
        print(f"  REF: {' '.join(ref_words[a:b])}  ->  HYP: {' '.join(hyp_words[c:d])}")
    elif tag == "delete":
        print(f"  REF-ONLY: {' '.join(ref_words[a:b])}")
    elif tag == "insert":
        print(f"  HYP-ONLY: {' '.join(hyp_words[c:d])}")

print("\n--- HYP-Gesamttext ---")
print(hyp_text)
