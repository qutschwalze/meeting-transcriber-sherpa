package com.sherpa.transcript.engine

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.sqrt

/**
 * Abstraktion der Embedding-Berechnung – unit-testbar per Fake.
 * Die echte Implementierung ([SherpaEmbeddingComputer]) nutzt Sherpa-ONNX
 * mit dem bereits vorhandenen `embedding.onnx` (NeMo Titanet Small).
 */
fun interface SpeakerEmbeddingComputer {
    /** Berechnet ein Speaker-Embedding aus 16kHz-Audio-Samples. Null bei Fehler. */
    fun computeEmbedding(samples: FloatArray): FloatArray?
}

/**
 * Echte Sherpa-ONNX-Implementierung: `SpeakerEmbeddingExtractor` + `embedding.onnx`.
 * Nutzt dieselbe Modell-Datei wie die Diarization – kein neuer Download.
 */
class SherpaEmbeddingComputer(
    assetManager: AssetManager,
    private val modelName: String = "embedding.onnx",
    private val sampleRate: Int = 16000,
) : SpeakerEmbeddingComputer {

    companion object {
        private const val TAG = "SherpaEmbeddingComputer"
    }

    private val extractor = SpeakerEmbeddingExtractor(
        assetManager,
        SpeakerEmbeddingExtractorConfig(
            model = modelName,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        ),
    )

    override fun computeEmbedding(samples: FloatArray): FloatArray? {
        if (samples.isEmpty()) return null
        return try {
            val stream = extractor.createStream()
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            val embedding = if (extractor.isReady(stream)) {
                extractor.compute(stream)
            } else {
                Log.w(TAG, "computeEmbedding: Stream nicht bereit (${samples.size} samples)")
                null
            }
            stream.release()
            embedding
        } catch (t: Throwable) {
            Log.e(TAG, "computeEmbedding failed: ${t.message}")
            null
        }
    }

    fun release() {
        try { extractor.release() } catch (_: Throwable) {}
    }
}

/**
 * SessionVoiceBank (Hebel G) – akustisches Gedächtnis gegen Engine-Drift.
 *
 * Problem (0.5.45-Diagnose): pyannote verliert nach ~60s den Bezugsrahmen und
 * erfindet alle 10–20s neue Cluster (6 Speaker in 101s bei nur 2 echten).
 * Zeitliches Matching (Reconciler) ist dagegen machtlos, wenn die Overlap-Zone
 * keine Anker hat.
 *
 * Lösung: Die Bank merkt sich pro globaler Speaker-ID ein Embedding (Voiceprint)
 * und gleicht neue Kandidaten per Cosine Similarity ab:
 * - [identify]: Match über Threshold → globale ID zurückgeben (Drift aufgelöst)
 * - [enroll]:   neuer Sprecher wird eingeschrieben
 * - Rolling Update: je mehr Audio, desto stabiler das Voiceprint (gewichteter Ø)
 *
 * Enrollment-Härtung (0.5.53): Ein neuer Sprecher wird erst nach 2 unabhängigen
 * Kontakten (2 Chunks) mit ähnlichem Klangbild bestätigt. Pyannote-Fehlcluster,
 * die nur einmal auftauchen (0.5.52: 3 Speaker in Hälfte 1), werden nie zu
 * echten Speakern – sie bleiben "pending" und sterben am Session-Ende.
 *
 * Die Bank ist bewusst AKUSTISCH und zeitunabhängig – sie ergänzt den temporalen
 * Reconciler als Fallback für die Anker-Lücken-Fälle.
 */
