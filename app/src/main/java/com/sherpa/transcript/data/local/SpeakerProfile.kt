package com.sherpa.transcript.data.local

/**
 * Persistiertes Sprecher-Profil (Phase 7, GlobalVoiceBank).
 *
 * @param id UUID des Profils – stabil über alle Sessions (unabhängig von den
 *           numerischen Session-ID-Nummern, die jede Aufnahme neu vergibt).
 * @param embedding 512-dim ERes2Net-Vektor (gewichteter Mittelwert aller
 *           bestätigten Kontakte – rolling average, wie die SessionVoiceBank).
 * @param sampleCount Anzahl Enrollment-Beiträge (für den gewichteten Mittelwert).
 * @param updatedAt letzter Enroll-Zeitpunkt (epoch ms, Diagnose).
 */
data class SpeakerProfile(
    val id: String,
    val embedding: FloatArray,
    val sampleCount: Int,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is SpeakerProfile && other.id == id && other.sampleCount == sampleCount &&
            other.updatedAt == updatedAt && other.embedding.contentEquals(embedding)

    override fun hashCode(): Int = id.hashCode()
}