package com.sherpa.transcript.data.local

/**
 * Ein gespeichertes Transkript (Metadaten).
 */
data class TranscriptEntity(
    val transcriptId: String,
    val title: String,
    val language: String = "de",
    val durationMs: Long = 0L,
    val speakerCount: Int = 0,
    val status: String = "finalized",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
