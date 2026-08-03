package com.sherpa.transcript.data.repository

import com.sherpa.transcript.SherpaTranscriptApp
import com.sherpa.transcript.data.local.SegmentEntity
import com.sherpa.transcript.data.local.TranscriptEntity
import com.sherpa.transcript.data.local.TranscriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository für Transkripte, basierend auf JSON-Datei-Speicher.
 * Alle I/O-Operationen sind suspend und laufen auf IO-Dispatcher.
 */
class TranscriptRepository {

    private val store: TranscriptStore by lazy {
        val dir = File(SherpaTranscriptApp.instance.filesDir, "transcripts")
        TranscriptStore(dir)
    }

    suspend fun getAllTranscripts(): List<TranscriptEntity> = withContext(Dispatchers.IO) {
        store.getAllTranscripts()
    }

    suspend fun getTranscript(id: String): TranscriptEntity? = withContext(Dispatchers.IO) {
        store.getTranscript(id)
    }

    suspend fun getSegments(transcriptId: String): List<SegmentEntity> = withContext(Dispatchers.IO) {
        store.getSegments(transcriptId)
    }

    suspend fun searchTranscripts(query: String): List<TranscriptEntity> = withContext(Dispatchers.IO) {
        store.searchTranscripts(query)
    }

    suspend fun searchSegments(transcriptId: String, query: String): List<SegmentEntity> = withContext(Dispatchers.IO) {
        store.searchSegments(transcriptId, query)
    }

    suspend fun saveTranscriptWithSegments(
        transcript: TranscriptEntity,
        segments: List<SegmentEntity>,
    ) = withContext(Dispatchers.IO) {
        store.saveTranscript(transcript, segments)
    }

    suspend fun updateTitle(id: String, title: String) = withContext(Dispatchers.IO) {
        store.updateTitle(id, title)
    }

    suspend fun deleteTranscript(id: String) = withContext(Dispatchers.IO) {
        store.deleteTranscript(id)
    }
}
