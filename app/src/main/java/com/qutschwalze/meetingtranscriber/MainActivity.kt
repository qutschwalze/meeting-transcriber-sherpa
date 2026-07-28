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
    private var currentSpeakerId = 0
    private val currentTextBuffer = StringBuilder()

    // ASR Engines (always auto DE/EN)
    private var engineDE: SherpaEngine? = null
    private var engineEN: SherpaEngine? = null
    private var activeEngine: SherpaEngine? = null
    private var detectedLanguage: String? = null

    // Speaker detection via pitch
    private var speakerCount = 1
    private var lastEndpointMs = 0L
    private var currentPitch = 0f
    private val pitchHistory = mutableListOf<Float>()
    private val SPEAKER_DIFF_THRESHOLD = 15f  // Hz difference = new speaker
    private val MIN_SEGMENT_MS = 800
    private var lastAudioChunk: ShortArray? = null

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 200
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_GAIN = 2.5f
        private const val DETECTION_WINDOW_MS = 3000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        transcriptAdapter = LiveTranscriptAdapter()
        binding.rvTranscript.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { reverseLayout = true }
            adapter = transcriptAdapter
        }

        setupButtons()
        setupTextSizeSlider()
        checkPermissions()
    }

    // ── Menu (⋮) ──

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.overflow_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_transcripts -> startActivity(Intent(this, TranscriptListActivity::class.java))
            R.id.menu_about -> showAboutDialog()
        }
        return true
    }

    private fun showAboutDialog() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "4.0" }
        AlertDialog.Builder(this)
            .setTitle("ℹ️ About")
            .setMessage("Meeting Transcriber v$v\nEngine: Sherpa-ONNX 1.13.3\n\nOffline-Spracherkennung mit\nStreaming Zipformer Transducer.\n\nAuto-Erkennung: DE/EN/FR\nSpeaker-Erkennung über Pausen")
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Buttons & Controls ──

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
                withContext(Dispatchers.IO) { loadModel("de"); loadModel("en") }

                val buf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, buf * 2)

                isRecording.set(true)
                startTime = System.currentTimeMillis()
                currentSpeakerId = 0
                currentTextBuffer.clear()
                lastEndpointMs = 0
                speakerCount = 1
                transcriptAdapter.clear()
                audioRecord?.startRecording()

                binding.tvStatus.text = getString(R.string.status_recording)
                binding.fabRecord.text = getString(R.string.btn_stop)
                binding.fabRecord.isEnabled = true
                binding.tvPartial.visibility = View.VISIBLE
                binding.exportButtons.visibility = View.GONE
                binding.tvModelInfo.text = "🎙️ Auto (DE/EN)"

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
        val dir = ModelManager.getModelPath(this, lang) ?: throw Exception("Pfad: $lang")
        val files = ModelManager.getModelFiles(this, lang) ?: throw Exception("Dateien: $lang")
        val tokens = File(dir, "tokens.txt").absolutePath
        val engine = SherpaEngine(files.first, files.second, files.third, tokens)
        if (!engine.init()) throw Exception("Engine: $lang")
        when (lang) { "de" -> engineDE = engine; "en" -> engineEN = engine }
        if (activeEngine == null) activeEngine = engine
    }

    private fun stopRecording() {
        isRecording.set(false)
        handler.removeCallbacks(timerRunnable)
        audioRecord?.apply { try { stop() } catch (_: Exception) {}; release() }
        audioRecord = null

        flushCurrentText()
        binding.tvStatus.text = getString(R.string.status_ready)
        binding.fabRecord.text = getString(R.string.btn_start)
        binding.tvPartial.visibility = View.GONE
        binding.progressLevel.progress = 0

        val ld = if (detectedLanguage != null) " (${if (detectedLanguage == "de") "🇩🇪 DE" else "🇬🇧 EN"})" else ""
        binding.tvModelInfo.text = "Modell:$ld"

        if (transcriptAdapter.itemCount > 0) { binding.exportButtons.visibility = View.VISIBLE; saveTranscriptToFile() }
        engineDE?.release(); engineEN?.release(); engineDE = null; engineEN = null; activeEngine = null
    }

    // ── Audio Processing ──

    private inner class AudioTask : Runnable {
        override fun run() {
            val buf = ShortArray((0.1 * SAMPLE_RATE).toInt())
            val detBuf = mutableListOf<String>()

            while (isRecording.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read <= 0) continue

                val now = System.currentTimeMillis() - startTime
                val amp = ShortArray(read) { i -> (buf[i].toFloat() * AUDIO_GAIN).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }

                // Level
                var mx = 0
                for (i in 0 until read) { val a = Math.abs(amp[i].toInt()); if (a > mx) mx = a }
                handler.post { binding.progressLevel.progress = (mx * 100 / 32768).coerceIn(0, 100) }

                // Save chunk for pitch estimation
                lastAudioChunk = amp.copyOf(read)

                // Always feed both engines for auto-detect
                val float = FloatArray(read) { amp[it] / 32768.0f }
                if (detectedLanguage == null) autoDetect(float, now, detBuf)
                else feedEngine(activeEngine!!, float)
            }
        }

        private fun autoDetect(s: FloatArray, now: Long, buf: MutableList<String>) {
            val de = engineDE ?: return; val en = engineEN ?: return
            de.acceptWaveform(s); en.acceptWaveform(s)
            while (de.isReady()) de.decode(); while (en.isReady()) en.decode()

            val dt = de.getResult(); val et = en.getResult()
            if (dt.isNotBlank()) buf.add("de:$dt"); if (et.isNotBlank()) buf.add("en:$et")

            if (now > DETECTION_WINDOW_MS && buf.size > 5) {
                val dc = buf.count { it.startsWith("de:") && it.length > 4 }
                val ec = buf.count { it.startsWith("en:") && it.length > 4 }
                detectedLanguage = if (dc > ec) "de" else "en"
                activeEngine = if (detectedLanguage == "de") de else en
                val l = if (detectedLanguage == "de") "🇩🇪 DE" else "🇬🇧 EN"
                handler.post { binding.tvModelInfo.text = "🎙️ Erkannt: $l" }
            }

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
                if (text.isNotBlank()) {
                    // Check speaker change: compare pitch of current segment to history
                    val now = System.currentTimeMillis()
                    val segmentMs = now - lastEndpointMs

                    if (segmentMs > MIN_SEGMENT_MS && currentPitch > 0 && pitchHistory.size >= 2) {
                        val avgPitch = pitchHistory.average().toFloat()
                        val diff = Math.abs(currentPitch - avgPitch)

                        if (diff > SPEAKER_DIFF_THRESHOLD && pitchHistory.size > 1) {
                            // Significant pitch change — new speaker
                            flushCurrentText()
                            speakerCount++
                            currentSpeakerId = speakerCount - 1
                            pitchHistory.clear()
                        }
                    }

                    pitchHistory.add(currentPitch)
                    lastEndpointMs = now
                    currentTextBuffer.append(text)
                }
                engine.reset()
                flushCurrentText()
                handler.post { binding.tvPartial.visibility = View.GONE }
            } else if (text.isNotBlank()) {
                // Track pitch from audio buffer (estimated from last chunk)
                val lastChunk = lastAudioChunk
                if (lastChunk != null) {
                    currentPitch = estimatePitch(lastChunk)
                }
                handler.post {
                    binding.tvPartial.text = text
                    binding.tvPartial.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun flushCurrentText() {
        val text = currentTextBuffer.toString().trim()
        if (text.isNotBlank()) {
            val entry = TranscriptEntry(speakerId = currentSpeakerId, text = text)
            handler.post {
                transcriptAdapter.addEntry(entry)
                binding.rvTranscript.scrollToPosition(0)
            }
        }
        currentTextBuffer.clear()
    }

    /**
     * Estimate pitch (F0) via autocorrelation.
     * Returns fundamental frequency in Hz (0 = unvoiced/silence).
     * Male: ~85-180Hz, Female: ~165-255Hz
     */
    private fun estimatePitch(samples: ShortArray): Float {
        val n = samples.size
        if (n < 256) return 0f

        // Only check lags for 60-400Hz at 16kHz
        val minLag = 40   // 400Hz
        val maxLag = 267  // 60Hz

        var bestCorr = 0.0
        var bestLag = minLag

        for (lag in minLag..maxLag) {
            var corr = 0.0
            var norm1 = 0.0
            var norm2 = 0.0
            val cnt = n - lag
            for (i in 0 until cnt) {
                val a = samples[i].toDouble() / Short.MAX_VALUE
                val b = samples[i + lag].toDouble() / Short.MAX_VALUE
                corr += a * b
                norm1 += a * a
                norm2 += b * b
            }
            val norm = Math.sqrt(norm1 * norm2)
            if (norm > 0) {
                corr /= norm
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLag = lag
                }
            }
        }

        // Only return pitch if correlation is strong enough (voiced speech)
        if (bestCorr < 0.3) return 0f

        return (SAMPLE_RATE.toFloat() / bestLag)
    }

    // ── Utils ──

    private fun saveTranscriptToFile() {
        val lang = detectedLanguage ?: "auto"
        val file = TranscriptManager.saveTranscript(this, transcriptAdapter.getFullTranscript(), lang)
        if (file != null) Toast.makeText(this, "Gespeichert: ${TranscriptManager.getTranscriptDirPath(this)}", Toast.LENGTH_LONG).show()
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
    }
}
