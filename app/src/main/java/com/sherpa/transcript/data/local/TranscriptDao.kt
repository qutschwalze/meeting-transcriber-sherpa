package com.sherpa.transcript.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 0.6.6: Room-DAO für Transkripte.
 * suspend + IO-Dispatcher im Repository – die UI-API bleibt unverändert.
 */
@Dao
interface TranscriptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscript(transcript: TranscriptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SegmentEntity>)

    /** Atomar: Transkript + Segmente speichern. */
    @Transaction
    suspend fun saveTranscriptWithSegments(transcript: TranscriptEntity, segments: List<SegmentEntity>) {
        insertTranscript(transcript)
        if (segments.isNotEmpty()) insertSegments(segments)
    }

    @Query("SELECT * FROM transcripts ORDER BY createdAt DESC")
    suspend fun getAllTranscripts(): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE transcriptId = :id")
    suspend fun getTranscript(id: String): TranscriptEntity?

    @Query("SELECT * FROM segments WHERE transcriptId = :tid ORDER BY startTimeMs ASC")
    suspend fun getSegments(tid: String): List<SegmentEntity>

    @Query("SELECT * FROM transcripts WHERE title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchTranscripts(query: String): List<TranscriptEntity>

    @Query("SELECT * FROM segments WHERE transcriptId = :tid AND text LIKE '%' || :query || '%' ORDER BY startTimeMs ASC")
    suspend fun searchSegments(tid: String, query: String): List<SegmentEntity>

    @Query("UPDATE transcripts SET title = :title WHERE transcriptId = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("DELETE FROM transcripts WHERE transcriptId = :id")
    suspend fun deleteTranscript(id: String)

    @Query("DELETE FROM segments WHERE transcriptId = :tid")
    suspend fun deleteSegments(tid: String)

    @Query("SELECT COUNT(*) FROM transcripts")
    suspend fun countTranscripts(): Int
}
