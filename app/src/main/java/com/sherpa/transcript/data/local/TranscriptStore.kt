package com.sherpa.transcript.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON-basierte persistente Ablage für Transkripte.
 * Jedes Transkript = eine JSON-Datei in filesDir/transcripts/.
 * Keine externen Abhängigkeiten (nutzt nur org.json, das in Android enthalten ist).
 */
class TranscriptStore(private val transcriptsDir: File) {

    init {
        transcriptsDir.mkdirs()
    }

    // ─── Schreiben ─────────────────────────────────────────────────

    fun saveTranscript(transcript: TranscriptEntity, segments: List<SegmentEntity>) {
        val json = JSONObject().apply {
            put("transcriptId", transcript.transcriptId)
            put("title", transcript.title)
            put("language", transcript.language)
            put("durationMs", transcript.durationMs)
            put("speakerCount", transcript.speakerCount)
            put("status", transcript.status)
            put("createdAt", transcript.createdAt)
            put("updatedAt", transcript.updatedAt)

            val segmentsArray = JSONArray()
            segments.forEach { seg ->
                segmentsArray.put(JSONObject().apply {
                    put("segmentId", seg.segmentId)
                    put("transcriptId", seg.transcriptId)
                    put("startTimeMs", seg.startTimeMs)
                    put("endTimeMs", seg.endTimeMs)
                    put("text", seg.text)
                    put("speakerId", seg.speakerId ?: JSONObject.NULL)
                    put("speakerLabel", seg.speakerLabel ?: JSONObject.NULL)
                    put("speakerConfidence", seg.speakerConfidence)
                    put("asrConfidence", seg.asrConfidence)
                    put("isFinal", seg.isFinal)
                    put("sequenceIndex", seg.sequenceIndex)
                    put("createdAt", seg.createdAt)
                })
            }
            put("segments", segmentsArray)
        }

        transcriptFile(transcript.transcriptId).writeText(json.toString(2))
    }

    // ─── Lesen ─────────────────────────────────────────────────────

    fun getAllTranscripts(): List<TranscriptEntity> {
        return transcriptsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    TranscriptEntity(
                        transcriptId = json.getString("transcriptId"),
                        title = json.getString("title"),
                        language = json.optString("language", "de"),
                        durationMs = json.optLong("durationMs", 0L),
                        speakerCount = json.optInt("speakerCount", 0),
                        status = json.optString("status", "finalized"),
                        createdAt = json.optLong("createdAt", file.lastModified()),
                        updatedAt = json.optLong("updatedAt", file.lastModified()),
                    )
                } catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun getTranscript(id: String): TranscriptEntity? {
        val file = transcriptFile(id)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            TranscriptEntity(
                transcriptId = json.getString("transcriptId"),
                title = json.getString("title"),
                language = json.optString("language", "de"),
                durationMs = json.optLong("durationMs", 0L),
                speakerCount = json.optInt("speakerCount", 0),
                status = json.optString("status", "finalized"),
                createdAt = json.optLong("createdAt", file.lastModified()),
                updatedAt = json.optLong("updatedAt", file.lastModified()),
            )
        } catch (_: Exception) { null }
    }

    fun getSegments(transcriptId: String): List<SegmentEntity> {
        val file = transcriptFile(transcriptId)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val segmentsArray = json.getJSONArray("segments")
            (0 until segmentsArray.length()).map { i ->
                val seg = segmentsArray.getJSONObject(i)
                SegmentEntity(
                    segmentId = seg.getString("segmentId"),
                    transcriptId = seg.getString("transcriptId"),
                    startTimeMs = seg.optLong("startTimeMs", 0L),
                    endTimeMs = seg.optLong("endTimeMs", 0L),
                    text = seg.getString("text"),
                    speakerId = seg.optString("speakerId", null),
                    speakerLabel = if (seg.isNull("speakerLabel")) null else seg.optString("speakerLabel", "Sprecher 1"),
                    speakerConfidence = seg.optDouble("speakerConfidence", 0.0).toFloat(),
                    asrConfidence = seg.optDouble("asrConfidence", 0.0).toFloat(),
                    isFinal = seg.optBoolean("isFinal", true),
                    sequenceIndex = seg.optInt("sequenceIndex", 0),
                    createdAt = seg.optLong("createdAt", 0L),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ─── Suchen ────────────────────────────────────────────────────

    fun searchTranscripts(query: String): List<TranscriptEntity> {
        val q = query.lowercase()
        // Erst nach Titel durchsuchen
        val titleMatches = getAllTranscripts().filter { it.title.lowercase().contains(q) }
        // Dann nach Segment-Text durchsuchen
        val segmentMatches = transcriptsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.filter { file ->
                try {
                    val json = JSONObject(file.readText())
                    val segments = json.getJSONArray("segments")
                    (0 until segments.length()).any { i ->
                        segments.getJSONObject(i).optString("text", "").lowercase().contains(q)
                    }
                } catch (_: Exception) { false }
            }
            ?.mapNotNull { file ->
                val id = file.nameWithoutExtension
                titleMatches.find { it.transcriptId == id }
                    ?: getTranscript(id)
            }
            ?: emptyList()

        // Kombinieren ohne Duplikate
        val seen = mutableSetOf<String>()
        return (titleMatches + segmentMatches)
            .filter { seen.add(it.transcriptId) }
            .sortedByDescending { it.createdAt }
    }

    fun searchSegments(transcriptId: String, query: String): List<SegmentEntity> {
        val q = query.lowercase()
        return getSegments(transcriptId).filter { it.text.lowercase().contains(q) }
    }

    // ─── Update / Delete ───────────────────────────────────────────

    fun updateTitle(id: String, title: String) {
        val file = transcriptFile(id)
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            json.put("title", title)
            json.put("updatedAt", System.currentTimeMillis())
            file.writeText(json.toString(2))
        } catch (_: Exception) {}
    }

    fun deleteTranscript(id: String) {
        transcriptFile(id).delete()
    }

    // ─── Hilfsfunktionen ───────────────────────────────────────────

    private fun transcriptFile(id: String): File =
        transcriptsDir.resolve("$id.json")
}
