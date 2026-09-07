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

    /** Phase 9a (0.9.1): Alle Segmente mit [label] in [transcriptId] umbenennen. */
    suspend fun assignSpeakerName(transcriptId: String, label: String, name: String?): Int =
        withContext(Dispatchers.IO) { dao.assignSpeakerName(transcriptId, label, name) }

    /**
     * 0.10.9: Live-Zuweisung nach Stop in die History übernehmen – alle
     * Segmente mit gleicher speakerId (Orig-GID) bekommen den Profilnamen.
     */
    suspend fun assignSpeakerNameById(transcriptId: String, speakerId: String, name: String?): Int =
        withContext(Dispatchers.IO) { dao.assignSpeakerNameById(transcriptId, speakerId, name) }

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

    // ─── 0.12.0: Trim/Split ──────────────────────────────────────────

    /**
     * Trim: Alle Segmente nach [markerMs] löschen, Transkript-Dauer anpassen.
     * Gibt die Anzahl gelöschter Segmente zurück.
     */
    suspend fun trimAfter(transcriptId: String, markerMs: Long): Int = withContext(Dispatchers.IO) {
        val deleted = dao.deleteSegmentsAfter(transcriptId, markerMs)
        if (deleted > 0) {
            dao.updateTranscriptMeta(transcriptId, markerMs)
        }
        deleted
    }

    /**
     * Split: Segmente nach [markerMs] in ein neues Transkript verschieben.
     * Gibt die neue transcriptId zurück (oder null bei Fehler).
     */
    suspend fun splitAt(transcriptId: String, markerMs: Long): String? = withContext(Dispatchers.IO) {
        val old = dao.getTranscript(transcriptId) ?: return@withContext null
        val tail = dao.getSegmentsAfter(transcriptId, markerMs)
        if (tail.isEmpty()) return@withContext null

        // Neues Transkript mit Tail-Metadaten
        val newId = "split_${System.currentTimeMillis()}_${transcriptId.take(8)}"
        val tailDuration = (tail.last().endTimeMs - tail.first().startTimeMs).coerceAtLeast(0)
        val tailSpeakers = tail.mapNotNull { it.speakerId }.distinct().size
        val newTranscript = TranscriptEntity(
            transcriptId = newId,
            title = "${old.title} (ab ${formatMarker(markerMs)})",
            language = old.language,
            durationMs = tailDuration,
            speakerCount = tailSpeakers,
            status = "finalized",
        )
        dao.insertTranscript(newTranscript)

        // Segmente zum neuen Transkript verschieben
        val moved = tail.map { it.copy(transcriptId = newId) }
        dao.insertSegments(moved)
        dao.deleteSegmentsAfter(transcriptId, markerMs)
        dao.updateTranscriptMeta(transcriptId, markerMs)

        newId
    }

    private fun formatMarker(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "${h}:${String.format("%02d", m)}:${String.format("%02d", s)}"
        else "${m}:${String.format("%02d", s)}"
    }
}
