package com.sherpa.transcript.ui.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sherpa.transcript.ui.live.components.AssignSpeakerSheet
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import android.view.WindowManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sherpa.transcript.BuildConfig
import com.sherpa.transcript.domain.model.RecordingState
import com.sherpa.transcript.domain.model.TranscriptSegment
import com.sherpa.transcript.ui.theme.SpeakerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: LiveViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Phase 7a: Segment, das der Nutzer nach dem Stoppen zuweisen will (null = kein Sheet)
    var assignTarget by remember { mutableStateOf<TranscriptSegment?>(null) }

    // Phase 8: Display-Wach-Toggle → Window-Flag an/aus (kein Stromsparmodus)
    val context = LocalContext.current
    LaunchedEffect(uiState.keepScreenOn) {
        val activity = context as? Activity ?: return@LaunchedEffect
        if (uiState.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Scroll-Trigger: reagiert auf neue Segmente UND Textänderungen im letzten Segment
    val scrollTrigger by remember {
        derivedStateOf {
            if (uiState.autoScrollEnabled && uiState.segments.isNotEmpty()) {
                val last = uiState.segments.last()
                last.segmentId to last.text.length
            } else {
                null to 0
            }
        }
    }

    // Auto-Scroll: scrollt zum letzten Item, wenn Text wächst oder neues Segment
    LaunchedEffect(scrollTrigger, uiState.autoScrollEnabled) {
        if (uiState.autoScrollEnabled && uiState.segments.isNotEmpty()) {
            listState.animateScrollToItem(uiState.segments.lastIndex)
        }
    }

    // Pausiere Auto-Scroll, wenn der User manuell nach oben scrollt
    val isAtBottom by remember(uiState.segments.size) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index >= totalItems - 2
        }
    }

    LaunchedEffect(isAtBottom) {
        if (!isAtBottom && uiState.autoScrollEnabled) {
            viewModel.onUserScroll()
        }
    }

    // Phase 8 (0.7.5): Scroll-Repro-Diagnose für den Zuweisungs-Sprung-Bug.
    // Loggt jede Auto-Scroll-Aktivierung + Position; im TestLog landet die Zeile
    // neben der WAV (Host-Analyse). Aktiv nur im Debug-Modus.
    LaunchedEffect(uiState.autoScrollEnabled, uiState.segments.size, assignTarget) {
        if (!uiState.debugMode || !uiState.autoScrollEnabled) return@LaunchedEffect
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()?.index ?: -1
        android.util.Log.d("SCROLL_REPRO", "auto=on first=$first total=${info.totalItemsCount} segs=${uiState.segments.size} sheet=${assignTarget != null}")
        com.sherpa.transcript.domain.audio.TestLog.log(
            "SCROLL_REPRO auto=on first=$first total=${info.totalItemsCount} segs=${uiState.segments.size} sheet=${assignTarget != null}"
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ─── Transkript-Anzeige (füllt den verfügbaren Platz) ──────
        Box(modifier = Modifier.weight(1f)) {
            if (uiState.segments.isEmpty() && uiState.recordingState is RecordingState.Idle) {
                EmptyState()
            } else if (uiState.segments.isEmpty() && uiState.recordingState is RecordingState.Listening) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Warte auf Sprache…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                ) {
                    items(
                        items = uiState.segments,
                        key = { it.segmentId },
                    ) { segment ->
                        val isLatest by remember(segment.segmentId, uiState.latestSegmentId) {
                            derivedStateOf { segment.segmentId == uiState.latestSegmentId }
                        }
                        val isRemapped = segment.segmentId in uiState.remappedSegmentIds
                        // Phase 7a: Tap auf Segment (nach Stop) öffnet das Zuweisungs-Sheet
                        // Phase 8 (0.7.5): stabiler Farb-Key = Profil-UUID (Fallback Session-ID)
                        val spkNum = segment.speakerId?.removePrefix("speaker_")?.toIntOrNull()
                        val colorKey = if (spkNum != null) {
                            uiState.sessionProfileMap[spkNum] ?: segment.speakerId
                        } else segment.speakerId
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = uiState.recordingState is RecordingState.Idle,
                                ) { assignTarget = segment },
                        ) {
                            TranscriptSegmentItem(
                                segment = segment,
                                fontSize = uiState.fontSize,
                                isLatest = isLatest,
                                debugMode = uiState.debugMode,
                                isRemapped = isRemapped,
                                colorKey = colorKey,
                            )
                        }
                    }
                }
            }
            // Debug-Zähler: raw / assigned / display (nur im DBG-Modus)
            if (uiState.debugMode) {
                Text(
                    text = "raw=${uiState.rawCount}  labeled=${uiState.labeledCount}  display=${uiState.displayCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 8.dp, start = 12.dp),
                )
            }
            // Phase 8: Display-Wach-Toggle (dezenter Mini-Button neben DBG)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(
                    onClick = viewModel::toggleKeepScreenOn,
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = "scr",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.keepScreenOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Debug-Toggle (oben rechts, über der Liste)
                FilledTonalButton(
                    onClick = viewModel::toggleDebugMode,
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = "DBG",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.debugMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ─── Scroll-Zum-Live-Ende Button (über der BottomBar) ─────
        if (!uiState.autoScrollEnabled && uiState.segments.isNotEmpty()) {
            FilledTonalButton(
                onClick = viewModel::onScrollToLatest,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                )
                Text(
                    text = "Zum Live-Ende",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Phase 8 (0.7.4): Post-Processing-Anzeige nach Stop (finaler Lauf + Save)
                    if (uiState.postProcessing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Nachbearbeitung… ${uiState.postProcessingElapsedSec}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 4.dp),
                        )
                    }

                    // ─── Untere Leiste: Status + Slider + Button + Version ─────
                    BottomBar(
            recordingState = uiState.recordingState,
            downloadProgress = uiState.downloadProgress,
            isDownloading = uiState.isDownloading,
            isModelReady = uiState.isModelReady,
            fontSize = uiState.fontSize,
            onFontSizeChanged = viewModel::onFontSizeChanged,
            onStart = viewModel::startRecording,
            onStop = viewModel::stopRecording,
        )

        // ─── Phase 7a: Sprecher-Zuweisung (nur nach dem Stoppen) ──────
        assignTarget?.let { seg ->
            ModalBottomSheet(
                onDismissRequest = { assignTarget = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                AssignSpeakerSheet(
                    segment = seg,
                    profiles = uiState.speakerProfiles,
                    onAssign = { sid, pid, name ->
                        viewModel.assignSpeakerToSegment(sid, pid, name)
                        assignTarget = null
                    },
                    onDismiss = { assignTarget = null },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// BottomBar – Status, Schriftgrößenregler, Record-Button, Version
// ─────────────────────────────────────────────────────────────────

@Composable
private fun BottomBar(
    recordingState: RecordingState,
    downloadProgress: Float,
    isDownloading: Boolean,
    isModelReady: Boolean,
    fontSize: Float,
    onFontSizeChanged: (Float) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Download-Fortschritt (nur sichtbar während Download)
        if (isDownloading) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Schriftgrößenregler
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "A",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = fontSize,
                onValueChange = onFontSizeChanged,
                valueRange = 12f..28f,
                steps = 15,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Text(
                text = "A",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Status + Button: Button immer rechts fixiert
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status-Text (links, flexibel)
            val statusText = when {
                isDownloading -> "Lade Modell…"
                !isModelReady && recordingState is RecordingState.Idle -> "Tippen zum Starten"
                recordingState is RecordingState.Idle -> "Bereit"
                recordingState is RecordingState.Initializing -> "Initialisiere…"
                recordingState is RecordingState.Listening -> "Hört zu…"
                recordingState is RecordingState.Processing -> "Verarbeitet…"
                recordingState is RecordingState.Error -> "Fehler"
                else -> ""
            }
            val statusColor by animateColorAsState(
                targetValue = when {
                    recordingState is RecordingState.Listening -> MaterialTheme.colorScheme.primary
                    recordingState is RecordingState.Processing -> MaterialTheme.colorScheme.tertiary
                    recordingState is RecordingState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "statusColor",
            )

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = statusColor,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Record/Stop Button (rechts fixiert)
            val isRecording = recordingState is RecordingState.Listening ||
                    recordingState is RecordingState.Processing ||
                    recordingState is RecordingState.Initializing

            if (isRecording) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stopp",
                        modifier = Modifier.size(32.dp),
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onStart,
                    enabled = !isDownloading,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                            )
                        } else {
                            // 0.6.9: Mikrofon deutlich größer (36dp statt 24dp) –
                            // bei 56dp-Button wirkte 24dp wie ein Punkt
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Aufnahme starten",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }
        }

        // Version
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// TranscriptSegmentItem
// ─────────────────────────────────────────────────────────────────

@Composable
private fun TranscriptSegmentItem(
    segment: TranscriptSegment,
    fontSize: Float,
    isLatest: Boolean,
    modifier: Modifier = Modifier,
    debugMode: Boolean = false,
    isRemapped: Boolean = false,
    /** Phase 8 (0.7.5): stabiler Farb-Key (Profil-UUID oder Session-ID). */
    colorKey: String? = null,
) {
    val backgroundColor = when {
        isRemapped -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        isLatest && segment.isNew -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    val textColor = if (segment.isFinal) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }

    val fontStyle = if (!segment.isFinal) FontStyle.Italic else FontStyle.Normal

    // Sprecherfarbe – Phase 8 (0.7.5): Farb-Key kommt vom Aufrufer (Profil-UUID bevorzugt).
    val speakerColor = colorKey?.let { key ->
        SpeakerColors[key.hashCode().mod(SpeakerColors.size).absoluteValue]
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Farbbalken für Sprecher
        if (speakerColor != null) {
            Spacer(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .background(speakerColor, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (segment.isNew) {
            Spacer(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            // Sprecherlabel + Remap-Badge
            if (segment.displaySpeakerLabel.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = segment.displaySpeakerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = speakerColor ?: MaterialTheme.colorScheme.primary,
                    )
                    if (isRemapped) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "remapped",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Text(
                text = segment.text,
                fontSize = fontSize.sp,
                fontStyle = fontStyle,
                fontWeight = if (segment.isFinal) FontWeight.Normal else FontWeight.Light,
                color = textColor,
                lineHeight = fontSize.sp * 1.5f,
            )

            // Debug-Zeile: Rohdaten pro sichtbarem Segment
            if (debugMode) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("id=${segment.segmentId.take(8)}")
                        append("  spk=${segment.speakerId ?: "-"}")
                        append("  t=${segment.startTimeMs}-${segment.endTimeMs}")
                        append("  final=${segment.isFinal}")
                        append("  new=${segment.isNew}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private val Int.absoluteValue: Int get() = if (this < 0) -this else this

// ─────────────────────────────────────────────────────────────────
// EmptyState
// ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Noch keine Transkription.\nTippen Sie auf „Aufnahme starten\".",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
