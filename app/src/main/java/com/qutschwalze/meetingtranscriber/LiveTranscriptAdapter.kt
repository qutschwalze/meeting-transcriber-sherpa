package com.qutschwalze.meetingtranscriber

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class TranscriptEntry(
    val speakerId: Int,
    val text: String,
    val startMs: Long = 0,
    val endMs: Long = 0
)

class LiveTranscriptAdapter : RecyclerView.Adapter<LiveTranscriptAdapter.ViewHolder>() {

    private val entries = mutableListOf<TranscriptEntry>()
    private var textSize = 15f

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSpeaker: TextView = view.findViewById(R.id.tvSpeaker)
        val tvText: TextView = view.findViewById(R.id.tvText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_transcript, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvSpeaker.text = "Sprecher ${entry.speakerId}"
        holder.tvText.text = entry.text
        holder.tvText.textSize = textSize
    }

    override fun getItemCount() = entries.size

    fun addEntry(entry: TranscriptEntry) {
        entries.add(entry)
        notifyItemInserted(entries.size - 1)
    }

    fun updateLastText(text: String) {
        if (entries.isNotEmpty()) {
            entries[entries.size - 1] = entries[entries.size - 1].copy(text = text)
            notifyItemChanged(entries.size - 1)
        }
    }

    fun setTextSize(size: Float) {
        textSize = size
        notifyDataSetChanged()
    }

    fun getFullTranscript(): String {
        return entries.joinToString("\n\n") { "[Sprecher ${it.speakerId}]\n${it.text}" }
    }

    fun clear() {
        entries.clear()
        notifyDataSetChanged()
    }
}
