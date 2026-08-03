package com.sherpa.transcript.data.local

/**
 * Ein Segment innerhalb eines Transkripts.
 */
data class SegmentEntity(
    val segmentId: String,
    val transcriptId: String,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L,
    val text: String,
    val speakerId: String? = null,
    val speakerLabel: String? = null,
    val speakerConfidence: Float = 0f,
    val asrConfidence: Float = 0f,
    val isFinal: Boolean = true,
    val sequenceIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
