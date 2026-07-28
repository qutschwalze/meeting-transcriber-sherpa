package com.qutschwalze.meetingtranscriber

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qutschwalze.meetingtranscriber.databinding.ItemTranscriptBinding
import java.io.File

class TranscriptAdapter(
    private val onOpen: (Transcript) -> Unit,
    private val onCopy: (Transcript) -> Unit,
    private val onShare: (Transcript) -> Unit,
    private val onDelete: (Transcript) -> Unit
) : ListAdapter<Transcript, TranscriptAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTranscriptBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTranscriptBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(transcript: Transcript) {
            binding.tvDate.text = transcript.date
            binding.tvLanguage.text = "Sprache: ${transcript.language}"
            binding.tvPreview.text = transcript.preview

            binding.btnOpen.setOnClickListener { onOpen(transcript) }
            binding.btnCopyItem.setOnClickListener { onCopy(transcript) }
            binding.btnShareItem.setOnClickListener { onShare(transcript) }
            binding.btnDelete.setOnClickListener { onDelete(transcript) }

            // Also open on card click
            binding.root.setOnClickListener { onOpen(transcript) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Transcript>() {
        override fun areItemsTheSame(oldItem: Transcript, newItem: Transcript): Boolean {
            return oldItem.file.absolutePath == newItem.file.absolutePath
        }
        override fun areContentsTheSame(oldItem: Transcript, newItem: Transcript): Boolean {
            return oldItem.file.lastModified() == newItem.file.lastModified()
        }
    }
}
