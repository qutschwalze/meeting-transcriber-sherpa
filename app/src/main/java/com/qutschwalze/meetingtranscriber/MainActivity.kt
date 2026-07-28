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
    private val transcriptBuilder = StringBuilder()
    private var currentSpeaker = -1
    private var lastSpeakerText = StringBuilder()  // Text for current speaker segment
    private lateinit var diarizer: SpeakerDiarizer

    private var engineDE: SherpaEngine? = null
    private var engineEN: SherpaEngine? = null
    private var activeEngine: SherpaEngine? = null
    private var detectedLanguage: String? = null
    private var autoMode = true
    private var selectedLangCode = "de"

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

        diarizer = SpeakerDiarizer(changeThreshold = 0.35f, matchThreshold = 0.25f, minSegmentMs = 500, silenceMs = 350)

        setupButtons()
        setupLanguageChips()
        setupTextSizeSlider()
        checkPermissions()

        binding.tvTranscript.textSize = 15f
    }

    // ── Overflow Menu (⋮) ──

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.overflow_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_lang_auto -> { autoMode = true; selectedLangCode = "de"; updateChips(); Toast.makeText(this, "🤖 Auto", Toast.LENGTH_SHORT).show() }
            R.id.menu_lang_de -> { autoMode = false; detectedLanguage = null; selectedLangCode = "de"; updateChips(); Toast.makeText(this, "🇩🇪 DE", Toast.LENGTH_SHORT).show() }
            R.id.menu_lang_en -> { autoMode = false; detectedLanguage = null; selectedLangCode = "en"; updateChips(); Toast.makeText(this, "🇬🇧 EN", Toast.LENGTH_SHORT).show() }
            R.id.menu_lang_fr -> { autoMode = false; detectedLanguage = null; selectedLangCode = "fr"; updateChips(); Toast.makeText(this, "🇫🇷 FR", Toast.LENGTH_SHORT).show() }
            R.id.menu_transcripts -> startActivity(Intent(this, TranscriptListActivity::class.java))
            R.id.menu_about -> showAboutDialog()
        }
        return true
    }

    private fun showAboutDialog() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "2.6" }
        AlertDialog.Builder(this)
            .setTitle("ℹ️ About")
            .setMessage("Meeting Transcriber v$v\nEngine: Sherpa-ONNX 1.13.3\n\nOffline-Spracherkennung mit\nStreaming Zipformer Transducer.\n\nFeatures:\n• Live-Transkription\n• Auto-Spracherkennung (DE/EN)\n• Speaker Diarization\n• 3 Sprachen (DE, EN, FR)\n\ngithub.com/qutschwalze/\nmeeting-transcriber-sherpa")
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
            val text = transcriptBuilder.toString()
            if (text.isNotBlank()) {
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Transkript", text))
                Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, getString(R.string.no_transcript), Toast.LENGTH_SHORT).show()
        }
        binding.btnShare.setOnClickListener {
            val text = transcriptBuilder.toString()
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
                if (fromUser) { binding.tvTranscript.textSize = progress.toFloat(); binding.tvTextSizeLabel.text = "${progress}sp" }
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
                transcriptBuilder.clear()
                lastSpeakerText.clear()
                currentSpeaker = -1
                diarizer.reset()
                audioRecord?.startRecording()

                binding.tvStatus.text = getString(R.string.status_recording)
                binding.fabRecord.text = getString(R.string.btn_stop)
                binding.fabRecord.isEnabled = true
                binding.tvTranscript.text = getString(R.string.transcript_placeholder)
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

        // Flush remaining speaker text
        flushSpeakerText()

        activeEngine?.let { val r = it.getResult(); if (r.isNotBlank()) addText(r) }

        binding.tvStatus.text = getString(R.string.status_ready)
        binding.fabRecord.text = getString(R.string.btn_start)
        val ld = when {
            autoMode && detectedLanguage != null -> " (${if (detectedLanguage == "de") "🇩🇪 DE" else "🇬🇧 EN"} erkannt)"
            !autoMode -> " (${selectedLangCode.uppercase()})" else -> ""
        }
        binding.tvModelInfo.text = "Modell: $ld"
        binding.progressLevel.progress = 0

        if (transcriptBuilder.isNotBlank()) { binding.exportButtons.visibility = View.VISIBLE; saveTranscriptToFile() }
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

                // Speaker diarization
                val rms = calcRMS(amp, read)
                if (rms > 0.015f) {
                    val sr = diarizer.analyze(amp, read, now)
                    if (sr.changed && currentSpeaker != sr.speakerId) {
                        // Flush previous speaker's text before switching
                        flushSpeakerText()
                        currentSpeaker = sr.speakerId
                    }
                }

                // Sherpa-ONNX
                val float = FloatArray(read) { amp[it] / 32768.0f }
                if (autoMode && detectedLanguage == null) autoDetect(float, now, detBuf)
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
                val l = if (detectedLanguage == "de") "🇩🇪 Deutsch" else "🇬🇧 English"
                handler.post { binding.tvModelInfo.text = "🤖 Erkannt: $l"; Toast.makeText(this@MainActivity, "Sprache: $l", Toast.LENGTH_SHORT).show() }
                activeEngine?.getResult()?.let { if (it.isNotBlank()) addText(it) }
            }
            if (now < DETECTION_WINDOW_MS) {
                val p = de.getResult()
                if (p.isNotBlank()) handler.post { binding.tvTranscript.text = "${transcriptBuilder.toString().trim()}\n[text…] $p…"; doScroll() }
            }
        }

        private fun feedEngine(engine: SherpaEngine, s: FloatArray) {
            engine.acceptWaveform(s)
            while (engine.isReady()) engine.decode()
            if (engine.isEndpoint()) {
                val t = engine.getResult(); if (t.isNotBlank()) addText(t); engine.reset()
            } else {
                val p = engine.getResult()
                if (p.isNotBlank()) handler.post { binding.tvTranscript.text = "${transcriptBuilder.toString().trim()}\n$p…"; doScroll() }
            }
        }
    }

    // ── Speaker Text Management ──

    /** Called when speaker changes — flush accumulated text for previous speaker */
    private fun flushSpeakerText() {
        if (currentSpeaker >= 0 && lastSpeakerText.isNotEmpty()) {
            val text = lastSpeakerText.toString().trim()
            if (text.isNotBlank()) {
                transcriptBuilder.append("[Sprecher $currentSpeaker]\n")
                transcriptBuilder.append(text).append("\n\n")
                lastSpeakerText.clear()
                handler.post { updateDisplay() }
            }
        }
        lastSpeakerText.clear()
    }

    private fun addText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        lastSpeakerText.append(clean).append(" ")
        // Also update display with current accumulated text
        handler.post { updateDisplay() }
    }

    private fun updateDisplay() {
        // Build display: transcript + current speaker's pending text
        val display = StringBuilder(transcriptBuilder)
        if (currentSpeaker >= 0 && lastSpeakerText.isNotEmpty()) {
            if (display.isNotEmpty()) display.append("\n")
            display.append("[Sprecher $currentSpeaker]\n")
            display.append(lastSpeakerText.toString().trim())
        }
        binding.tvTranscript.text = display.toString().trim()
        doScroll()
    }

    // ── Auto-Scroll ──

    private fun doScroll() {
        binding.scrollTranscript.post {
            binding.scrollTranscript.postDelayed({
                val child = binding.scrollTranscript.getChildAt(0) ?: return@postDelayed
                val sh = binding.scrollTranscript.height
                val ch = child.height
                if (ch > sh) {
                    val target = (ch * 0.65).toInt() - sh / 2
                    binding.scrollTranscript.scrollTo(0, target.coerceAtLeast(0))
                }
            }, 50)
        }
    }

    // ── Utils ──

    private fun calcRMS(s: ShortArray, n: Int): Float {
        var sq = 0.0
        for (i in 0 until n) { val v = s[i].toDouble() / Short.MAX_VALUE; sq += v * v }
        return kotlin.math.sqrt(sq / n).toFloat()
    }

    private fun saveTranscriptToFile() {
        val lang = detectedLanguage ?: selectedLangCode
        val file = TranscriptManager.saveTranscript(this, transcriptBuilder.toString(), lang)
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
    }
}
