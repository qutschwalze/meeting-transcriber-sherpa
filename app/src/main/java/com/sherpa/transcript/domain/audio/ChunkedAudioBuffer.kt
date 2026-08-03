package com.sherpa.transcript.domain.audio

/**
 * Ein Diarization-Chunk: zusammenhängendes Audio inkl. Overlap-Kontext.
 *
 * @param samples     Das komplette Chunk-Audio (Overlap + neues Audio).
 * @param startSec    Absolute Session-Startzeit des Chunks (inkl. Overlap).
 * @param endSec      Absolute Session-Endzeit des Chunks.
 * @param overlapSec  Länge der Overlap-Zone am Chunk-Anfang (0 beim ersten Chunk).
 *                    Segmente in [startSec, startSec+overlapSec] müssen gegen die
 *                    bereits zugeordneten Speaker des vorherigen Chunks konsistent sein.
 * @param isFirstChunk True beim allerersten Chunk (kein Overlap-Kontext vorhanden).
 */
data class AudioChunk(
    val samples: FloatArray,
    val startSec: Float,
    val endSec: Float,
    val overlapSec: Float,
    val isFirstChunk: Boolean,
)

/**
 * ChunkedAudioBuffer – Rolling-Audio-Quelle für die Rolling-Reconciliation-Architektur.
 *
 * Kernidee (neu, experiment/neue-idee):
 * - Der bisherige `audioAccumulator` im LiveViewModel wächst bis 12 Minuten und jeder
 *   Diarization-Lauf kopiert ALLE Frames, um dann nur die letzten 30s zu verarbeiten.
 * - Dieser Buffer hält stattdessen nur ein begrenztes Fenster (maxWindowSec) und
 *   liefert deterministische Chunks: `chunkSec` neues Audio + `overlapSec` Kontext
 *   vom Ende des vorherigen Chunks (Speaker-Overlap-Puffer gegen Speaker-Drift).
 *
 * Zeitbasis: Frames werden mit ihrer absoluten Session-Startzeit (ms) gepusht –
 * dieselbe Basis wie `audioBaseTimeMs` im LiveViewModel (session-relative Zeit).
 *
 * Threading: synchronisiert (internes Lock). Push kommt aus dem Capture-Loop
 * (alle 10ms), takeChunk/takeRemainingChunk aus dem Diarization-Loop –
 * ohne Lock wäre das eine Data-Race. Ein uncontended synchronized kostet
 * ~20ns, bleibt also im 10ms-Takt non-blocking.
 */
