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
 * - [enroll]:   neuer Sprecher wird eingeschrieben (erst ab minEnrollmentSec)
 * - Rolling Update: je mehr Audio, desto stabiler das Voiceprint (gewichteter Ø)
 *
 * Die Bank ist bewusst AKUSTISCH und zeitunabhängig – sie ergänzt den temporalen
 * Reconciler als Fallback für die Anker-Lücken-Fälle.
 */
class SessionVoiceBank(
    private val computer: SpeakerEmbeddingComputer,
    /** Cosine-Similarity-Schwelle für einen Match (0.8 = konservativ). */
    private val matchThreshold: Float = 0.8f,
    /** Mindest-Redezeit für ein Enrollment (verhindert Einschreiben auf Fragmente). */
    private val minEnrollmentSec: Float = 5f,
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

    val speakerCount: Int get() = voiceprints.size
    val enrolledSpeakerIds: Set<Int> get() = voiceprints.keys.toSet()

    /**
     * Gleicht Audio-Samples gegen die Bank ab.
     * @return globale Speaker-ID bei Match über Threshold, sonst null.
     */
    fun identify(samples: FloatArray): Int? {
        if (samples.isEmpty() || voiceprints.isEmpty()) return null
        val embedding = computer.computeEmbedding(samples) ?: return null

        var bestId: Int? = null
        var bestSim = matchThreshold // nur Matches ÜBER der Schwelle zählen
        for ((id, vp) in voiceprints) {
            val sim = cosineSimilarity(embedding, vp)
            if (sim > bestSim) {
                bestSim = sim
                bestId = id
            }
        }
        if (bestId != null) {
            Log.d(TAG, "identify: match=$bestId sim=${"%.3f".format(bestSim)} (threshold=$matchThreshold)")
        }
        return bestId
    }

    /**
     * Schreibt einen Sprecher ein oder aktualisiert sein Voiceprint.
     * Erst ab [minEnrollmentSec] Redezeit – verhindert Enrollment auf Fragmente.
     * Das Voiceprint ist ein gewichteter Durchschnitt aller Enrollment-Beiträge
     * (Rolling Fingerprint): je mehr Audio, desto stabiler.
     *
     * @return true wenn eingeschrieben/aktualisiert, false wenn übersprungen (zu kurz / kein Embedding).
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

        val count = (enrollCounts[globalId] ?: 0)
        val existing = voiceprints[globalId]
        val updated = if (existing == null) {
            embedding
        } else {
            // Gewichteter Durchschnitt: (alt * n + neu) / (n + 1)
            FloatArray(embedding.size) { i ->
                (existing[i] * count + embedding[i]) / (count + 1)
            }
        }
        voiceprints[globalId] = updated
        enrollCounts[globalId] = count + 1
        Log.d(TAG, "enroll: global=$globalId ($durationMs ms, Beitrag #${count + 1}) – Bank hat ${voiceprints.size} Sprecher")
        return true
    }

    /** Leert die Bank (Session-Ende). */
    fun reset() {
        voiceprints.clear()
        enrollCounts.clear()
        Log.d(TAG, "reset: Bank geleert")
    }
}
