package com.sherpa.transcript.domain.export

import com.sherpa.transcript.data.local.SegmentEntity
import com.sherpa.transcript.data.local.TranscriptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExporterTest {

    private val transcript = TranscriptEntity(
        transcriptId = "t1",
        title = "Test-Gespräch",
        language = "de",
        durationMs = 105_000,
        speakerCount = 2,
        createdAt = 1000L,
        updatedAt = 2000L,
    )

    private val segments = listOf(
        SegmentEntity("s1", "t1", 8_000, 12_000, "Nicht mehr merken.", "speaker_0", "Sprecher 1"),
        SegmentEntity("s2", "t1", 12_000, 16_000, "Aber der Erfahrungsweg ist aus.", "speaker_0", "Sprecher 1"),
        SegmentEntity("s3", "t1", 62_000, 68_000, "Ich hab neulich mit meinem Sohn geredet.", "speaker_1", "Sprecher 2"),
        SegmentEntity("s4", "t1", 68_000, 70_000, "Er sagt das ähnlich.", "speaker_1", "Sprecher 2"),
    )

    @Test
    fun `groupBySpeaker fasst gleiche Labels zu Blöcken zusammen`() {
        val blocks = TranscriptExporter.groupBySpeaker(segments)
        assertEquals(2, blocks.size)
        assertEquals("Sprecher 1", blocks[0].label)
        assertEquals(8_000L, blocks[0].startMs)
        assertEquals(listOf("Nicht mehr merken.", "Aber der Erfahrungsweg ist aus."), blocks[0].texts)
        assertEquals("Sprecher 2", blocks[1].label)
        assertEquals(62_000L, blocks[1].startMs)
    }

    @Test
    fun `groupBySpeaker trennt unlabeled Segmente als eigenen Block ohne Label`() {
        val withUnlabeled = segments + SegmentEntity("s5", "t1", 90_000, 91_000, "Kurzes Fragment.", null, null)
        val blocks = TranscriptExporter.groupBySpeaker(withUnlabeled)
        assertEquals(3, blocks.size)
        assertTrue(blocks[2].label == null)
    }

    @Test
    fun `formatTxt liefert Referenz-Stil mit Sprecherblock und HH-MM-SS`() {
        val txt = TranscriptExporter.formatTxt(transcript, segments)
        assertTrue(txt.startsWith("Test-Gespräch\n"))
        assertTrue(txt.contains("Dauer: 1:45 Min · Sprecher: 2"))
        assertTrue(txt.contains("Sprecher 1 00:00:08\nNicht mehr merken.\nAber der Erfahrungsweg ist aus."))
        assertTrue(txt.contains("Sprecher 2 00:01:02\nIch hab neulich mit meinem Sohn geredet."))
        assertFalse(txt.contains("speaker_0")) // interne IDs erscheinen nicht im TXT
    }

    @Test
    fun `formatTxt markiert unlabeled Block mit Gedankenstrich`() {
        val withUnlabeled = segments + SegmentEntity("s5", "t1", 90_000, 91_000, "Kurzes Fragment.", null, null)
        val txt = TranscriptExporter.formatTxt(transcript, withUnlabeled)
        assertTrue(txt.contains("— 00:01:30\nKurzes Fragment."))
    }

    @Test
    fun `formatMarkdown enthält Titel Metadaten und Sprecher-Abschnitte`() {
        val md = TranscriptExporter.formatMarkdown(transcript, segments)
        assertTrue(md.startsWith("# Test-Gespräch\n"))
        assertTrue(md.contains("**Dauer:** 1:45 Min · **Sprecher:** 2"))
        assertTrue(md.contains("## Sprecher 1 · 00:00:08"))
        assertTrue(md.contains("## Sprecher 2 · 00:01:02"))
    }

    @Test
    fun `formatJson enthält Metadaten und Segmente mit Escaping`() {
        val special = SegmentEntity("s9", "t1", 1000, 2000, "Er sagte \"Hallo\"\nund ging.", "speaker_0", "Sprecher 1")
        val json = TranscriptExporter.formatJson(transcript, segments + special)
        assertTrue(json.contains("\"title\": \"Test-Gespräch\""))
        assertTrue(json.contains("\"speakerCount\": 2"))
        assertTrue(json.contains("\"startTimeMs\": 8000"))
        assertTrue(json.contains("\"speakerLabel\": \"Sprecher 1\""))
        assertTrue(json.contains("Er sagte \\\"Hallo\\\"\\nund ging."))
        assertTrue(json.trimEnd().endsWith("}"))
    }

    @Test
    fun `formatTimestampHms formatiert korrekt`() {
        assertEquals("00:00:08", TranscriptExporter.formatTimestampHms(8_000))
        assertEquals("00:01:02", TranscriptExporter.formatTimestampHms(62_000))
        assertEquals("01:01:02", TranscriptExporter.formatTimestampHms(3_662_000))
    }

    @Test
    fun `jsonEscape behandelt Sonderzeichen`() {
        assertEquals("a\\\"b", TranscriptExporter.jsonEscape("a\"b"))
        assertEquals("a\\\\b", TranscriptExporter.jsonEscape("a\\b"))
        assertEquals("a\\nb", TranscriptExporter.jsonEscape("a\nb"))
        assertEquals("a\\u0001b", TranscriptExporter.jsonEscape("a\u0001b"))
    }

    @Test
    fun `cleanSegmentText entfernt führende Satzzeichen und Whitespace`() {
        assertEquals("Das ist noch gar nicht abzusehen", TranscriptExporter.cleanSegmentText(". Das ist noch gar nicht abzusehen"))
        assertEquals("mit meinem Sohn", TranscriptExporter.cleanSegmentText(", mit meinem Sohn"))
        assertEquals("Hallo", TranscriptExporter.cleanSegmentText("...Hallo"))
        assertEquals("Hallo", TranscriptExporter.cleanSegmentText("   ,,Hallo  "))
        assertEquals("3. Punkt bleibt", TranscriptExporter.cleanSegmentText("3. Punkt bleibt")) // Zahl + Punkt nicht trimmen
        assertEquals("", TranscriptExporter.cleanSegmentText("..."))
        assertEquals("Nichts zu tun", TranscriptExporter.cleanSegmentText("Nichts zu tun"))
    }

    @Test
    fun `groupBySpeaker säubert Segment-Texte vor der Gruppierung`() {
        val dirty = listOf(
            SegmentEntity("s1", "t1", 8_000, 12_000, ". Nicht mehr merken.", "speaker_0", "Sprecher 1"),
            SegmentEntity("s2", "t1", 12_000, 16_000, ", aber der Erfahrungsweg ist aus.", "speaker_0", "Sprecher 1"),
        )
        val blocks = TranscriptExporter.groupBySpeaker(dirty)
        assertEquals(1, blocks.size)
        assertEquals(listOf("Nicht mehr merken.", "aber der Erfahrungsweg ist aus."), blocks[0].texts)
    }

    @Test
    fun `formatMarkdown verbindet Block-Sätze als zusammenhängenden Fließtext`() {
        val md = TranscriptExporter.formatMarkdown(transcript, segments)
        // 0.6.5: Leerzeichen-Verbindung – kein Zeilenumbruch mitten im Satz
        assertTrue(md.contains("Nicht mehr merken. Aber der Erfahrungsweg ist aus."))
        assertFalse(md.contains("Nicht mehr merken.\n"))
        assertTrue(md.contains("Ich hab neulich mit meinem Sohn geredet. Er sagt das ähnlich."))
    }

    @Test
    fun `formatMarkdown ersetzt benannte Profile im Block-Label`() {
        val md = TranscriptExporter.formatMarkdown(
            transcript, segments,
            profileNames = mapOf("Sprecher 1" to "Anna"),
        )
        assertTrue("Name statt Sprecher 1", md.contains("## Anna · "))
        assertTrue("unbenanntes Profil bleibt Sprecher N", md.contains("## Sprecher 2 · "))
        assertFalse("kein 'Sprecher 1' mehr", md.contains("## Sprecher 1"))
    }

    @Test
    fun `formatTxt ersetzt benannte Profile im Block-Label`() {
        val txt = TranscriptExporter.formatTxt(
            transcript, segments,
            profileNames = mapOf("Sprecher 2" to "Bernd"),
        )
        assertTrue("Name statt Sprecher 2", txt.contains("Bernd 00:01:02"))
        assertTrue("unbenanntes Profil bleibt", txt.contains("Sprecher 1 00:00:08"))
    }
}