class ChunkedAudioBuffer(
    /** Maximales Audio-Fenster in Sekunden, das im Speicher gehalten wird. */
    private val maxWindowSec: Float = 120f,
    /** Sample-Rate des Audio-Streams (16 kHz). */
    private val sampleRate: Int = 16000,
) {

    private val lock = Any()

    private data class TimedFrame(val samples: FloatArray, val startMs: Long)

    private val frames = ArrayDeque<TimedFrame>()

    /** Absolute Session-Endzeit des zuletzt gelieferten Chunks (ms), null vor dem ersten. */
    private var lastChunkEndMs: Long? = null

    /** Absolute Session-Endzeit des jüngsten gepushten Frames (ms). */
    private var newestEndMs: Long = 0L

    /** Dauer des aktuell gepufferten Audios in Sekunden. */
    val bufferedSec: Float
        get() = synchronized(lock) {
            if (frames.isEmpty()) return@synchronized 0f
            (newestEndMs - frames.first().startMs) / 1000f
        }

    /**
     * Pusht einen Audio-Frame mit seiner absoluten Session-Startzeit.
     * Verwirft älteste Frames, sobald das Fenster maxWindowSec überschreitet.
     */
    fun push(frame: FloatArray, absoluteStartMs: Long) = synchronized(lock) {
        if (frame.isEmpty()) return@synchronized
        frames.addLast(TimedFrame(frame, absoluteStartMs))
        newestEndMs = absoluteStartMs + (frame.size * 1000L / sampleRate)

        val oldestAllowedMs = newestEndMs - (maxWindowSec * 1000L).toLong()
        while (frames.isNotEmpty() && frames.first().startMs < oldestAllowedMs) {
            frames.removeFirst()
        }
    }

    /**
     * Liefert den nächsten Chunk: `chunkSec` neues Audio + `overlapSec` Kontext
     * vom vorherigen Chunk. Liefert null, wenn noch nicht genug neues Audio
     * eingetroffen ist (Chunk-Grenze noch nicht erreicht).
     *
     * - Erster Chunk: [0, chunkSec], overlapSec = 0
     * - Folge-Chunks: [prevEnd - overlap, prevEnd + chunkSec]
     *
     * Der Overlap-Bereich ist "best effort": wurde er bereits aus dem Fenster
     * verworfen, wird der Chunk mit dem verfügbaren Audio geliefert.
     */
    fun takeChunk(chunkSec: Float, overlapSec: Float): AudioChunk? = synchronized(lock) {
        if (frames.isEmpty()) return@synchronized null
        val chunkMs = (chunkSec * 1000f).toLong()
        val overlapMs = (overlapSec * 1000f).toLong()

        val prevEndMs = lastChunkEndMs
        val nextEndMs = (prevEndMs ?: 0L) + chunkMs

        // Noch nicht genug neues Audio seit dem letzten Chunk?
        if (newestEndMs < nextEndMs) return@synchronized null

        val isFirst = prevEndMs == null
        val windowStartMs = if (isFirst) maxOf(0L, nextEndMs - chunkMs) else nextEndMs - chunkMs - overlapMs

        // Samples im Fenster [windowStartMs, nextEndMs) extrahieren
        val samples = collectSamples(windowStartMs, nextEndMs)
        if (samples.isEmpty()) return@synchronized null

        lastChunkEndMs = nextEndMs
        AudioChunk(
            samples = samples,
            startSec = windowStartMs / 1000f,
            endSec = nextEndMs / 1000f,
            overlapSec = if (isFirst) 0f else overlapMs / 1000f,
            isFirstChunk = isFirst,
        )
    }

    /** Extrahiert alle Samples im Zeitfenster [fromMs, untilMs) als zusammenhängendes FloatArray. */
    private fun collectSamples(fromMs: Long, untilMs: Long): FloatArray {
        val result = mutableListOf<Float>()
        for (frame in frames) {
            val frameDurSamples = frame.samples.size
            val frameEndMs = frame.startMs + (frameDurSamples * 1000L / sampleRate)
            if (frameEndMs <= fromMs) continue
            if (frame.startMs >= untilMs) break

            // Alles in Samples relativ zum Frame-Anfang rechnen (keine Einheiten-Mischung)
            val skipSamples = maxOf(0L, fromMs - frame.startMs) * sampleRate / 1000L
            val keepSamples = minOf(
                frameDurSamples.toLong(),
                (untilMs - frame.startMs) * sampleRate / 1000L,
            )
            val startIdx = skipSamples.toInt().coerceIn(0, frameDurSamples)
            val endIdx = keepSamples.toInt().coerceIn(startIdx, frameDurSamples)
            for (i in startIdx until endIdx) {
                result.add(frame.samples[i])
            }
        }
        return result.toFloatArray()
    }

    /**
     * Liefert den REST-Chunk für den finalen Lauf (Stop): alles verfügbare Audio
     * seit dem letzten Chunk – auch wenn weniger als chunkSec zusammenkam.
     * Overlap vom vorherigen Chunk wird wie bei [takeChunk] mitgenommen.
     * Liefert null, wenn seit dem letzten Chunk nichts Neues kam.
     */
    fun takeRemainingChunk(chunkSec: Float, overlapSec: Float): AudioChunk? = synchronized(lock) {
        if (frames.isEmpty()) return@synchronized null
        val prevEndMs = lastChunkEndMs ?: return@synchronized takeChunk(chunkSec, overlapSec)
        val overlapMs = (overlapSec * 1000f).toLong()

        val windowStartMs = maxOf(frames.first().startMs, prevEndMs - overlapMs)
        val windowEndMs = newestEndMs
        if (windowEndMs <= prevEndMs) return@synchronized null // nichts Neues seit letztem Chunk

        val samples = collectSamples(windowStartMs, windowEndMs)
        if (samples.isEmpty()) return@synchronized null

        lastChunkEndMs = windowEndMs
        AudioChunk(
            samples = samples,
            startSec = windowStartMs / 1000f,
            endSec = windowEndMs / 1000f,
            overlapSec = (prevEndMs - windowStartMs) / 1000f,
            isFirstChunk = false,
        )
    }

    /** Leert den Buffer inkl. Chunk-Fortschritt (Start einer neuen Session). */
    fun clear() = synchronized(lock) {
        frames.clear()
        lastChunkEndMs = null
        newestEndMs = 0L
    }
}
