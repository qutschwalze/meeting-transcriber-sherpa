package com.sherpa.transcript.domain.export

import com.sherpa.transcript.data.local.SegmentEntity
import com.sherpa.transcript.data.local.TranscriptEntity

/**
 * Phase 4 (0.6.2): Export-Formatierung für Transkripte.
 *
 * Pure Funktionen (keine Android-/org.json-Abhängigkeit → JVM-Unit-testbar).
 * TXT im Referenz-Stil (Sprecherblock + erster Timestamp, wie Di._07.52),
 * Markdown mit Metadaten-Kopf, JSON mit Escaping.
 */
object TranscriptExporter {

    /** Ein Sprecher-Block: aufeinanderfolgende Segmente mit gleichem Label. */
    data class Block(
        val label: String?,
        /** Phase 8 (0.7.4): Profil-Name des Blocks (aus SegmentEntity.speakerName). */
        val name: String?,
        val startMs: Long,
        val texts: MutableList<String> = mutableListOf(),
    )

    /**
     * Gruppiert aufeinanderfolgende Segmente mit demselben speakerLabel zu Blöcken
     * (Referenz-Stil: "Sprecher 1 00:00:08" + alle Sätze bis zum nächsten Sprecher).
     * Unlabeled Segmente bilden einen Block ohne Label (ehrlich, kein Rate-Label).
     * 0.6.3: Segment-Texte werden vor dem Gruppieren gesäubert (cleanSegmentText).
     */
    fun groupBySpeaker(segments: List<SegmentEntity>): List<Block> {
        val blocks = mutableListOf<Block>()
        for (seg in segments) {
            val clean = cleanSegmentText(seg.text)
            if (clean.isBlank()) continue
            val label = seg.speakerLabel
            val last = blocks.lastOrNull()
            if (last != null && last.label == label) {
                last.texts += clean
            } else {
                blocks += Block(label = label, name = seg.speakerName, startMs = seg.startTimeMs).apply { texts += clean }
            }
        }
        return blocks
    }

    /**
     * 0.6.3: ASR-Segmentierungs-Artfakte am Segmentanfang entfernen.
     *
     * Geräte-Befund 0.6.2 (Export-Test 11:32, transcript_e2c4341b.md): viele
     * Segmente beginnen mit einem führenden Punkt/Leerzeichen (z.B. ". Das ist
     * noch gar nicht abzusehen") – die Endpoint-Segmentierung lässt den Punkt
     * am Segmentende weg und das nächste Segment beginnt mit dem Satzzeichen.
     * Entfernt führende Satzzeichen (.,;:!?) + Whitespace wiederholt. Pure
     * Funktion (JVM-testbar).
     */
    fun cleanSegmentText(raw: String): String {
        var s = raw.trim()
        while (s.isNotEmpty() && s[0] in ".,;:!?") {
            s = s.substring(1).trimStart()
        }
        return s
    }

    // ─── TXT ────────────────────────────────────────────────────────────

    /**
     * Phase 7a (0.7.2): [profileNames] ersetzt das Block-Label (key = speakerLabel,
     * z.B. "Sprecher 1" → "Anna"). Leer = Verhalten wie bisher ("Sprecher N").
     */
    fun formatTxt(
        transcript: TranscriptEntity,
        segments: List<SegmentEntity>,
        profileNames: Map<String, String> = emptyMap(),
    ): String {
        val sb = StringBuilder()
        sb.append(transcript.title).append('\n')
        sb.append("Dauer: ").append(formatDuration(transcript.durationMs))
        sb.append(" · Sprecher: ").append(transcript.speakerCount).append('\n')
        for (block in groupBySpeaker(segments)) {
            sb.append('\n')
            val label = block.name ?: block.label?.let { profileNames[it] ?: it }
            if (label != null) {
                sb.append(label).append(' ').append(formatTimestampHms(block.startMs)).append('\n')
            } else {
                sb.append("— ").append(formatTimestampHms(block.startMs)).append('\n')
            }
            block.texts.forEach { sb.append(it).append('\n') }
        }
        return sb.toString().trimEnd() + "\n"
    }

    // ─── Markdown ───────────────────────────────────────────────────────

    /**
     * Phase 7a (0.7.2): [profileNames] ersetzt das Block-Label (key = speakerLabel).
     * Leer = Verhalten wie bisher.
     */
    fun formatMarkdown(
        transcript: TranscriptEntity,
        segments: List<SegmentEntity>,
        profileNames: Map<String, String> = emptyMap(),
    ): String {
        val sb = StringBuilder()
        sb.append("# ").append(transcript.title).append("\n\n")
        sb.append("**Dauer:** ").append(formatDuration(transcript.durationMs))
        sb.append(" · **Sprecher:** ").append(transcript.speakerCount).append('\n')
        appendSpeakerBlocks(sb, segments, profileNames)
        return sb.toString().trimEnd() + "\n"
    }

