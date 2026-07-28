package com.qutschwalze.meetingtranscriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.qutschwalze.meetingtranscriber.databinding.ActivityTranscriptDetailBinding
import java.io.File

class TranscriptDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscriptDetailBinding
    private var transcriptFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val filePath = intent.getStringExtra("file_path")
        if (filePath == null) {
            finish()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "Datei nicht gefunden", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        transcriptFile = file
        val content = file.readText()
        val lines = content.lines()

        // Parse metadata
        val date = lines.find { it.startsWith("Datum:") }
            ?.removePrefix("Datum:")?.trim() ?: ""
        val lang = lines.find { it.startsWith("Sprache:") }
            ?.removePrefix("Sprache:")?.trim() ?: ""

        binding.toolbar.title = file.name
        binding.tvDetailDate.text = date
        binding.tvDetailLang.text = "Sprache: $lang"

        // Get text after separator
        val separatorIdx = lines.indexOfFirst { it.startsWith("===") }
        val textStart = if (separatorIdx >= 0 && separatorIdx + 2 < lines.size) {
            separatorIdx + 2
        } else {
            0
        }
        val fullText = lines.drop(textStart).joinToString("\n").trim()
        binding.tvFullTranscript.text = fullText

        // Actions
        binding.btnDetailCopy.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Transkript", content))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnDetailShare.setOnClickListener {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_SUBJECT, "Meeting-Transkript: ${file.name}")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Transkript teilen"))
        }

        binding.btnDetailDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Löschen?")
                .setMessage("Dieses Transkript wirklich löschen?")
                .setPositiveButton("Löschen") { _, _ ->
                    if (file.delete()) {
                        Toast.makeText(this, "Gelöscht", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }
}
