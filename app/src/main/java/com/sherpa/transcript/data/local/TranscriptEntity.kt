package com.sherpa.transcript.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ein gespeichertes Transkript (Metadaten).
 * 0.6.6: Room-@Entity (Spalte = Feldname, PrimaryKey = transcriptId).
 */
@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey val transcriptId: String,
    val title: String,
    val language: String = "de",
    val durationMs: Long = 0L,
    val speakerCount: Int = 0,
    val status: String = "finalized",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
