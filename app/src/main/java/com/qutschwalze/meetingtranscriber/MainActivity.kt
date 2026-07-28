package com.qutschwalze.meetingtranscriber

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.qutschwalze.meetingtranscriber.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var audioRecord: AudioRecord? = null
    private var isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L

    // Transcript
    private lateinit var transcriptAdapter: LiveTranscriptAdapter
    private var currentSpeakerId = -1
    private val currentTextBuffer = StringBuilder()

    // Engines
    private var engineDE: SherpaEngine? = null
    private var engineEN: SherpaEngine? = null
    private var activeEngine: SherpaEngine? = null
    private var detectedLanguage: String? = null
    private var autoMode = true
    private var selectedLangCode = "de"

    // Diarization
    private lateinit var diarizationManager: DiarizationManager
    private val audioBuffer = mutableListOf<FloatArray>()
    private var diarizationReady = false

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 200
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_GAIN = 2.5f
        private const val DETECTION_WINDOW_MS = 3000L
        // Process diarization every N seconds of audio
        private const val DIARIZATION_CHUNK_SECONDS = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // RecyclerView setup
        transcriptAdapter = LiveTranscriptAdapter()
        binding.rvTranscript.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = transcriptAdapter
        }

        // Initialize diarization
        lifecycleScope.launch(Dispatchers.IO) {
            diarizationManager = DiarizationManager(this@MainActivity)
            diarizationReady = diarizationManager.init()
            if (!diarizationReady) {
                handler.post { Toast.makeText(this@MainActivity, "Diarization-Modelle konnten nicht geladen werden", Toast.LENGTH_LONG).show() }
            }
        }

        setupButtons()
        setupLanguageChips()
        setupTextSizeSlider()
        checkPermissions()
    }

    // ── Overflow Menu ──

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.overflow_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_lang_auto -> { autoMode = true; selectedLangCode = "de"; updateChips() }
            R.id.menu_lang_de -> { autoMode = false; detectedLanguage = null; selectedLangCode = "de"; updateChips() }
            R.id.menu_lang_en -> { autoMode = false; detectedLanguage = null; selectedLangCode = "en"; updateChips() }
            R.id.menu_lang_fr -> { autoMode = false; detectedLanguage = null; selectedLangCode = "fr"; updateChips() }
            R.id.menu_transcripts -> startActivity(Intent(this, TranscriptListActivity::class.java))
            R.id.menu_about -> showAboutDialog()
        }
        return true
    }

    private fun showAboutDialog() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "3.0" }
        AlertDialog.Builder(this)
            .setTitle("ℹ️ About")
            .setMessage("Meeting Transcriber v$v\nEngine: Sherpa-ONNX 1.13.3\nSpeaker Diarization: Pyannote 3.0\n\nOffline-Spracherkennung mit\nStreaming Zipformer Transducer.\n\nFeatures:\n• Live-Transkription\n• Auto-Spracherkennung (DE/EN)\n• Native Speaker Diarization\n• 3 Sprachen (DE, EN, FR)")
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Language Chips ──

    private fun setupLanguageChips() {
        binding.chipGroupLang.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipAuto) -> { autoMode = true; selectedLangCode = "de" }
                checkedIds.contains(R.id.chipDe) -> { autoMode = false; detectedLanguage = null; selectedLangCode = "de" }
                checkedIds.contains(R.id.chipEn) -> { autoMode = false; detectedLanguage = null; selectedLangCode = "en" }
                checkedIds.contains(R.id.chipFr) -> { autoMode = false; detectedLanguage = null; selectedLangCode = "fr" }
            }
            updateChips()
        }
    }

    private fun updateChips() {
        binding.chipAuto.isChecked = autoMode
        binding.chipDe.isChecked = !autoMode && selectedLangCode == "de"
        binding.chipEn.isChecked = !autoMode && selectedLangCode == "en"
        binding.chipFr.isChecked = !autoMode && selectedLangCode == "fr"
    }

    // ── Buttons ──

    private fun setupButtons() {
        binding.fabRecord.setOnClickListener {
            if (isRecording.get()) stopRecording() else startRecording()
        }
        binding.btnCopy.setOnClickListener {
            val text = transcriptAdapter.getFullTranscript()
            if (text.isNotBlank()) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Transkript", text))
                Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, getString(R.string.no_transcript), Toast.LENGTH_SHORT).show()
        }
        binding.btnShare.setOnClickListener {
            val text = transcriptAdapter.getFullTranscript()
            if (text.isNotBlank()) {
                startActivity(Intent.createChooser(Intent().apply {
                    action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_TEXT, text); type = "text/plain"
                }, "Teilen"))
            } else Toast.makeText(this, getString(R.string.no_transcript), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTextSizeSlider() {
        binding.seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { transcriptAdapter.setTextSize(progress.toFloat()); binding.tvTextSizeLabel.text = "${progress}sp" }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Permissions ──

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED))
            Toast.makeText(this, "Mikrofon ist required", Toast.LENGTH_LONG).show()
    }

    // ── Recording ──

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { checkPermissions(); return }

        binding.tvStatus.text = getString(R.string.status_initializing)
        binding.fabRecord.isEnabled = false

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (autoMode) { loadModel("de"); loadModel("en") }
                    else loadModel(selectedLangCode)
                }

                val buf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, buf * 2)

                isRecording.set(true)
                startTime = System.currentTimeMillis()
                currentSpeakerId = 0  // Default speaker — diarization updates later
                currentTextBuffer.clear()
                audioBuffer.clear()
                transcriptAdapter.clear()

                audioRecord?.startRecording()

                binding.tvStatus.text = getString(R.string.status_recording)
                binding.fabRecord.text = getString(R.string.btn_stop)
                binding.fabRecord.isEnabled = true
                binding.rvTranscript.visibility = View.VISIBLE
                binding.tvPartial.visibility = View.VISIBLE
                binding.exportButtons.visibility = View.GONE

                if (autoMode) binding.tvModelInfo.text = "🤖 Auto-Erkennung aktiv…"
                handler.post(timerRunnable)
                recordingThread = Thread(AudioTask()).also { it.start() }
            } catch (e: Exception) {
                binding.tvStatus.text = "${getString(R.string.status_error)}: ${e.message}"
                binding.fabRecord.isEnabled = true
                binding.fabRecord.text = getString(R.string.btn_start)
            }
        }
    }

    private fun loadModel(lang: String) {
        if (!ModelManager.isModelAvailable(this, lang)) {
            handler.post { binding.tvStatus.text = "Modell $lang wird heruntergeladen…" }
            if (ModelManager.downloadModel(this, lang) { p -> handler.post { binding.tvStatus.text = "$lang: $p" } } == null)
                throw Exception("Download fehlgeschlagen: $lang")
        }
        val dir = ModelManager.getModelPath(this, lang) ?: throw Exception("Pfad nicht gefunden: $lang")
        val files = ModelManager.getModelFiles(this, lang) ?: throw Exception("Dateien nicht gefunden: $lang")
        val tokens = File(dir, "tokens.txt").absolutePath
        val engine = SherpaEngine(files.first, files.second, files.third, tokens)
        if (!engine.init()) throw Exception("Engine-Init fehlgeschlagen: $lang")
        when (lang) { "de" -> engineDE = engine; "en" -> engineEN = engine }
        if (activeEngine == null) activeEngine = engine
    }

    private fun stopRecording() {
        isRecording.set(false)
        handler.removeCallbacks(timerRunnable)
        audioRecord?.apply { try { stop() } catch (_: Exception) {}; release() }
        audioRecord = null

        // Flush remaining partial text
        flushCurrentText()

        // Run final diarization on remaining audio
        runDiarizationOnBuffer()

        binding.tvStatus.text = getString(R.string.status_ready)
        binding.fabRecord.text = getString(R.string.btn_start)
        binding.tvPartial.visibility = View.GONE

        val ld = when {
            autoMode && detectedLanguage != null -> " (${if (detectedLanguage == "de") "🇩🇪 DE" else "🇬🇧 EN"} erkannt)"
            !autoMode -> " (${selectedLangCode.uppercase()})" else -> ""
        }
        binding.tvModelInfo.text = "Modell: $ld"
        binding.progressLevel.progress = 0

        if (transcriptAdapter.itemCount > 0) {
            binding.exportButtons.visibility = View.VISIBLE
            saveTranscriptToFile()
        }

        engineDE?.release(); engineEN?.release(); engineDE = null; engineEN = null; activeEngine = null
    }

    // ── Audio Processing ──

    private inner class AudioTask : Runnable {
        override fun run() {
            val buf = ShortArray((0.1 * SAMPLE_RATE).toInt()) // 100ms chunks
            val detBuf = mutableListOf<String>()
            var chunkCounter = 0

            while (isRecording.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read <= 0) continue

                val now = System.currentTimeMillis() - startTime
                val amp = ShortArray(read) { i -> (buf[i].toFloat() * AUDIO_GAIN).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }

                // Level indicator
                var mx = 0
                for (i in 0 until read) { val a = Math.abs(amp[i].toInt()); if (a > mx) mx = a }
                handler.post { binding.progressLevel.progress = (mx * 100 / 32768).coerceIn(0, 100) }

                // Convert to float for Sherpa
                val float = FloatArray(read) { amp[it] / 32768.0f }

                // Buffer audio for diarization
                audioBuffer.add(float.copyOf())

                // Stream ASR
                if (autoMode && detectedLanguage == null) autoDetect(float, now, detBuf)
                else feedEngine(activeEngine!!, float)

                // Periodically run diarization on accumulated audio
                chunkCounter++
                val chunksPerDiarization = DIARIZATION_CHUNK_SECONDS * 10 // 100ms chunks
                if (chunkCounter >= chunksPerDiarization && diarizationReady) {
                    runDiarizationOnBuffer()
                    chunkCounter = 0
                }
            }
        }

        private fun autoDetect(s: FloatArray, now: Long, buf: MutableList<String>) {
            val de = engineDE ?: return; val en = engineEN ?: return
            de.acceptWaveform(s); en.acceptWaveform(s)
            while (de.isReady()) de.decode(); while (en.isReady()) en.decode()

            // Use partial results for language detection
            val dt = de.getResult(); val et = en.getResult()
            if (dt.isNotBlank()) buf.add("de:$dt"); if (et.isNotBlank()) buf.add("en:$et")

            if (now > DETECTION_WINDOW_MS && buf.size > 5) {
                val dc = buf.count { it.startsWith("de:") && it.length > 4 }
                val ec = buf.count { it.startsWith("en:") && it.length > 4 }
                detectedLanguage = if (dc > ec) "de" else "en"
                activeEngine = if (detectedLanguage == "de") de else en
                val l = if (detectedLanguage == "de") "🇩🇪 Deutsch" else "🇬🇧 English"
                handler.post { binding.tvModelInfo.text = "🤖 Erkannt: $l" }
            }

            // Show partial from DE engine during detection window
            if (now < DETECTION_WINDOW_MS) {
                val p = de.getResult()
                if (p.isNotBlank()) handler.post { binding.tvPartial.text = p; binding.tvPartial.visibility = View.VISIBLE }
            }
        }

        private fun feedEngine(engine: SherpaEngine, s: FloatArray) {
            engine.acceptWaveform(s)
            while (engine.isReady()) engine.decode()

            val text = engine.getResult()

            if (engine.isEndpoint()) {
                // Final result — add to transcript
                if (text.isNotBlank()) {
                    currentTextBuffer.append(text)
                }
                engine.reset()
                flushCurrentText()
                handler.post { binding.tvPartial.visibility = View.GONE }
            } else if (text.isNotBlank()) {
                // Partial result — show live, don't accumulate
                handler.post {
                    binding.tvPartial.text = text
                    binding.tvPartial.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun getPreviousSpeakerId(): Int {
        return if (transcriptAdapter.itemCount > 0) {
            // Get last entry's speaker ID from the adapter's entries
            -1 // Simplified — diarization handles this
        } else -1
    }

    private fun flushCurrentText() {
        val text = currentTextBuffer.toString().trim()
        if (text.isNotBlank() && currentSpeakerId >= 0) {
            val entry = TranscriptEntry(speakerId = currentSpeakerId, text = text)
            handler.post {
                transcriptAdapter.addEntry(entry)
                scrollToBottom()
            }
        }
        currentTextBuffer.clear()
    }

    // ── Diarization ──

    private fun runDiarizationOnBuffer() {
        if (audioBuffer.isEmpty() || !diarizationReady) return

        // Concatenate all buffered audio
        val totalSamples = audioBuffer.sumOf { it.size }
        val allSamples = FloatArray(totalSamples)
        var pos = 0
        for (chunk in audioBuffer) {
            System.arraycopy(chunk, 0, allSamples, pos, chunk.size)
            pos += chunk.size
        }

        // Run diarization
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val segments = diarizationManager.process(allSamples)
                if (segments.isNotEmpty()) {
                    // Rebuild transcript with speaker labels
                    handler.post {
                        transcriptAdapter.clear()
                        for (seg in segments) {
                            val startSample = (seg.start * SAMPLE_RATE).toInt().coerceIn(0, allSamples.size - 1)
                            val endSample = (seg.end * SAMPLE_RATE).toInt().coerceIn(startSample + 1, allSamples.size)

                            // Get text from ASR for this segment
                            // For now, use the accumulated text from streaming
                            val entry = TranscriptEntry(
                                speakerId = seg.speaker,
                                text = "(Segment ${String.format("%.1f", seg.start)}s - ${String.format("%.1f", seg.end)}s)",
                                startMs = (seg.start * 1000).toLong(),
                                endMs = (seg.end * 1000).toLong()
                            )
                            transcriptAdapter.addEntry(entry)
                        }
                        scrollToBottom()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ── Auto-Scroll ──

    private fun scrollToBottom() {
        val lm = binding.rvTranscript.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastCompletelyVisibleItemPosition()
        val total = transcriptAdapter.itemCount - 1

        // Only auto-scroll if user is already near the bottom
        if (lastVisible >= total - 2 || total <= 2) {
            binding.rvTranscript.scrollToPosition(total)
        }
    }

    // ── Utils ──

    private fun saveTranscriptToFile() {
        val lang = detectedLanguage ?: selectedLangCode
        val text = transcriptAdapter.getFullTranscript()
        val file = TranscriptManager.saveTranscript(this, text, lang)
        if (file != null) Toast.makeText(this, "Gespeichert: ${TranscriptManager.getTranscriptDirPath(this)}", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "Fehler beim Speichern", Toast.LENGTH_SHORT).show()
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording.get()) {
                val e = System.currentTimeMillis() - startTime
                binding.tvTimer.text = String.format("%02d:%02d:%02d", e / 3600000, (e / 60000) % 60, (e / 1000) % 60)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording.set(false)
        handler.removeCallbacks(timerRunnable)
        audioRecord?.apply { try { stop() } catch (_: Exception) {}; release() }
        engineDE?.release(); engineEN?.release()
        diarizationManager.release()
    }
}
