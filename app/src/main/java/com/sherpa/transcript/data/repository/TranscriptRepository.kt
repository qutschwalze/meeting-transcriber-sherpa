package com.sherpa.transcript.data.repository

import com.sherpa.transcript.SherpaTranscriptApp
import com.sherpa.transcript.data.local.AppDatabase
import com.sherpa.transcript.data.local.SegmentEntity
import com.sherpa.transcript.data.local.TranscriptDao
import com.sherpa.transcript.data.local.TranscriptEntity
import com.sherpa.transcript.data.local.TranscriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 0.6.6: Repository auf Room (SQLite) umgestellt.
 *
 * Der bisherige JSON-Datei-Store (TranscriptStore) wurde mit wachsender
 * Transkriptzahl langsam: getAlleTranscripts parste ALLE JSON-Dateien inkl.
 * Segmente nur für die Metadaten-Liste. Room liefert indizierte Queries.
 *
 * Migration: Beim ersten Zugriff werden vorhandene JSON-Transkripte einmalig
 * nach SQLite importiert (nur wenn die DB leer ist); die JSON-Dateien bleiben
 * als Backup erhalten. Alle I/O-Operationen sind suspend auf IO-Dispatcher.
 */
class TranscriptRepository {

    private val dao: TranscriptDao by lazy {
        AppDatabase.get(SherpaTranscriptApp.instance).transcriptDao()
    }

    private val jsonStore: TranscriptStore by lazy {
        TranscriptStore(File(SherpaTranscriptApp.instance.filesDir, "transcripts"))
    }

    /** Einmalige JSON→SQLite-Migration (einmal pro Prozess geprüft). */
    private var migrationChecked = false

    private suspend fun migrateJsonIfNeeded() {
        if (migrationChecked) return
        migrationChecked = true
        val jsonTranscripts = jsonStore.getAllTranscripts()
        if (jsonTranscripts.isEmpty()) return
        for (t in jsonTranscripts) {
            val segments = jsonStore.getSegments(t.transcriptId)
            dao.saveTranscriptWithSegments(t, segments)
        }
    }

    suspend fun getAllTranscripts(): List<TranscriptEntity> = withContext(Dispatchers.IO) {
        migrateJsonIfNeeded()
        dao.getAllTranscripts()
    }

    suspend fun getTranscript(id: String): TranscriptEntity? = withContext(Dispatchers.IO) {
        migrateJsonIfNeeded()
        dao.getTranscript(id)
    }

    suspend fun getSegments(transcriptId: String): List<SegmentEntity> = withContext(Dispatchers.IO) {
        migrateJsonIfNeeded()
        dao.getSegments(transcriptId)
    }

    suspend fun searchTranscripts(query: String): List<TranscriptEntity> = withContext(Dispatchers.IO) {
        migrateJsonIfNeeded()
        // Phase 8 (0.7.5): Titel + Segmenttext + Sprecher-Namen
        dao.searchTranscriptsFull(query)
    }

    suspend fun searchSegments(transcriptId: String, query: String): List<SegmentEntity> = withContext(Dispatchers.IO) {
        migrateJsonIfNeeded()
        dao.searchSegments(transcriptId, query)
    }

    suspend fun saveTranscriptWithSegments(
        transcript: TranscriptEntity,
        segments: List<SegmentEntity>,
    ) = withContext(Dispatchers.IO) {
        dao.saveTranscriptWithSegments(transcript, segments)
    }

    suspend fun updateTitle(id: String, title: String) = withContext(Dispatchers.IO) {
        dao.updateTitle(id, title)
    }

    suspend fun deleteTranscript(id: String) = withContext(Dispatchers.IO) {
        dao.deleteSegments(id)
        dao.deleteTranscript(id)
    }
}
