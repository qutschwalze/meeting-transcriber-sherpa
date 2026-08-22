package com.sherpa.transcript.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests für SessionVoiceBank (Hebel G – akustischer Drift-Schutz).
 *
 * Fake-Computer: erzeugt deterministische One-Hot-Embeddings anhand des
 * Sample-Werts (1f → [1,0,0] = Stimme A, 2f → [0,1,0] = Stimme B).
 */
class SessionVoiceBankTest {

    /** Fake-Computer: Sample-Wert bestimmt die "Stimme". */
    private class FakeComputer : SpeakerEmbeddingComputer {
        override fun computeEmbedding(samples: FloatArray): FloatArray? {
            if (samples.isEmpty()) return null
            val value = samples[0]
            return when {
                value <= 1.5f -> floatArrayOf(1f, 0f, 0f) // Stimme A
                value <= 2.5f -> floatArrayOf(0f, 1f, 0f) // Stimme B
                else -> floatArrayOf(0f, 0f, 1f)          // Stimme C
            }
        }
    }

    private fun samplesOf(value: Float, seconds: Float): FloatArray {
        val count = (seconds * 16000).toInt()
        return FloatArray(count) { value }
    }

    @Test
    fun `cosineSimilarity - identische Vektoren = 1, orthogonale = 0`() {
        assertEquals("identisch", 1f, SessionVoiceBank.cosineSimilarity(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f)), 0.001f)
        assertEquals("orthogonal", 0f, SessionVoiceBank.cosineSimilarity(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)), 0.001f)
        assertEquals("teilweise", 0.707f, SessionVoiceBank.cosineSimilarity(
            floatArrayOf(1f, 1f, 0f), floatArrayOf(1f, 0f, 0f)), 0.01f)
    }

    @Test
    fun `enroll dann identify - gleiche Stimme wird erkannt`() {
        val bank = SessionVoiceBank(FakeComputer())
        // 1. Kontakt (3s < Quick-Confirm 4s) → nur pending, noch kein Voiceprint
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 3_000L)
        assertEquals("1. Kontakt = pending, noch kein bestätigter Sprecher", 0, bank.speakerCount)
        assertEquals("1 pending Enrollment", 1, bank.pendingCount)

        // 2. Kontakt via identify → Bestätigung + Match
        val match = bank.identify(samplesOf(1f, 5f))
        assertEquals("Stimme A → global 0", 0, match)
        assertEquals("nach Bestätigung: 1 Sprecher", 1, bank.speakerCount)
        assertEquals("pending ist aufgelöst", 0, bank.pendingCount)
    }

    @Test
    fun `einmaliger Fehlcluster wird nie eingeschrieben`() {
        val bank = SessionVoiceBank(FakeComputer())
        // 1. Kontakt einer Stimme, die nie wieder auftaucht (3s < Quick-Confirm)
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 3_000L)
        assertEquals("pending, nicht eingeschrieben", 0, bank.speakerCount)
        assertEquals("1 pending", 1, bank.pendingCount)

        // Fremde Stimme kommt → kein Match mit dem pending
        val match = bank.identify(samplesOf(2f, 5f))
        assertNull("fremde Stimme matcht nicht", match)
        // Das pending bleibt pending – wird erst am Session-Ende verworfen
        assertEquals("Fehlcluster bleibt pending", 1, bank.pendingCount)
    }

    @Test
    fun `identify - fremde Stimme liefert keinen Match`() {
        val bank = SessionVoiceBank(FakeComputer())
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 3_000L)

        val match = bank.identify(samplesOf(2f, 5f))
        assertNull("Stimme B ist nicht Stimme A", match)
    }

    @Test
    fun `Phase 6 Quick-Confirm - langer erster Kontakt wird sofort bestaetigt`() {
        val bank = SessionVoiceBank(FakeComputer())
        // 1. Kontakt mit >= 4s Redezeit (Phase 6: Quick-Confirm für Meetings) →
        // sofort bestätigt, KEIN 2. Kontakt nötig
        val enrolled = bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 6_000L)
        assertTrue("langer 1. Kontakt wird sofort eingeschrieben", enrolled)
        assertEquals("sofort bestätigt", 1, bank.speakerCount)
        assertEquals("kein pending mehr", 0, bank.pendingCount)

        // identify erkennt die Stimme (Match gegen den bestätigten Voiceprint)
        val match = bank.identify(samplesOf(1f, 5f))
        assertEquals("Stimme A → global 0", 0, match)
    }

    @Test
    fun `enroll unter Mindestdauer wird verworfen`() {
        val bank = SessionVoiceBank(FakeComputer(), minEnrollmentSec = 5f)
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 2_000L)

        assertEquals("zu kurzes Enrollment wird verworfen", 0, bank.speakerCount)
    }

    @Test
    fun `rolling update - mehrere Beitraege stabilisieren das Voiceprint`() {
        val bank = SessionVoiceBank(FakeComputer())
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 10_000L)
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 10_000L)
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 10_000L)

        assertEquals("mehrere Beiträge = immer noch 1 Sprecher", 1, bank.speakerCount)
        // Gewichteter Durchschnitt bleibt Stimme A
        assertEquals("Stimme A weiterhin erkennbar", 0, bank.identify(samplesOf(1f, 5f)))
    }

    @Test
    fun `zwei Sprecher - korrekte Unterscheidung`() {
        val bank = SessionVoiceBank(FakeComputer())
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 10_000L)
        bank.enroll(globalId = 1, samples = samplesOf(2f, 10f), durationMs = 10_000L)

        assertEquals("Stimme A → global 0", 0, bank.identify(samplesOf(1f, 5f)))
        assertEquals("Stimme B → global 1", 1, bank.identify(samplesOf(2f, 5f)))
        assertEquals("2 Sprecher in der Bank", 2, bank.speakerCount)
    }

    @Test
    fun `reset leert die Bank`() {
        val bank = SessionVoiceBank(FakeComputer())
        bank.enroll(globalId = 0, samples = samplesOf(1f, 10f), durationMs = 10_000L)
        bank.reset()

        assertEquals("Bank leer nach reset", 0, bank.speakerCount)
        assertTrue(bank.enrolledSpeakerIds.isEmpty())
    }

    @Test
    fun `leere Bank - identify liefert null`() {
        val bank = SessionVoiceBank(FakeComputer())
        assertNull("leere Bank matcht nie", bank.identify(samplesOf(1f, 5f)))
    }

    @Test
    fun `confirmedVoiceprints - nur bestaetigte Kontakte, keine pending`() {
        val bank = SessionVoiceBank(FakeComputer())
        // Pending-Kontakt (3s < Quick-Confirm 4s) – wird NICHT confirmed
        bank.enroll(globalId = 7, samples = samplesOf(1f, 10f), durationMs = 3_000L)
        // Quick-Confirm-Kontakt (>= 4s) – wird sofort confirmed
        bank.enroll(globalId = 9, samples = samplesOf(2f, 10f), durationMs = 6_000L)

        val confirmed = bank.confirmedVoiceprints()
        assertEquals("nur der bestätigte Kontakt ist confirmed", setOf(9), confirmed.keys)
        assertTrue("pending-ID (7) ist NICHT enthalten", !confirmed.containsKey(7))
        // Vektor-Treue: Stimme B ([0,1,0])
        assertEquals(0f, confirmed.getValue(9)[0], 0f)
        assertEquals(1f, confirmed.getValue(9)[1], 0f)
    }
}