    /**
     * 0.11.0: Protokoll-Export – wie Markdown, aber mit Kopf + Teilnehmer-
     * Statistik (Redezeit, Anteil, Segmentzahl je Sprecher). Ideal für
     * Meeting-Notizen in der Wiki-Ablage (MirMirStack/BookStack).
     */
    fun formatProtocolMarkdown(
        transcript: TranscriptEntity,
        segments: List<SegmentEntity>,
        profileNames: Map<String, String> = emptyMap(),
    ): String {
        val sb = StringBuilder()
        sb.append("# ").append(transcript.title).append("\n\n")
        sb.append("**Dauer:** ").append(formatDuration(transcript.durationMs)).append('\n')
        val stats = SpeakerStats.compute(segments)
        sb.append("**Teilnehmer:** ").append(stats.size).append("\n\n")
        sb.append("## Teilnehmer & Redezeiten\n\n")
        sb.append("| Sprecher | Redezeit | Anteil | Segmente |\n")
        sb.append("|---|---|---|---|\n")
        stats.forEach { s ->
            sb.append("| ").append(s.label.replace("|", "\\|"))
                .append(" | ").append(SpeakerStats.formatDurationMs(s.totalMs))
                .append(" | ").append(s.percent).append(" %")
                .append(" | ").append(s.segmentCount).append(" |\n")
        }
        sb.append('\n')
        appendSpeakerBlocks(sb, segments, profileNames)
        return sb.toString().trimEnd() + "\n"
    }

    private fun appendSpeakerBlocks(
        sb: StringBuilder,
        segments: List<SegmentEntity>,
        profileNames: Map<String, String>,
    ) {
        for (block in groupBySpeaker(segments)) {
            val label = block.name ?: block.label?.let { profileNames[it] ?: it }
            sb.append("\n## ").append(label ?: "Unbekannt")
            sb.append(" · ").append(formatTimestampHms(block.startMs)).append('\n')
            // 0.6.3: Sätze eines Blocks als Fließtext verbinden.
            // 0.6.5: mit LEERZEICHEN statt soft break – die ASR-Segmente splitten
            // bei kurzen Pausen mitten im Satz; Zeilenumbrüche zerstückeln den
            // Text (Geräte-Befund 0.6.4: "…fallen mir schon ein\nBeispiel der…").
            sb.append(block.texts.joinToString(" ")).append('\n')
        }
    }

    // ─── JSON ───────────────────────────────────────────────────────────

    fun formatJson(transcript: TranscriptEntity, segments: List<SegmentEntity>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        field(sb, "transcriptId", transcript.transcriptId, 1); sb.append(",\n")
        field(sb, "title", transcript.title, 1); sb.append(",\n")
        field(sb, "language", transcript.language, 1); sb.append(",\n")
        num(sb, "durationMs", transcript.durationMs, 1); sb.append(",\n")
        num(sb, "speakerCount", transcript.speakerCount.toLong(), 1); sb.append(",\n")
        num(sb, "createdAt", transcript.createdAt, 1); sb.append(",\n")
        num(sb, "updatedAt", transcript.updatedAt, 1); sb.append(",\n")
        sb.append("  \"segments\": [\n")
        segments.forEachIndexed { i, seg ->
            sb.append("    {\n")
            field(sb, "segmentId", seg.segmentId, 3); sb.append(",\n")
            num(sb, "startTimeMs", seg.startTimeMs, 3); sb.append(",\n")
            num(sb, "endTimeMs", seg.endTimeMs, 3); sb.append(",\n")
            field(sb, "text", seg.text, 3); sb.append(",\n")
            field(sb, "speakerId", seg.speakerId, 3); sb.append(",\n")
            field(sb, "speakerLabel", seg.speakerLabel, 3); sb.append('\n')
            sb.append("    }")
            if (i < segments.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun field(sb: StringBuilder, key: String, value: String?, indent: Int) {
        repeat(indent) { sb.append(' ') }
        sb.append('"').append(key).append("\": ")
        if (value == null) sb.append("null") else sb.append('"').append(jsonEscape(value)).append('"')
    }

    private fun num(sb: StringBuilder, key: String, value: Long, indent: Int) {
        repeat(indent) { sb.append(' ') }
        sb.append('"').append(key).append("\": ").append(value)
    }

    fun jsonEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    // ─── Format-Helper ──────────────────────────────────────────────────

    fun formatTimestampHms(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "${min}:${sec.toString().padStart(2, '0')} Min"
    }
}
