package com.sherpa.transcript.ui.detail

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sherpa.transcript.data.local.SegmentEntity
import com.sherpa.transcript.data.local.TranscriptEntity
import com.sherpa.transcript.domain.export.TranscriptExporter
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptDetailScreen(
    transcriptId: String,
    onBack: () -> Unit,
    viewModel: TranscriptDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(transcriptId) {
        viewModel.loadTranscript(transcriptId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.transcript?.title ?: "Transkript",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück",
                        )
                    }
                },
                actions = {
                    // Phase 4 (0.6.2): Export als TXT/Markdown/JSON via ShareSheet
                    val context = LocalContext.current
                    var exportMenuOpen by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { exportMenuOpen = true },
                        enabled = uiState.transcript != null && uiState.segments.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Exportieren")
                    }
                    DropdownMenu(
                        expanded = exportMenuOpen,
                        onDismissRequest = { exportMenuOpen = false },
                    ) {
                        ExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.label) },
                                onClick = {
                                    exportMenuOpen = false
                                    exportTranscript(context, uiState.transcript, uiState.segments, format)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Suchleiste
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                placeholder = { Text("In diesem Transkript suchen…") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Metadaten
            uiState.transcript?.let { t ->
                Text(
                    text = "${t.title} · ${formatDuration(t.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = t.transcriptId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Segmente
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Lade…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val displaySegments = uiState.segments

                if (displaySegments.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "Keine Treffer"
                                   else "Keine Segmente",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn {
                        items(
                            items = displaySegments,
                            key = { it.segmentId },
                        ) { segment ->
                            SegmentItem(segment)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentItem(segment: SegmentEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        // Sprecherlabel + Timestamp
        Column(
            modifier = Modifier.width(80.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = segment.speakerLabel ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = formatTimestamp(segment.startTimeMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text + Diagnosezeile
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("id=${segment.segmentId.take(8)}")
                    append("  spk=${segment.speakerId ?: "-"}")
                    append("  t=${segment.startTimeMs}-${segment.endTimeMs}")
                    append("  dur=${segment.endTimeMs - segment.startTimeMs}ms")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "${min}:${sec.toString().padStart(2, '0')} Min"
}

private fun formatTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "${min}:${sec.toString().padStart(2, '0')}"
}

// ─── Phase 4 (0.6.2): Export ──────────────────────────────────────────

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    TXT("Text (.txt)", "txt", "text/plain"),
    MARKDOWN("Markdown (.md)", "md", "text/markdown"),
    JSON("JSON (.json)", "json", "application/json"),
}

private fun exportTranscript(
    context: Context,
    transcript: TranscriptEntity?,
    segments: List<SegmentEntity>,
    format: ExportFormat,
) {
    if (transcript == null) return
    val content = when (format) {
        ExportFormat.TXT -> TranscriptExporter.formatTxt(transcript, segments)
        ExportFormat.MARKDOWN -> TranscriptExporter.formatMarkdown(transcript, segments)
        ExportFormat.JSON -> TranscriptExporter.formatJson(transcript, segments)
    }
    shareFile(context, "transcript_${transcript.transcriptId.take(8)}.${format.extension}", content, format.mime)
}

private fun shareFile(context: Context, fileName: String, content: String, mime: String) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Transkript exportieren"))
    } catch (t: Throwable) {
        Log.e("TranscriptDetail", "Export fehlgeschlagen: ${t.message}")
    }
}
