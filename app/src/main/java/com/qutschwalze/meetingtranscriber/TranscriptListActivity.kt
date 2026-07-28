package com.qutschwalze.meetingtranscriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.qutschwalze.meetingtranscriber.databinding.ActivityTranscriptListBinding

class TranscriptListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscriptListBinding
    private lateinit var adapter: TranscriptAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = TranscriptAdapter(
            onOpen = { transcript ->
                val intent = Intent(this, TranscriptDetailActivity::class.java).apply {
                    putExtra("file_path", transcript.file.absolutePath)
                }
                startActivity(intent)
            },
            onCopy = { transcript ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Transkript", transcript.fullText))
                Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
            },
            onShare = { transcript ->
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, transcript.fullText)
                    putExtra(Intent.EXTRA_SUBJECT, "Meeting-Transkript")
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, "Transkript teilen"))
            },
            onDelete = { transcript ->
                AlertDialog.Builder(this)
                    .setTitle("Löschen?")
                    .setMessage("Transkript vom ${transcript.date} wirklich löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        if (TranscriptManager.deleteTranscript(transcript.file)) {
                            Toast.makeText(this, "Gelöscht", Toast.LENGTH_SHORT).show()
                            loadTranscripts()
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        )

        binding.rvTranscripts.layoutManager = LinearLayoutManager(this)
        binding.rvTranscripts.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadTranscripts()
    }

    private fun loadTranscripts() {
        val transcripts = TranscriptManager.loadAllTranscripts(this)
        adapter.submitList(transcripts)

        if (transcripts.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvTranscripts.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvTranscripts.visibility = View.VISIBLE
        }
    }
}
