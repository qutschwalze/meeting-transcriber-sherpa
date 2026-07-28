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
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
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
    private lateinit var diarizer: SpeakerDiarizer

    // Sherpa-ONNX engines
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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        diarizer = SpeakerDiarizer(sensitivity = 1.8f, minSegmentDurationMs = 600, silenceThresholdMs = 400)

        setupDrawer()
        setupButtons()
        setupLanguageChips()
        setupTextSizeSlider()
        checkPermissions()

        // Set initial text size
        binding.tvTranscript.textSize = 15f
    }

    private fun setupDrawer() {
        // Hamburger toggle
        binding.toolbar.setNavigationOnClickListener {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // Drawer item clicks
        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_lang_auto -> {
                    autoMode = true
                    selectedLangCode = "de"
                    updateLanguageCheckmarks()
                    Toast.makeText(this, "🤖 Auto-Erkennung", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_lang_de -> {
                    autoMode = false
                    detectedLanguage = null
                    selectedLangCode = "de"
                    updateLanguageCheckmarks()
                    Toast.makeText(this, "🇩🇪 Deutsch", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_lang_en -> {
                    autoMode = false
                    detectedLanguage = null
                    selectedLangCode = "en"
                    updateLanguageCheckmarks()
                    Toast.makeText(this, "🇬🇧 English", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_lang_fr -> {
                    autoMode = false
                    detectedLanguage = null
                    selectedLangCode = "fr"
                    updateLanguageCheckmarks()
                    Toast.makeText(this, "🇫🇷 Français", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_transcripts -> {
                    startActivity(Intent(this, TranscriptListActivity::class.java))
                }
                R.id.nav_about -> {
                    showAboutDialog()
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        updateLanguageCheckmarks()
    }

    private fun updateLanguageCheckmarks() {
        // Update drawer menu
        val menu = binding.navView.menu
        menu.findItem(R.id.nav_lang_auto)?.isChecked = autoMode
        menu.findItem(R.id.nav_lang_de)?.isChecked = !autoMode && selectedLangCode == "de"
        menu.findItem(R.id.nav_lang_en)?.isChecked = !autoMode && selectedLangCode == "en"
        menu.findItem(R.id.nav_lang_fr)?.isChecked = !autoMode && selectedLangCode == "fr"

        // Update chips on main page
        binding.chipAuto.isChecked = autoMode
        binding.chipDe.isChecked = !autoMode && selectedLangCode == "de"
        binding.chipEn.isChecked = !autoMode && selectedLangCode == "en"
        binding.chipFr.isChecked = !autoMode && selectedLangCode == "fr"
    }

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "2.3" }

        AlertDialog.Builder(this, R.style.Theme_MeetingTranscriber)
            .setTitle("ℹ️ About")
            .setMessage(
                "Meeting Transcriber\n" +
                "Version: $versionName\n" +
                "Engine: Sherpa-ONNX 1.13.3\n\n" +
                "Offline-Spracherkennung mit\n" +
                "Streaming Zipformer Transducer.\n\n" +
                "Features:\n" +
                "• Live-Transkription\n" +
                "• Auto-Spracherkennung (DE/EN)\n" +
                "• Speaker Diarization\n" +
                "• 3 Sprachen (DE, EN, FR)\n\n" +
                "github.com/qutschwalze/\nmeeting-transcriber-sherpa"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupButtons() {
        binding.fabRecord.setOnClickListener {
            if (isRecording.get()) stopRecording() else startRecording()
        }

        binding.btnCopy.setOnClickListener {
            val text = transcriptBuilder.toString()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Transkript", text))
                Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.no_transcript), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShare.setOnClickListener {
            val text = transcriptBuilder.toString()
            if (text.isNotBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, "Meeting-Transkript")
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, "Transkript teilen"))
            } else {
                Toast.makeText(this, getString(R.string.no_transcript), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupLanguageChips() {
        binding.chipAuto.setOnClickListener {
            autoMode = true
            selectedLangCode = "de"
            updateLanguageCheckmarks()
        }
        binding.chipDe.setOnClickListener {
            autoMode = false
            detectedLanguage = null
            selectedLangCode = "de"
            updateLanguageCheckmarks()
        }
        binding.chipEn.setOnClickListener {
            autoMode = false
            detectedLanguage = null
            selectedLangCode = "en"
            updateLanguageCheckmarks()
        }
        binding.chipFr.setOnClickListener {
            autoMode = false
            detectedLanguage = null
            selectedLangCode = "fr"
            updateLanguageCheckmarks()
        }
    }

    private fun setupTextSizeSlider() {
        binding.seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val size = progress.toFloat()
                    binding.tvTranscript.textSize = size
                    binding.tvTextSizeLabel.text = "${progress}sp"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Mikrofon ist required für Transkription", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        binding.tvStatus.text = getString(R.string.status_initializing)
        binding.fabRecord.isEnabled = false

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (autoMode) {
                        loadModel("de")
                        loadModel("en")
                    } else {
                        loadModel(selectedLangCode)
                    }
                }

                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2
                )

                isRecording.set(true)
                startTime = System.currentTimeMillis()
                transcriptBuilder.clear()
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
                recordingThread = Thread(AudioRecognitionTask()).also { it.start() }
            } catch (e: Exception) {
                binding.tvStatus.text = "${getString(R.string.status_error)}: ${e.message}"
                binding.fabRecord.isEnabled = true
                binding.fabRecord.text = getString(R.string.btn_start)
            }
        }
    }

    private fun loadModel(langCode: String) {
        if (!ModelManager.isModelAvailable(this, langCode)) {
            handler.post { binding.tvStatus.text = "Modell $langCode wird heruntergeladen…" }
            val result = ModelManager.downloadModel(this, langCode) { progress ->
                handler.post { binding.tvStatus.text = "$langCode: $progress" }
            }
            if (result == null) throw Exception("Download fehlgeschlagen: $langCode")
        }

        val modelDir = ModelManager.getModelPath(this, langCode)
            ?: throw Exception("Modell-Pfad nicht gefunden: $langCode")
        val files = ModelManager.getModelFiles(this, langCode)
            ?: throw Exception("Modell-Dateien nicht gefunden: $langCode")
        val tokens = File(modelDir, "tokens.txt").absolutePath

        val engine = SherpaEngine(files.first, files.second, files.third, tokens)
        if (!engine.init()) throw Exception("Engine-Init fehlgeschlagen: $langCode")

        when (langCode) {
            "de" -> engineDE = engine
            "en" -> engineEN = engine
        }
        if (activeEngine == null) activeEngine = engine
    }

    private fun stopRecording() {
        isRecording.set(false)
        handler.removeCallbacks(timerRunnable)

        audioRecord?.apply { try { stop() } catch (_: Exception) {}; release() }
        audioRecord = null

        activeEngine?.let { engine ->
            val finalResult = engine.getResult()
            if (finalResult.isNotBlank()) processResultText(finalResult)
        }

        binding.tvStatus.text = getString(R.string.status_ready)
        binding.fabRecord.text = getString(R.string.btn_start)

        val langDisplay = when {
            autoMode && detectedLanguage != null ->
                " (${if (detectedLanguage == "de") "🇩🇪 DE" else "🇬🇧 EN"} erkannt)"
            !autoMode -> " (${selectedLangCode.uppercase()})"
            else -> ""
        }
        binding.tvModelInfo.text = "Modell: $langDisplay"
        binding.progressLevel.progress = 0

        if (transcriptBuilder.isNotBlank()) {
            binding.exportButtons.visibility = View.VISIBLE
            saveTranscriptToFile()
        }

        engineDE?.release(); engineEN?.release()
        engineDE = null; engineEN = null; activeEngine = null
    }

    private inner class AudioRecognitionTask : Runnable {
        override fun run() {
            val bufferSize = (0.1 * SAMPLE_RATE).toInt()
            val buffer = ShortArray(bufferSize)
            val detectionBuffer = mutableListOf<String>()

            while (isRecording.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val currentTimeMs = System.currentTimeMillis() - startTime
                    val amplified = amplifyAudio(buffer, read)

                    // Audio level
                    var maxLevel = 0
                    for (i in 0 until read) {
                        val level = Math.abs(amplified[i].toInt())
                        if (level > maxLevel) maxLevel = level
                    }
                    handler.post { binding.progressLevel.progress = (maxLevel * 100 / 32768).coerceIn(0, 100) }

                    // Speaker diarization
                    val rms = calculateRMS(amplified, read)
                    if (rms > 0.02f) {
                        val speakerResult = diarizer.analyze(amplified, read, currentTimeMs)
                        if (speakerResult.changed) {
                            currentSpeaker = speakerResult.speakerId
                            handler.post {
                                if (transcriptBuilder.isNotBlank()) transcriptBuilder.append("\n\n")
                                transcriptBuilder.append("[Sprecher $currentSpeaker]\n")
                                updateTranscriptDisplay()
                            }
                        }
                    }

                    // Sherpa-ONNX needs FloatArray
                    val floatSamples = FloatArray(read) { amplified[it] / 32768.0f }

                    if (autoMode && detectedLanguage == null) {
                        autoDetectLanguage(floatSamples, currentTimeMs, detectionBuffer)
                    } else {
                        feedEngine(activeEngine!!, floatSamples)
                    }
                }
            }
        }

        private fun autoDetectLanguage(samples: FloatArray, currentTimeMs: Long, buffer: MutableList<String>) {
            val de = engineDE ?: return
            val en = engineEN ?: return

            de.acceptWaveform(samples); en.acceptWaveform(samples)
            while (de.isReady()) de.decode()
            while (en.isReady()) en.decode()

            val deText = de.getResult()
            val enText = en.getResult()
            if (deText.isNotBlank()) buffer.add("de:$deText")
            if (enText.isNotBlank()) buffer.add("en:$enText")

            if (currentTimeMs > DETECTION_WINDOW_MS && buffer.size > 5) {
                val deCount = buffer.count { it.startsWith("de:") && it.length > 4 }
                val enCount = buffer.count { it.startsWith("en:") && it.length > 4 }
                detectedLanguage = if (deCount > enCount) "de" else "en"
                activeEngine = if (detectedLanguage == "de") de else en
                val label = if (detectedLanguage == "de") "🇩🇪 Deutsch" else "🇬🇧 English"
                handler.post {
                    binding.tvModelInfo.text = "🤖 Erkannt: $label"
                    Toast.makeText(this@MainActivity, "Sprache erkannt: $label", Toast.LENGTH_SHORT).show()
                }
                activeEngine?.getResult()?.let { if (it.isNotBlank()) processResultText(it) }
            }

            if (currentTimeMs < DETECTION_WINDOW_MS) {
                val partial = de.getResult()
                if (partial.isNotBlank()) {
                    handler.post {
                        binding.tvTranscript.text = "${transcriptBuilder.toString().trim()}\n[text…] $partial…"
                        smoothScrollToBottom()
                    }
                }
            }
        }

        private fun feedEngine(engine: SherpaEngine, samples: FloatArray) {
            engine.acceptWaveform(samples)
            while (engine.isReady()) engine.decode()

            if (engine.isEndpoint()) {
                val text = engine.getResult()
                if (text.isNotBlank()) processResultText(text)
                engine.reset()
            } else {
                val partial = engine.getResult()
                if (partial.isNotBlank()) {
                    handler.post {
                        binding.tvTranscript.text = "${transcriptBuilder.toString().trim()}\n$partial…"
                        smoothScrollToBottom()
                    }
                }
            }
        }
    }

    private fun amplifyAudio(samples: ShortArray, count: Int): ShortArray {
        return ShortArray(count) { i ->
            (samples[i].toFloat() * AUDIO_GAIN).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun calculateRMS(samples: ShortArray, count: Int): Float {
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble() / Short.MAX_VALUE
            sumSq += s * s
        }
        return kotlin.math.sqrt(sumSq / count).toFloat()
    }

    private fun processResultText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        handler.post {
            transcriptBuilder.append(clean).append(" ")
            updateTranscriptDisplay()
        }
    }

    private fun updateTranscriptDisplay() {
        binding.tvTranscript.text = transcriptBuilder.toString().trim()
        smoothScrollToBottom()
    }

    private fun smoothScrollToBottom() {
        binding.scrollTranscript.post {
            val child = binding.scrollTranscript.getChildAt(0) ?: return@post
            val scrollViewHeight = binding.scrollTranscript.height
            val contentHeight = child.height
            if (contentHeight > scrollViewHeight) {
                // Scroll to 70% of content — keeps latest text in lower third
                val targetScroll = (contentHeight * 0.7).toInt() - scrollViewHeight / 2
                val scrollAmount = targetScroll.coerceAtLeast(0)
                binding.scrollTranscript.smoothScrollTo(0, scrollAmount)
            }
        }
    }

    private fun saveTranscriptToFile() {
        val lang = detectedLanguage ?: selectedLangCode
        val file = TranscriptManager.saveTranscript(this, transcriptBuilder.toString(), lang)
        if (file != null) {
            Toast.makeText(this, "Gespeichert: ${TranscriptManager.getTranscriptDirPath(this)}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Fehler beim Speichern", Toast.LENGTH_SHORT).show()
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording.get()) {
                val elapsed = System.currentTimeMillis() - startTime
                val s = (elapsed / 1000) % 60
                val m = (elapsed / 60000) % 60
                val h = elapsed / 3600000
                binding.tvTimer.text = String.format("%02d:%02d:%02d", h, m, s)
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
