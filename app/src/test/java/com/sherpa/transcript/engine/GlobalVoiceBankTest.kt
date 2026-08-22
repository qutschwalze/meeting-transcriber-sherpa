package com.sherpa.transcript.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests für GlobalVoiceBank (Phase 7, persistente Speaker-Profile).
 *
 * Fake-Embeddings: One-Hot-artige Vektoren – value 1f → [1,0,0] (Stimme A),
 * 2f → [0,1,0] (Stimme B), 3f → [0,0,1] (Stimme C). Cos deterministisch.
 */
class GlobalVoiceBankTest {

    private fun emb(vararg values: Float) = values

    @Test
    fun `identify - identische Stimme matcht ueber 0_62`() {
        val bank = GlobalVoiceBank().apply { putProfile("anna", emb(1f, 0f, 0f), 1) }
        assertEquals("anna", bank.identify(emb(1f, 0f, 0f)))
    }

    @Test
    fun `identify - fremde Stimme liefert keinen Match`() {
        val bank = GlobalVoiceBank().apply { putProfile("anna", emb(1f, 0f, 0f), 1) }
        assertNull(bank.identify(emb(0f, 1f, 0f)))
    }

    @Test
    fun `identify - knapp unter Schwelle bleibt ohne Match`() {
        // cos([1,2,0],[1,0,0]) = 1/sqrt(5) ~ 0.447 < 0.62
        val bank = GlobalVoiceBank().apply { putProfile("anna", emb(1f, 0f, 0f), 1) }
        assertNull(bank.identify(emb(1f, 2f, 0f)))
    }

    @Test
    fun `identify - leere Bank liefert null`() {
        assertNull(GlobalVoiceBank().identify(emb(1f, 0f, 0f)))
    }

    @Test
    fun `enroll - rolling average update erhoeht sampleCount`() {
        val bank = GlobalVoiceBank().apply { putProfile("anna", emb(1f, 0f, 0f), 2) }
        bank.enroll("anna", emb(1f, 0f, 0f))
        assertEquals(3, bank.profileCount("anna"))
        // Mittelwert bleibt Stimme A
        assertEquals("anna", bank.identify(emb(1f, 0f, 0f)))
    }

    @Test
    fun `enroll - neues Profil wird angelegt`() {
        val bank = GlobalVoiceBank()
        bank.enroll("neu", emb(0f, 0f, 1f))
        assertTrue(bank.contains("neu"))
        assertEquals(1, bank.size)
    }

    @Test
    fun `autoEnrollFrom - bekannte Stimme wird gemerged, fremde wird neu`() {
        val bank = GlobalVoiceBank().apply { putProfile("anna", emb(1f, 0f, 0f), 2) }
        val result = bank.autoEnrollFrom(mapOf(0 to emb(1f, 0f, 0f), 1 to emb(0f, 1f, 0f)))

        assertEquals(setOf("anna"), result.mergedIds)
        assertEquals(1, result.newIds.size)
        assertEquals(2, bank.size)
        assertEquals(3, bank.profileCount("anna"))          // 2 -> 3 (rolling)
        assertTrue(bank.contains(result.newIds.single()))    // Stimme B als neues Profil
    }

    @Test
    fun `autoEnrollFrom - fremde Stimme unter Schwelle wird neues Profil`() {
        val bank = GlobalVoiceBank().apply { putProfile("anna", emb(1f, 0f, 0f), 1) }
        val result = bank.autoEnrollFrom(mapOf(0 to emb(1f, 2f, 0f)))   // cos ~0.447 < 0.62
        assertEquals(0, result.mergedIds.size)
        assertEquals(1, result.newIds.size)
        assertEquals(2, bank.size)
    }

    @Test
    fun `snapshot und load sind verlustfrei`() {
        val bank = GlobalVoiceBank().apply {
            putProfile("anna", emb(1f, 0f, 0f), 3)
            enroll("anna", emb(1f, 0f, 0f))
        }
        val restored = GlobalVoiceBank().apply { load(bank.snapshot()) }
        assertEquals(bank.size, restored.size)
        assertEquals(4, restored.profileCount("anna"))
        assertEquals("anna", restored.identify(emb(1f, 0f, 0f)))
    }

    @Test
    fun `bestMatch liefert beste Similarity fuer Diagnose`() {
        val bank = GlobalVoiceBank().apply {
            putProfile("anna", emb(1f, 0f, 0f), 1)
            putProfile("berta", emb(0f, 1f, 0f), 1)
        }
        val (id, sim) = bank.bestMatch(emb(1f, 1f, 0f))!!
        assertEquals("anna", id)
        assertEquals(0.707f, sim, 0.01f)
    }
}