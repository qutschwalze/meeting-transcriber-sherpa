package com.sherpa.transcript.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResamplerTest {

    @Test
    fun `identische Rate gibt Input unveraendert zurueck`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(input, AudioResampler.resample(input, 16_000, 16_000))
    }

    @Test
    fun `upsample verdoppelt die Samplezahl`() {
        val input = floatArrayOf(0f, 1f, 0f, -1f)   // 4 Samples @8k
        val out = AudioResampler.resample(input, 8_000, 16_000)
        assertEquals("4 Samples ×2 = 8", 8, out.size)
    }

    @Test
    fun `downsample halbiert die Samplezahl und erhaelt Werte naeherungsweise`() {
        // Sinus-artig: 16 Samples @32k → 8 @16k
        val input = FloatArray(16) { i -> kotlin.math.sin(i * Math.PI / 4).toFloat() }
        val out = AudioResampler.resample(input, 32_000, 16_000)
        assertEquals(8, out.size)
        assertTrue("Werte im Bereich [-1,1]", out.all { it in -1.001f..1.001f })
    }

    @Test
    fun `leerer Input oder invalide Raten liefern leeres Array`() {
        assertEquals(0, AudioResampler.resample(FloatArray(0), 44_100, 16_000).size)
        assertEquals(0, AudioResampler.resample(floatArrayOf(1f), 0, 16_000).size)
        assertEquals(0, AudioResampler.resample(floatArrayOf(1f), 44_100, -1).size)
    }
}