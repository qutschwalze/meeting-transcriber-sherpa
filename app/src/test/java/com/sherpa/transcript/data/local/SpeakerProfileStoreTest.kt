package com.sherpa.transcript.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit-Tests für SpeakerProfileStore (Phase 7, JSON-Persistenz).
 */
class SpeakerProfileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeFile() = File(tmp.root!!, "nested/speakerProfiles.json")

    @Test
    fun `roundtrip erhaelt Profile inklusive Embedding`() {
        val store = SpeakerProfileStore(storeFile())
        val prof = SpeakerProfile(
            id = "p1",
            embedding = FloatArray(512) { i -> (i % 7) / 7f },
            sampleCount = 3,
            updatedAt = 42L,
        )
        store.saveAll(listOf(prof))

        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("p1", loaded[0].id)
        assertEquals(3, loaded[0].sampleCount)
        assertEquals(42L, loaded[0].updatedAt)
        assertEquals(512, loaded[0].embedding.size)
        assertEquals(prof.embedding.toList(), loaded[0].embedding.toList())
    }

    @Test
    fun `mehrere Profile ueberleben den Roundtrip`() {
        val store = SpeakerProfileStore(storeFile())
        store.saveAll(
            listOf(
                SpeakerProfile("a", floatArrayOf(1f, 0f, 0f), 1, 1L),
                SpeakerProfile("b", floatArrayOf(0f, 1f, 0f), 5, 2L),
            )
        )
        val loaded = store.loadAll()
        assertEquals(2, loaded.size)
        assertEquals(setOf("a", "b"), loaded.map { it.id }.toSet())
        assertEquals(5, loaded.first { it.id == "b" }.sampleCount)
    }

    @Test
    fun `kaputte Datei liefert leere Liste statt Crash`() {
        storeFile().apply { requireNotNull(parentFile).mkdirs(); writeText("{ kaputt") }
        val loaded = SpeakerProfileStore(storeFile()).loadAll()
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `fehlende Datei liefert leere Liste`() {
        assertTrue(SpeakerProfileStore(storeFile()).loadAll().isEmpty())
    }

    @Test
    fun `rueckwaerts-kompatibel - leere Profile-Liste wird ueberschrieben`() {
        val store = SpeakerProfileStore(storeFile())
        store.saveAll(listOf(SpeakerProfile("x", floatArrayOf(1f), 1, 1L)))
        store.saveAll(emptyList())
        assertTrue(store.loadAll().isEmpty())
    }
}