package com.qutschwalze.meetingtranscriber

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Transcript(
    val file: File,
    val date: String,
    val language: String,
    val preview: String,
    val fullText: String
)

object TranscriptManager {

    private fun getTranscriptDir(context: Context): File {
        // Save to public Downloads folder for easy access
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MeetingTranscripts"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getTranscriptDirPath(context: Context): String {
        return getTranscriptDir(context).absolutePath
    }

    fun saveTranscript(context: Context, text: String, langCode: String): File? {
        if (text.isBlank()) return null

        val dir = getTranscriptDir(context)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMAN).format(Date())
        val filename = "meeting-$timestamp.txt"
        val file = File(dir, filename)

        return try {
            file.writeText(buildString {
                append("Meeting-Transkript\n")
                append("Datum: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN).format(Date())}\n")
                append("Sprache: $langCode\n")
                append("=".repeat(40))
                append("\n\n")
                append(text.trim())
            })
            file
        } catch (e: Exception) {
            null
        }
    }

    fun loadAllTranscripts(context: Context): List<Transcript> {
        val dir = getTranscriptDir(context)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile && it.extension == "txt" && it.name.startsWith("meeting-") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val content = file.readText()
                val lines = content.lines()

                // Extract date from first lines or filename
                val date = lines.find { it.startsWith("Datum:") }
                    ?.removePrefix("Datum:")?.trim()
                    ?: file.nameWithoutExtension

                // Extract language
                val lang = lines.find { it.startsWith("Sprache:") }
                    ?.removePrefix("Sprache:")?.trim()
                    ?: "?"

                // Get preview (text after the separator line)
                val separatorIdx = lines.indexOfFirst { it.startsWith("===") }
                val textStart = if (separatorIdx >= 0 && separatorIdx + 2 < lines.size) {
                    separatorIdx + 2
                } else {
                    0
                }
                val preview = lines.drop(textStart).joinToString(" ").take(200)

                Transcript(
                    file = file,
                    date = date,
                    language = lang,
                    preview = preview.ifBlank { "(leer)" },
                    fullText = content
                )
            } ?: emptyList()
    }

    fun deleteTranscript(file: File): Boolean {
        return file.delete()
    }
}
