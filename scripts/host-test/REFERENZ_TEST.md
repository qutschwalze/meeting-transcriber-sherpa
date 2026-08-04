# SherpaApp Host-Test: Di._07.52.m4a (128,9 s)

Pipeline exakt nachgestellt: sherpa-onnx **1.13.4** (App-Version), Kroko-Zipformer-ASR
(App-Config: greedy_search, Endpoint rule1 0,4 s / rule2 0,25 s, 16 kHz, dim 80),
Diarization ReVerb v1 + NeMo Titanet Small (App-Assets), Chunk-Pipeline 15 s+5 s,
FIXED_2 (numClusters=2, threshold=0.3, minOn/Off=0.1), RollingReconciler, Voice-Bank
(0.62/0.35, 2 s-Gates), TimelineComposer (compact → split>8s → assign → merge 1,2 s).

## ASR-Qualität vs. Anhang

- WER ≈ **7,8 %** (23 Abweichungen / 294 Referenz-Wörter), SequenceMatcher-Ratio 0,907
- Meiste Differenzen = Schreibvarianten („n"→„ein", „drüber"→„darüber", „hab"→„habe")
- Echte Hörfehler (~8): aufmachen/aufbauen, nicht/mich, Coachreiber/Codesschreiber,
  „schafft dich ab"/„schaff ich", „gesehenerin"/„gesehen", „ausgekürzt"/„aus"
- Kroko ist an 2 Stellen RICHTIGER als die Referenz: „sechs, sieben Monaten"
  (Referenz: „67 Monaten"), „bei Meta" (Referenz: „bei bei Meta")

## Diarization-Qualität

- Sprecher A: 10,03–61,04 s | Sprecher B: 61,04–119,6 s
- **Umschaltpunkt 61,04 s** (Referenz 1:02) → FIRST_2SPK-Erwartung erfüllt
- Titanet-Separation exzellent: A-intra 0,910 / B-intra 0,915 / A-vs-B 0,069
- Voice-Bank bestätigt exakt 2 Sprecher (2-Kontakt-Härtung)

## Verbleibende Differenzen zur Referenz (befundet, nicht pipeline-lösbar)

1. **0–10 s-Fragment** („Nicht mehr merken, aber") – akustisch eigenständig
   (Titanet-Sim zu A = 0,05, zu B = 0,24; RMS 0,0042 vs. 0,0154; andere Spektralcharakteristik).
   **Gelöst per Heuristik (0.5.63)**: Führende unbestätigte Segmente werden im
   Final-Lauf dem ersten bestätigten Sprecher zugeordnet → exakt 2 Sprecher wie Referenz.
2. **Referenz-Rückwechsel bei 1:51** („Du machst die Maschine schlau…" als Sprecher 1)
   akustisch nicht bestätigbar: Segment 111–113,5 s liegt näher an B (0,44) als an A (0,13).
   Die App-Zuordnung (B durchgehend) ist hier sogar plausibler als die Referenz.
3. ASR-Hörfehler sind Modellgrenzen des Streaming-Zipformers (nur per anderem Modell
   verbesserbar).

## Endergebnis (Pipeline-Simulation, FIXED_2, chunked, Voice-Bank + Leading-Resolve)

```
Sprecher 1 00:00:08
Nicht mehr merken, aber der Erfahrungsweg ist ausgekürzt . Und das ist noch gar nicht
abzusehen, was das mit der Gesellschaft macht. Also ein paar Folgen fallen mir schon ein,
zum Beispiel der enorme Verbrauch von Moral hängt damit zusammen, dass die Leute in ganz
großen Stil Erkenntnisse haben, die nicht auf Erfahrungen aufmachen. Das heißt, es ist so
leicht, eine radikale moralische Meinung über etwas zu haben, wenn man selber noch nie in
der Situation war. Nun, man glaubt, alles beurteilen zu können, weil man die Erkenntnisse
hat, aber man kennt die Erfahrung, man hat dann sozusagen dieser spürende Weg, in dem man
sich etwas erschließt, mit all den Irrungen und Wirrungen, der wird völlig rausgekürzt. Und
da wundert man sich darüber, dass wir in so einer hypererregten und hypermoralisierenden
Welt leben, wenn die Leute ihre festen Überzeugungen nicht mehr aus Erfahrungen gewinnen,
sondern

Sprecher 2 00:01:01
aus Informationen, die ihnen per Mausklick zur Verfügung stehen. Ich habe neulich mit meinem
Sohn nicht lange darüber ausgetauscht, du weißt , der arbeitet ja in der Branche . Und der
sagt das ähnlich , der sagt im Silicon Valley herrscht zum Teil nackte Panik , weil die
Coachreiber, die jungen Programmierer, sagen, wir arbeiten gerade an unserer eigenen
Abschaffung. Da gibt es Leute, die angehalten sind und die sagen, das ist eine Entwicklung,
die da jetzt kommt , die dauert nicht ein Jahr. Da reden wir von sechs, sieben Monaten. Die
schieben gerade Überstunden wie die Wahnsinnigen und wissen ganz am Ende dieses Prozesses, ja,
die optimieren Ki, um selber andere Intelligenz zu programmieren und ganz am Ende ist die
eigene Intelligenz nicht mehr gefragt, danach bin ich zu Hause und sitze arbeitslos zu Hause
und mache die Maschine schlau und die schafft dich ab. So, und das gleiche hat man neulich bei
Meta gesehenerin .
```
