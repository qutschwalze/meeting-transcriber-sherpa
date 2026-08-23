#!/usr/bin/env python3
"""Phase-9-Host-Vortest mit Gemma 4 E2B (gleiche 4 Fälle wie bei Qwen).
Gemma-Chat-Format: <start_of_turn>user / <start_of_turn>model."""
import time
from llama_cpp import Llama

MODEL = "/root/models/gemma-4-E2B_q4_0-it.gguf"

CASES = [
    (
        "Mittelnwahlen ... Crimaries ... Maralago da steht Florida",
        "Ja, gestern spätabend wichtiger Termin, was die Mittelnwahlen angeht, wir hatten Crimaries, also Vorwahlen in diversen Staaten, darunter wichtige, wie zum Beispiel Donald Trumps Heimatstaaten, wenn man bedenkt, dass Maralago da steht Florida.",
        "Erwartet: Midtermwahlen/Mittelwahlen, Primaries, Mar-a-Lago",
    ),
    (
        "Homophon: 'schon gehabt' statt 'gedacht'",
        "Und als ich das gesehen habe, habe ich mir schon gehabt, oh, oh, oh. Den Godel ist das normalerweise wichtig, der wird wahrscheinlich morgen schlecht drauf sein und dann keine Lust haben Präsident zu spielen.",
        "Erwartet: 'habe ich mir schon gedacht'; Godel-Satz fraglich",
    ),
    (
        "Garble: 'wird er all' + 'hat Pistole geschossen'",
        "Eine Million Jahre wird er all, das bedeutet, die Lebensdauer ist eine Million Jahre, der kommt aus Kalifornien, das kommt alles wie aus hat Pistole geschossen, als wäre er schon sein ganzes Leben lang im Marmorvertrieb.",
        "Erwartet: 'wie aus der Pistole geschossen'; erster Teil evtl. unverständlich lassen",
    ),
    (
        "Kleinigkeit: fehlender Artikel",
        "quasi gesagt, geh mir damit nicht mehr auf die Nerven, ja, zahl die fünf Millionen Dollar an die Kirche zurück, dann ist die Sache erledigt für mich.",
        "Erwartet: fast keine Änderung (war schon ok)",
    ),
]

PROMPT_TMPL = """<start_of_turn>user
Du korrigierst deutsche Speech-to-Text Transkripte. Regeln:
1. NUR offensichtliche Erkennungsfehler korrigieren (Homophone, falsch erkannte Wörter, fehlende Artikel).
2. Keine Inhalte hinzufügen, keine Sätze umformulieren.
3. Unklare Stellen UNVERÄNDERT lassen.
4. Antworte NUR mit dem korrigierten Text, ohne Erklärung.

Korrigiere diesen Transkript-Abschnitt:

{input}<end_of_turn>
<start_of_turn>model
"""


def word_diff(a: str, b: str) -> int:
    wa, wb = a.split(), b.split()
    prev = list(range(len(wb) + 1))
    for i, ca in enumerate(wa, 1):
        cur = [i]
        for j, cb in enumerate(wb, 1):
            cur.append(min(prev[j] + 1, cur[-1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def main():
    llm = Llama(model_path=MODEL, n_ctx=2048, n_threads=4, verbose=False)
    print("=== Phase-9 Host-Vortest: Gemma-4-E2B-it (QAT q4_0) ===\n")

    total_orig_words, total_dist = 0, 0
    for i, (label, original, expect) in enumerate(CASES, 1):
        prompt = PROMPT_TMPL.format(input=original)
        t0 = time.time()
        out = llm(prompt, max_tokens=250, temperature=0.0, stop=["<end_of_turn>"])
        dt = time.time() - t0
        corrected = out["choices"][0]["text"].strip()

        dist = word_diff(original, corrected)
        ow = len(original.split())
        total_orig_words += ow
        total_dist += dist

        verdict = "OK(marginal)" if dist / max(ow, 1) <= 0.25 else "FAIL(umformuliert)"
        print(f"--- Fall {i}: {label}")
        print(f"  Original  ({ow} Wörter): {original[:180]}")
        print(f"  Korrektur ({len(corrected.split())} Wörter): {corrected[:180]}")
        print(f"  Distanz={dist} Wörter ({100*dist/max(ow,1):.0f}%), Zeit={dt:.1f}s → {verdict}")
        print(f"  Hinweis: {expect}")
        print()

    print(f"=== Gesamt: {total_dist}/{total_orig_words} Wörter geändert "
          f"({100*total_dist/max(total_orig_words,1):.1f}%) ===")


if __name__ == "__main__":
    main()