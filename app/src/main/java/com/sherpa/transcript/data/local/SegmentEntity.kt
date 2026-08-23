package com.sherpa.transcript.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ein Segment innerhalb eines Transkripts.
 * 0.6.6: Room-@Entity mit Index auf transcriptId (schnelle getSegments-Queries).
 */
@Entity(tableName = "segments", indices = [Index("transcriptId")])
data class SegmentEntity(
    @PrimaryKey val segmentId: String,
    val transcriptId: String,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L,
    val text: String,
    val speakerId: String? = null,
    val speakerLabel: String? = null,
    /** Phase 8 (0.7.4): Profil-Name beim Save mitgespeichert (Export aus der History). */
    val speakerName: String? = null,
    val speakerConfidence: Float = 0f,
    val asrConfidence: Float = 0f,
    val isFinal: Boolean = true,
    val sequenceIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
