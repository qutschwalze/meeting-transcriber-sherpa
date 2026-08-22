package com.sherpa.transcript.engine

import android.util.Log
import com.sherpa.transcript.data.local.SpeakerProfile
import java.util.UUID

/**
 * GlobalVoiceBank (Phase 7) – persistente, geräteweite Speaker-Profile.
 *
 * Semantik: BEWUSST strenger als die [SessionVoiceBank]:
 * - NUR bestätigte Profile (confirmed) – keine pending-/2-Kontakt-Logik.
 *   Einmal bestätigt (durch die Session-Bank-Maschinerie: 2 Kontakte oder
 *   Quick-Confirm ≥ 4 s) gilt eine Stimme als Person.
 * - Match-Schwelle FEST [MATCH_THRESHOLD] = 0.62. Die lockere 0.35-Pending-
 *   Schwelle der Session-Bank erzeugt auf unbekannte Stimmen (sim ~0.5)
 *   Falsch-Matches – im Vorbank-A/B-Test (2026-08-22) gemessen: 0.35-Pfad
 *   legte fremde Stimmen auf bekannte Personen. Die globale Bank matcht
 *   deshalb NUR mit der strikten Schwelle.
 * - Profil-ID = UUID, stabil über Sessions. Nummern ("Sprecher 0") bleiben
 *   session-lokal; die Profil-ID ist der dauerhafte Anker. Namen kommen
 *   mit 0.7.1 (UI).
 *
 * Host-Belege (scripts/host-test/vb_ab_test.py, 2026-08-22):
 * gleiche Person über Sessions 0.62–0.81, verschiedene ≤ 0.54 → 0.62 trennt
 * zuverlässig; Auto-Enroll-only-Lauf auf 5-Min-Meeting: korrekte Zuordnung
 * 24,8 % → 75,2 %.
 */
class GlobalVoiceBank(
    /** Optionaler Embedding-Computer für den Samples-Pfad (Worker-Integration). */
    private val computer: SpeakerEmbeddingComputer? = null,
    /** Mindest-Redezeit für den Samples-Match (identisch zur SessionVoiceBank). */
    private val minIdentifySec: Float = 2f,
) {

    companion object {
        private const val TAG = "GlobalVoiceBank"

        /** Feste Match-Schwelle (confirmed-only, quer über alle Sessions). */
        const val MATCH_THRESHOLD = 0.62f
    }

    /** Profil-ID → gewichteter Mittelwert-Vektor (Reihenfolge = Anzeige-Reihenfolge). */
    private val profiles = linkedMapOf<String, FloatArray>()

    /** Profil-ID → Anzahl Enrollment-Beiträge (für den gewichteten Mittelwert). */
    private val counts = mutableMapOf<String, Int>()

    val size: Int get() = profiles.size

    /**
     * Bester Cosine-Match gegen alle Profile – Diagnose-Funktion.
     * @return (Profil-ID, Similarity) oder null bei leerer Bank.
     */
    fun bestMatch(embedding: FloatArray): Pair<String, Float>? {
        if (embedding.isEmpty() || profiles.isEmpty()) return null
        return profiles.entries
            .map { it.key to SessionVoiceBank.cosineSimilarity(embedding, it.value) }
            .maxByOrNull { it.second }
    }

    /**
     * Match gegen die Profile – NUR über [MATCH_THRESHOLD]. Keine pending.
     * @return Profil-ID bei Match, sonst null (unbekannte Stimme → neues Profil).
     */
    fun identify(embedding: FloatArray): String? {
        val best = bestMatch(embedding) ?: return null
        if (best.second > MATCH_THRESHOLD) {
            Log.d(TAG, String.format("identify: MATCH → %s (sim=%.3f)", best.first.takeLast(8), best.second))
            return best.first
        }
        Log.v(TAG, String.format("identify: KEIN Match – beste=%.3f gegen %s (thr=%.3f)",
            best.second, best.first.takeLast(8), MATCH_THRESHOLD))
        return null
    }

    /**
     * Samples-basierter Match (Worker-Pfad): embeddet intern über den
     * [computer] und matcht mit derselben strikten 0.62-Schwelle.
     * Sub-[minIdentifySec]-Segmente werden nie aufgelöst (instabiles
     * Embedding, identische Regel wie die SessionVoiceBank).
     *
     * @return Profil-ID bei Match, sonst null (auch wenn kein Computer gesetzt).
     */
    fun identifySamples(samples: FloatArray): String? {
        if (computer == null) return null
        if (samples.size < (minIdentifySec * 16000).toInt()) return null
        val embedding = computer.computeEmbedding(samples) ?: return null
        return identify(embedding)
    }

    /**
     * Neues Profil anlegen oder bestehendes per rolling average aktualisieren
     * (gewichtet nach bisheriger Kontaktzahl – identisch zur SessionVoiceBank).
     * Aufruf NUR mit BESTÄTIGTEN Kontakten (Auto-Enroll-Pfad)!
     */
    fun enroll(profileId: String, embedding: FloatArray) {
        val existing = profiles[profileId]
        if (existing != null) {
            val n = counts.getOrDefault(profileId, 1)
            profiles[profileId] = FloatArray(existing.size) { i ->
                (existing[i] * n + embedding[i]) / (n + 1)
            }
            counts[profileId] = n + 1
        } else {
            profiles[profileId] = embedding.copyOf()
            counts[profileId] = 1
        }
    }

    /** Profil direkt setzen (Initialisierung aus Store / Tests). */
    fun putProfile(profileId: String, embedding: FloatArray, sampleCount: Int = 1) {
        profiles[profileId] = embedding.copyOf()
        counts[profileId] = sampleCount
    }

    fun profileCount(profileId: String): Int = counts.getOrDefault(profileId, 0)

    fun contains(profileId: String): Boolean = profiles.containsKey(profileId)

    /** Bank aus persistierten Profilen ersetzen (App-Start). */
    fun load(profiles: List<SpeakerProfile>) {
        this.profiles.clear()
        counts.clear()
        profiles.forEach { p -> putProfile(p.id, p.embedding, p.sampleCount) }
    }

    /** Aktuellen Zustand als persistierbare Profile (für den Store). */
    fun snapshot(): List<SpeakerProfile> = profiles.entries.map { (id, emb) ->
        SpeakerProfile(
            id = id,
            embedding = emb.copyOf(),
            sampleCount = counts.getOrDefault(id, 1),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Übernimmt BESTÄTIGTE Kontakte einer Session-Bank in die globale Bank
     * (Auto-Enroll beim Session-Ende – der Mechanismus, den die Host-Tests
     * als wirksam belegt haben). Bekannte Stimmen werden gemerged (rolling
     * average), unbekannte als neues Profil angelegt.
     *
     * @param confirmed sessionGid → bestätigter Embedding-Vektor
     *                  (Quelle: SessionVoiceBank.confirmedVoiceprints())
     */
    fun autoEnrollFrom(confirmed: Map<Int, FloatArray>): AutoEnrollResult {
        val mergedIds = linkedSetOf<String>()
        val newIds = linkedSetOf<String>()
        confirmed.forEach { (_, emb) ->
            val match = identify(emb)
            if (match != null) {
                enroll(match, emb)
                mergedIds += match
            } else {
                val id = UUID.randomUUID().toString()
                enroll(id, emb)
                newIds += id
            }
        }
        return AutoEnrollResult(mergedIds, newIds)
    }

    data class AutoEnrollResult(val mergedIds: Set<String>, val newIds: Set<String>)
}