class SessionVoiceBank(
    private val computer: SpeakerEmbeddingComputer,
    /**
     * Cosine-Similarity-Schwelle für einen Match.
     *
     * 0.5.61-Kalibrierung (gemessen mit echter Mikrofon-Aufnahme + Titanet,
     * Referenz-Sprecherzeiten aus dem Transkript):
     *   INTRA (gleicher Sprecher): min 0.638, max 0.908, mean 0.812
     *   INTER (verschiedene):      min 0.344, max 0.612, mean 0.486
     *   Sweet Spot: 0.625 (Lücke 0.612 → 0.638, nur 0.026 breit)
     * 0.38 (0.5.49, auf dem alten Wall-Clock-Pfad kalibriert) liegt UNTER dem
     * Inter-Maximum → matcht verschiedene Sprecher fälschlich (Log-Befund
     * 0.5.60: beide lokalen Speaker → global=0 mit sim 0.672/0.773).
     * 0.62 = konservativ (über Inter-max 0.612, unter Intra-min 0.638).
     */
    private val matchThreshold: Float = 0.62f,
    /** Mindest-Redezeit für ein Enrollment (verhindert Einschreiben auf Fragmente). */
    private val minEnrollmentSec: Float = 2f,
    /**
     * Mindest-Redezeit für identify (0.5.61).
     * Log-Befund 0.5.60: 1s-Segmente (995ms/1012ms) erzeugten FALSCH-Matches
     * (sim 0.669/0.672 → global=0). Unter 2s ist das Embedding akustisch
     * instabil – solche Segmente werden NICHT aufgelöst (bleiben unlabeled,
     * besser als falsch gelabelt). Konsistent mit dem Enrollment-Gate.
     */
    private val minIdentifySec: Float = 2f,
    /**
     * Mindest-Ähnlichkeit zwischen 2 Kontakten für die Enrollment-Bestätigung.
     *
     * 0.5.62: Von 0.62 zurück auf 0.35. Log-Befund 0.5.61: Mit 0.62 bestätigte
     * die Bank GAR NICHTS (Bank=0 bestätigt durchgehend, alle pending) – die
     * App-Aufnahme (Mikrofon, Raumklang) hat deutlich niedrigere Intra-
     * Similarities als die saubere Rekorder-WAV (0.17–0.32 zwischen Kontakten).
     * Die 0.62-Kalibrierung galt für die Rekorder-WAV, nicht für die App.
     *
     * WICHTIG: Diese Schwelle ist BEWUSST getrennt von [matchThreshold]:
     * - matchThreshold 0.62: Resolve gegen BESTÄTIGTE Voiceprints (verhindert
     *   falsches B→A-Merging, Log-Beweis 0.5.61: KEIN Match bei 0.22–0.32)
     * - pendingConfirmThreshold 0.35: NUR Bestätigung, dass 2 Kontakte derselben
     *   ID ähnlich genug sind. Das bestätigte Voiceprint (Durchschnitt aus 2
     *   Kontakten) wird danach trotzdem nur mit 0.62 gematcht.
     */
    private val pendingConfirmThreshold: Float = 0.35f,
) {

    companion object {
        private const val TAG = "SessionVoiceBank"

        /** Cosine Similarity zwischen zwei Embeddings (identische Richtung = 1). */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return 0f
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            if (normA == 0.0 || normB == 0.0) return 0f
            return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
        }
    }

    /** Voiceprints: globale Speaker-ID → Embedding (gewichteter Durchschnitt). */
    private val voiceprints = mutableMapOf<Int, FloatArray>()

    /** Anzahl Enrollment-Beiträge pro Speaker (für den gewichteten Durchschnitt). */
    private val enrollCounts = mutableMapOf<Int, Int>()

    /**
     * Noch NICHT bestätigte Speaker (2-Kontakt-Härtung): globale ID → Embedding.
     * Ein Enrollment braucht 2 unabhängige Kontakte (2 Chunks) mit ähnlichem
     * Klangbild – einmalige Pyannote-Fehlcluster werden nie eingeschrieben.
     */
    private class PendingEnrollment(val embedding: FloatArray)

    private val pendingEnrollments = mutableMapOf<Int, PendingEnrollment>()

    val speakerCount: Int get() = voiceprints.size
    val pendingCount: Int get() = pendingEnrollments.size
    val enrolledSpeakerIds: Set<Int> get() = voiceprints.keys.toSet()

    /**
     * Gleicht Audio-Samples gegen die Bank ab – gegen bestätigte Voiceprints UND
     * gegen pending Enrollments (Drift-Auflösung auch vor der Bestätigung).
     * Ein Match gegen ein pending gilt als 2. Kontakt und bestätigt das Enrollment.
     * @return globale Speaker-ID bei Match über Threshold, sonst null.
     */
    fun identify(samples: FloatArray): Int? {
        if (samples.isEmpty() || (voiceprints.isEmpty() && pendingEnrollments.isEmpty())) return null
        // 0.5.61: Kurze Segmente (< minIdentifySec) nicht auflösen – ihr Embedding
        // ist akustisch instabil und erzeugt FALSCH-Matches (Log-Befund 0.5.60:
        // 1s-Segmente mit sim 0.669/0.672 → fälschlich auf global=0 gematcht).
        // 16 kHz: minIdentifySec * 16000 Samples. Besser unlabeled als falsch.
        if (samples.size < (minIdentifySec * 16000).toInt()) {
            Log.d(TAG, "identify skip: nur ${samples.size / 16}ms (< ${minIdentifySec}s) – zu kurz für stabiles Embedding")
            return null
        }
        val embedding = computer.computeEmbedding(samples) ?: return null

        var bestId: Int? = null
        // 0.5.62: Getrennte Schwellen!
        // - bestätigte Voiceprints: matchThreshold (0.62, strikt – verhindert
        //   falsches B→A-Merging, Log-Beweis 0.5.61)
        // - pending Enrollments:   pendingConfirmThreshold (0.35, locker – der
        //   2. Kontakt derselben ID soll bestätigt werden, sonst bleibt ALLES
        //   pending wie in 0.5.61: Bank=0 bestätigt, sim 0.17–0.32)
        // Der beste Treffer (egal ob Voiceprint oder pending) wird am Ende
        // gegen die ZU IHM PASSENDE Schwelle geprüft.
        var bestSim = 0f
        var bestIsPending = false
        // Diagnose: Similarity-Verteilung sichtbar machen (auch unter Threshold)
        val sims = StringBuilder()
        for ((id, vp) in voiceprints) {
            val sim = cosineSimilarity(embedding, vp)
            if (sims.isNotEmpty()) sims.append(", ")
            sims.append(String.format("%d=%.3f", id, sim))
            if (sim > bestSim) {
                bestSim = sim
                bestId = id
                bestIsPending = false
            }
        }
        // Pending Enrollments matchen ebenfalls (Drift vor Bestätigung auflösen) –
        // ABER mit der LOCKEREN Bestätigungsschwelle, sonst wird nie bestätigt
        for ((id, pending) in pendingEnrollments) {
            val sim = cosineSimilarity(embedding, pending.embedding)
            if (sims.isNotEmpty()) sims.append(", ")
            sims.append(String.format("%d~=%.3f", id, sim))
            if (sim > bestSim) {
                bestSim = sim
                bestId = id
                bestIsPending = true
            }
        }
        val effectiveThreshold = if (bestIsPending) pendingConfirmThreshold else matchThreshold
        if (bestId != null && bestSim > effectiveThreshold) {
            // Match gegen ein pending = 2. Kontakt → Enrollment bestätigen
            val pending = pendingEnrollments[bestId]
            if (pending != null && bestIsPending) {
                confirmPending(bestId, embedding)
            }
            Log.d(TAG, String.format("identify: MATCH → global=%d sim=%.3f (threshold=%.3f, [%s])",
                    bestId, bestSim, effectiveThreshold, sims))
        } else {
            // Kein Match über Threshold – nur verbose (kommt bei jedem neuen Sprecher vor)
            val maxSim = voiceprints.entries.maxByOrNull { cosineSimilarity(embedding, it.value) }
            val topSim = if (maxSim != null) cosineSimilarity(embedding, maxSim.value) else 0f
            Log.v(TAG, String.format("identify: KEIN Match – beste Similarity=%.3f gegen global=%d (threshold=%.3f, [%s])",
                    topSim, maxSim?.key ?: -1, matchThreshold, sims))
        }
        return bestId
    }

    /**
     * Schreibt einen Sprecher ein oder aktualisiert sein Voiceprint.
     * Erst ab [minEnrollmentSec] Redezeit – verhindert Enrollment auf Fragmente.
     *
     * 2-Kontakt-Härtung: Der 1. Kontakt legt nur ein pending an (kein Voiceprint).
     * Erst wenn dieselbe ID in einem späteren Chunk wieder auftaucht und das
     * Klangbild ähnlich ist ([pendingConfirmThreshold]), wird bestätigt.
     *
     * @return true wenn eingeschrieben/bestätigt, false wenn pending/skip/Fehler.
     */
    fun enroll(globalId: Int, samples: FloatArray, durationMs: Long): Boolean {
        if (durationMs < (minEnrollmentSec * 1000f).toLong()) {
            Log.d(TAG, "enroll skip: global=$globalId nur ${durationMs}ms (< ${minEnrollmentSec}s)")
            return false
        }
        if (samples.isEmpty()) return false
        val embedding = computer.computeEmbedding(samples)
        if (embedding == null) {
            Log.w(TAG, "enroll: global=$globalId – computeEmbedding lieferte null (Extractor-Fehler?)")
            return false
        }

        // Bereits bestätigt → Rolling Update (gewichteter Ø)
        val existing = voiceprints[globalId]
        if (existing != null) {
            val count = enrollCounts[globalId] ?: 1
            val updated = FloatArray(embedding.size) { i ->
                (existing[i] * count + embedding[i]) / (count + 1)
            }
            voiceprints[globalId] = updated
            enrollCounts[globalId] = count + 1
            Log.d(TAG, "enroll: global=$globalId ($durationMs ms, Beitrag #${count + 1}) – Bank hat ${voiceprints.size} Sprecher")
            return true
        }

        // 2. Kontakt derselben ID? → Ähnlichkeit prüfen und ggf. bestätigen
        val pending = pendingEnrollments[globalId]
        if (pending != null) {
            val sim = cosineSimilarity(pending.embedding, embedding)
            if (sim >= pendingConfirmThreshold) {
                confirmPending(globalId, embedding)
                Log.d(TAG, "enroll CONFIRMED: global=$globalId ($durationMs ms, sim=$sim) – Bank hat ${voiceprints.size} Sprecher")
                return true
            }
            // Anderes Klangbild unter gleicher ID → pending ersetzen (war Fehlcluster)
            pendingEnrollments[globalId] = PendingEnrollment(embedding)
            Log.d(TAG, "enroll pending-ersetzt: global=$globalId – sim=$sim < $pendingConfirmThreshold")
            return false
        }

        // 1. Kontakt: nur pending anlegen – noch KEIN Voiceprint
        pendingEnrollments[globalId] = PendingEnrollment(embedding)
        Log.d(TAG, "enroll pending: global=$globalId ($durationMs ms, 1. Kontakt) – wartet auf 2. Bestätigung (Bank=${voiceprints.size} bestätigt, ${pendingEnrollments.size} pending)")
        return false
    }

    /** Bestätigt ein pending Enrollment (2. Kontakt): Voiceprint aus Ø beider Kontakte. */
    private fun confirmPending(globalId: Int, embedding: FloatArray) {
        val pending = pendingEnrollments.remove(globalId) ?: return
        val merged = FloatArray(embedding.size) { i ->
            (pending.embedding[i] + embedding[i]) / 2f
        }
        voiceprints[globalId] = merged
        enrollCounts[globalId] = 2
        Log.d(TAG, "confirmPending: global=$globalId – Voiceprint aus 2 Kontakten, Bank hat ${voiceprints.size} Sprecher")
    }

    /** Leert die Bank (Session-Ende). */
    fun reset() {
        voiceprints.clear()
        enrollCounts.clear()
        pendingEnrollments.clear()
        Log.d(TAG, "reset: Bank geleert")
    }
}
