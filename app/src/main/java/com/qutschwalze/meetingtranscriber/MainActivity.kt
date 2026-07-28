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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
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
    private lateinit var diarizer: SpeakerDiarizer

    // Sherpa-ONNX engines
    private var engineDE: SherpaEngine? = null
    private var engineEN: SherpaEngine? = null
    private var activeEngine: SherpaEngine? = null
    private var detectedLanguage: String? = null
    private var autoMode = true

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 200
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_GAIN = 2.5f
        private const val DETECTION_WINDOW_MS = 3000L
    }

    private val allLanguages = mapOf(
        "Deutsch" to "de",
        "English" to "en",
        "Français" to "fr",
        "Español" to "es",
        "Italiano" to "it",
        "Русский" to "ru",
        "Português" to "pt",
        "Nederlands" to "nl"
    )

    private var selectedLangCode = "de"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        diarizer = SpeakerDiarizer(sensitivity = 1.2f, minSegmentDurationMs = 800)
        setupLanguageButtons()
        setupOtherButtons()
        checkPermissions()
    }

    private fun setupLanguageButtons() {
        highlightLanguageButton("auto")

        binding.btnLangAuto.setOnClickListener {
            autoMode = true
            selectedLangCode = "de"
            highlightLanguageButton("auto")
        }

        binding.btnLangDe.setOnClickListener {
            autoMode = false
            detectedLanguage = null
            selectedLangCode = "de"
            highlightLanguageButton("de")
        }

        binding.btnLangEn.setOnClickListener {
            autoMode = false
            detectedLanguage = null
            selectedLangCode = "en"
            highlightLanguageButton("en")
        }

        val otherLangs = allLanguages.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, otherLangs)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter
    }

    private fun highlightLanguageButton(active: String) {
        val buttons = mapOf(
            "auto" to binding.btnLangAuto,
            "de" to binding.btnLangDe,
            "en" to binding.btnLangEn
        )

        for ((key, btn) in buttons) {
            if (key == active) {
                btn.strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary)
                )
                btn.setTextColor(ContextCompat.getColor(this, R.color.primary))
            } else {
                btn.strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.border)
                )
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }
    }

    private fun setupOtherButtons() {
        binding.fabRecord.setOnClickListener {
            if (isRecording.get()) {
                stopRecording()
            } else {
                startRecording()
            }
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

        binding.btnViewTranscripts.setOnClickListener {
            startActivity(Intent(this, TranscriptListActivity::class.java))
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Mikrofon-Berechtigung erteilt", Toast.LENGTH_SHORT).show()
            } else {
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
                        // Load both DE and EN models for auto-detection
                        loadModel("de")
                        loadModel("en")
                    } else {
                        loadModel(selectedLangCode)
                    }
                }

                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
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

                if (autoMode) {
                    binding.tvModelInfo.text = "🤖 Auto-Erkennung aktiv…"
                }

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
            if (result == null) {
                throw Exception("Download fehlgeschlagen: $langCode")
            }
        }

        val modelDir = ModelManager.getModelPath(this, langCode)
            ?: throw Exception("Modell-Pfad nicht gefunden: $langCode")
        val files = ModelManager.getModelFiles(this, langCode)
            ?: throw Exception("Modell-Dateien nicht gefunden: $langCode")
        val tokens = File(modelDir, "tokens.txt").absolutePath

        val engine = SherpaEngine(files.first, files.second, files.third, tokens)
        if (!engine.init()) {
            throw Exception("Engine-Init fehlgeschlagen: $langCode")
        }

        when (langCode) {
            "de" -> engineDE = engine
            "en" -> engineEN = engine
        }

        if (activeEngine == null) {
            activeEngine = engine
        }
    }

    private fun stopRecording() {
        isRecording.set(false)
        handler.removeCallbacks(timerRunnable)

        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        audioRecord = null

        // Get final result from active engine
        activeEngine?.let { engine ->
            val finalResult = engine.getResult()
            if (finalResult.isNotBlank()) {
                processResultText(finalResult)
            }
        }

        binding.tvStatus.text = getString(R.string.status_ready)
        binding.fabRecord.text = getString(R.string.btn_start)

        val langDisplay = if (autoMode && detectedLanguage != null) {
            " (${if (detectedLanguage == "de") "🇩🇪 Deutsch" else "🇬🇧 English"} erkannt)"
        } else if (!autoMode) {
            " (${selectedLangCode.uppercase()})"
        } else ""

        binding.tvModelInfo.text = "Modell: $langDisplay"
        binding.progressLevel.progress = 0

        if (transcriptBuilder.isNotBlank()) {
            binding.exportButtons.visibility = View.VISIBLE
            saveTranscriptToFile()
        }

        // Release engines
        engineDE?.release()
        engineEN?.release()
        engineDE = null
        engineEN = null
        activeEngine = null
    }

    private inner class AudioRecognitionTask : Runnable {
        override fun run() {
            val bufferSize = (0.1 * SAMPLE_RATE).toInt() // 100ms chunks
            val buffer = ShortArray(bufferSize)
            val detectionBuffer = mutableListOf<String>()

            while (isRecording.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val currentTimeMs = System.currentTimeMillis() - startTime

                    // Amplify audio
                    val amplified = amplifyAudio(buffer, read)

                    // Audio level feedback
                    var maxLevel = 0
                    for (i in 0 until read) {
                        val level = Math.abs(amplified[i].toInt())
                        if (level > maxLevel) maxLevel = level
                    }
                    val normalizedLevel = (maxLevel * 100 / 32768).coerceIn(0, 100)
                    handler.post { binding.progressLevel.progress = normalizedLevel }

                    // Speaker diarization — only when there's actual speech
                    val rms = calculateRMS(amplified, read)
                    if (rms > 0.02f) {
                        val speakerResult = diarizer.analyze(amplified, read, currentTimeMs)
                        if (speakerResult.changed) {
                            currentSpeaker = speakerResult.speakerId
                            handler.post {
                                if (transcriptBuilder.isNotBlank()) {
                                    transcriptBuilder.append("\n\n")
                                }
                                transcriptBuilder.append("[Sprecher $currentSpeaker]\n")
                                updateTranscriptDisplay()
                            }
                        }
                    }

                    // Convert ShortArray to FloatArray for Sherpa-ONNX
                    val floatSamples = FloatArray(read) { amplified[it] / 32768.0f }

                    // Feed to recognizer
                    if (autoMode && detectedLanguage == null) {
                        autoDetectLanguage(floatSamples, currentTimeMs, detectionBuffer)
                    } else {
                        feedEngine(activeEngine!!, floatSamples)
                    }
                }
            }
        }

        private fun autoDetectLanguage(
            floatSamples: FloatArray,
            currentTimeMs: Long,
            detectionBuffer: MutableList<String>
        ) {
            val deEngine = engineDE ?: return
            val enEngine = engineEN ?: return

            deEngine.acceptWaveform(floatSamples)
            enEngine.acceptWaveform(floatSamples)

            // Decode both
            while (deEngine.isReady()) deEngine.decode()
            while (enEngine.isReady()) enEngine.decode()

            val deText = deEngine.getResult()
            val enText = enEngine.getResult()

            if (deText.isNotBlank()) detectionBuffer.add("de:$deText")
            if (enText.isNotBlank()) detectionBuffer.add("en:$enText")

            if (currentTimeMs > DETECTION_WINDOW_MS && detectionBuffer.size > 5) {
                val deCount = detectionBuffer.count { it.startsWith("de:") && it.length > 4 }
                val enCount = detectionBuffer.count { it.startsWith("en:") && it.length > 4 }

                detectedLanguage = if (deCount > enCount) "de" else "en"
                activeEngine = if (detectedLanguage == "de") deEngine else enEngine
                val label = if (detectedLanguage == "de") "🇩🇪 Deutsch" else "🇬🇧 English"

                handler.post {
                    binding.tvModelInfo.text = "🤖 Erkannt: $label"
                    Toast.makeText(this@MainActivity, "Sprache erkannt: $label", Toast.LENGTH_SHORT).show()
                }

                // Process existing result from winning engine
                val existingResult = activeEngine?.getResult()
                if (!existingResult.isNullOrBlank()) {
                    processResultText(existingResult)
                }
            }

            // Show partial during detection
            if (currentTimeMs < DETECTION_WINDOW_MS) {
                val partialText = deEngine.getResult()
                if (partialText.isNotBlank()) {
                    handler.post {
                        val currentText = transcriptBuilder.toString().trim()
                        binding.tvTranscript.text = "$currentText\n[text…] $partialText…"
                        smoothScrollToBottom()
                    }
                }
            }
        }

        private fun feedEngine(engine: SherpaEngine, floatSamples: FloatArray) {
            engine.acceptWaveform(floatSamples)
            while (engine.isReady()) {
                engine.decode()
            }

            if (engine.isEndpoint()) {
                val text = engine.getResult()
                if (text.isNotBlank()) {
                    processResultText(text)
                }
                engine.reset()
            } else {
                // Show partial result
                val partial = engine.getResult()
                if (partial.isNotBlank()) {
                    handler.post {
                        val currentText = transcriptBuilder.toString().trim()
                        binding.tvTranscript.text = "$currentText\n$partial…"
                        smoothScrollToBottom()
                    }
                }
            }
        }
    }

    private fun amplifyAudio(samples: ShortArray, count: Int): ShortArray {
        val amplified = ShortArray(count)
        for (i in 0 until count) {
            val amplifiedValue = (samples[i].toFloat() * AUDIO_GAIN).toInt()
            amplified[i] = amplifiedValue.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return amplified
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
        val cleanText = text.trim()
        if (cleanText.isBlank()) return

        handler.post {
            transcriptBuilder.append(cleanText).append(" ")
            updateTranscriptDisplay()
        }
    }

    private fun updateTranscriptDisplay() {
        binding.tvTranscript.text = transcriptBuilder.toString().trim()
        smoothScrollToBottom()
    }

    private fun smoothScrollToBottom() {
        binding.scrollTranscript.post {
            val child = binding.scrollTranscript.getChildAt(0)
            if (child != null) {
                val scrollAmount = child.height - binding.scrollTranscript.height
                if (scrollAmount > 0) {
                    binding.scrollTranscript.scrollTo(0, scrollAmount)
                }
            }
        }
    }

    private fun saveTranscriptToFile() {
        val lang = detectedLanguage ?: selectedLangCode
        val file = TranscriptManager.saveTranscript(this, transcriptBuilder.toString(), lang)
        if (file != null) {
            val dir = TranscriptManager.getTranscriptDirPath(this)
            Toast.makeText(this, "Gespeichert: $dir", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Fehler beim Speichern", Toast.LENGTH_SHORT).show()
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording.get()) {
                val elapsed = System.currentTimeMillis() - startTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / (1000 * 60)) % 60
                val hours = elapsed / (1000 * 60 * 60)
                binding.tvTimer.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording.set(false)
        handler.removeCallbacks(timerRunnable)
        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        engineDE?.release()
        engineEN?.release()
    }
}
