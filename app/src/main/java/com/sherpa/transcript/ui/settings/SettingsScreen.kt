package com.sherpa.transcript.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherpa.transcript.BuildConfig
import com.sherpa.transcript.data.local.SettingsStore
import com.sherpa.transcript.data.local.ThemeMode

/**
 * Phase 5 (0.6.8): Einstellungen – Dark Mode, Schriftgröße (persistent),
 * Debug-Modus und Modell-Info. Alle Werte leben im SettingsStore
 * (SharedPreferences + StateFlow) und werden live übernommen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsStore: SettingsStore = SettingsStore.current) {
    val themeMode by settingsStore.themeMode.collectAsState()
    val fontSize by settingsStore.fontSize.collectAsState()
    val debugMode by settingsStore.debugMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // ── Darstellung ────────────────────────────────────────────
            item { SectionTitle("Darstellung") }

            item {
                Text(
                    text = "Dark Mode",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { settingsStore.setThemeMode(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.LIGHT -> "Hell"
                                        ThemeMode.DARK -> "Dunkel"
                                    }
                                )
                            },
                        )
                    }
                }
            }

            // ── Transkript ─────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle("Transkript")
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Schriftgröße",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${fontSize.toInt()} sp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = fontSize,
                    onValueChange = settingsStore::setFontSize,
                    valueRange = 12f..28f,
                    steps = 15,
                )
                Text(
                    text = "Vorschau: Der Erfahrungsweg ist aus. Und das ist noch gar nicht abzusehen…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = fontSize.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // ── Diagnose ───────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle("Diagnose")
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Debug-Modus",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Speichert Testaufnahmen (WAV) + Diagnose-Log für die Host-Analyse",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = debugMode,
                        onCheckedChange = settingsStore::setDebugMode,
                    )
                }
            }

            // ── Über / Modell-Info ─────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle("Über / Modelle")
            }

            item {
                InfoRow("App-Version", "v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})")
                InfoRow("Sprachmodell (ASR)", "Kroko Zipformer-Transducer (Deutsch, offline)")
                val context = LocalContext.current
                InfoRow("Segmentation (Diarization)", assetSize(context, "segmentation.onnx"))
                InfoRow("Embedding (Diarization)", assetSize(context, "embedding.onnx"))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "100% offline – keine Cloud, kein Netzwerk für Transkription und Diarization.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(180.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Dateigröße eines Assets (z. B. "segmentation.onnx") lesbar formatieren. */
@Composable
private fun assetSize(context: android.content.Context, assetName: String): String {
    return try {
        // openFd schlägt bei komprimierten Assets fehl (IOException) – open() mit
        // available() funktioniert für komprimierte und unkomprimierte Assets
        // (Geräte-Befund 0.6.9: "nicht gefunden" trotz vorhandener Assets).
        val size = context.assets.open(assetName).use { it.available().toLong() }
        val kb = size / 1024
        if (kb >= 1024) "%.1f MB".format(kb / 1024.0) else "$kb KiB"
    } catch (_: Exception) {
        "nicht gefunden"
    }
}
