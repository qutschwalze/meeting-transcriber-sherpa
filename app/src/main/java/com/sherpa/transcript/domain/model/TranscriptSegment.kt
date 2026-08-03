package com.sherpa.transcript.domain.model

/**
 * Ein einzelnes Segment eines Live-Transkripts.
 */
data class TranscriptSegment(
    val segmentId: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L,
    val isFinal: Boolean = false,
    val isNew: Boolean = false,
    val speakerId: String? = null,
    val speakerLabel: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** Kurzform für Anzeige: "Sprecher 1" falls vorhanden, sonst "" */
    val displaySpeakerLabel: String get() = speakerLabel ?: ""
}

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Initializing : RecordingState
    data object Listening : RecordingState
    data object Processing : RecordingState
    data class Error(val message: String) : RecordingState
}
