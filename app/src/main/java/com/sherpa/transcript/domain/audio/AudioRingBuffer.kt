package com.sherpa.transcript.domain.audio

/**
 * Lockfreier Ringbuffer für Float PCM-Audio.
 * - Feste Kapazität (Anzahl Frames à 160 Samples = 10ms bei 16kHz).
 * - Überschreibt älteste Einträge, wenn voll.
 * - `drain()` liefert alle gesammelten Samples als einen FloatArray.
 */
class AudioRingBuffer(
    /** Maximale Anzahl an Frames (ein Frame = 10ms) */
    private val capacity: Int = 800  // = 8 Sekunden
) {
    private val buffer = Array<FloatArray?>(capacity) { null }
    private var writeIndex = 0
    private var count = 0

    val size: Int get() = count

    fun push(frame: FloatArray) {
        buffer[writeIndex] = frame
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Entnimmt alle aktuellen Samples als einen zusammenhängenden FloatArray.
     * Leert den Buffer danach.
     */
    fun drain(): FloatArray {
        if (count == 0) return FloatArray(0)

        val totalSamples = (0 until count).sumOf { buffer[(writeIndex - count + it).mod(capacity)]?.size ?: 0 }
        val result = FloatArray(totalSamples)
        var offset = 0
        for (i in 0 until count) {
            val frame = buffer[(writeIndex - count + i).mod(capacity)] ?: continue
            frame.copyInto(result, offset)
            offset += frame.size
        }
        clear()
        return result
    }

    fun clear() {
        for (i in 0 until capacity) buffer[i] = null
        writeIndex = 0
        count = 0
    }
}
