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

    /**
     * Phase 8 (0.7.5): Volltextsuche über Titel, Segmenttext UND Sprecher-Namen.
     * Ein Transkript matcht, wenn Titel ODER irgendein Segment (Text oder
     * speakerName) die Query enthält – so findet ein gesuchter Kontaktname
     * alle Aufnahmen zu diesem Namen, auch wenn er nur im Sprecher-Label steckt.
     */
    @Query(
        "SELECT DISTINCT t.* FROM transcripts t WHERE " +
            "t.title LIKE '%' || :query || '%' " +
            "OR EXISTS (SELECT 1 FROM segments s WHERE s.transcriptId = t.transcriptId " +
            "AND (s.text LIKE '%' || :query || '%' OR s.speakerName LIKE '%' || :query || '%')) " +
            "ORDER BY t.createdAt DESC"
    )
    suspend fun searchTranscriptsFull(query: String): List<TranscriptEntity>

    @Query("SELECT * FROM segments WHERE transcriptId = :tid AND text LIKE '%' || :query || '%' ORDER BY startTimeMs ASC")
    suspend fun searchSegments(tid: String, query: String): List<SegmentEntity>

    /**
     * Phase 9a (0.9.1): Sprecher-Namen nachträglich zuweisen – alle Segmente
     * eines Transkripts mit gleichem speakerLabel bekommen denselben Namen.
     * (Akustisches ENROLL ist danach nicht mehr möglich – der Audio-Puffer
     * ist weg – aber der Name steuert Anzeige + Export über speakerName.)
     */
    @Query(
        "UPDATE segments SET speakerName = :name " +
            "WHERE transcriptId = :tid AND speakerLabel = :label"
    )
    suspend fun assignSpeakerName(tid: String, label: String, name: String?): Int

    @Query("UPDATE transcripts SET title = :title WHERE transcriptId = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("DELETE FROM transcripts WHERE transcriptId = :id")
    suspend fun deleteTranscript(id: String)

    @Query("DELETE FROM segments WHERE transcriptId = :tid")
    suspend fun deleteSegments(tid: String)

    @Query("SELECT COUNT(*) FROM transcripts")
    suspend fun countTranscripts(): Int
}
