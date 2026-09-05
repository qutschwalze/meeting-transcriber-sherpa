package com.sherpa.transcript.ui.settings

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherpa.transcript.BuildConfig
import com.sherpa.transcript.R
import com.sherpa.transcript.data.debug.DebugUploadClient
import com.sherpa.transcript.data.local.AsrLanguageMode
import com.sherpa.transcript.data.local.SettingsStore
import com.sherpa.transcript.data.local.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 5 (0.6.8): Einstellungen – Dark Mode, Schriftgröße (persistent),
 * Debug-Modus und Modell-Info. Alle Werte leben im SettingsStore
 * (SharedPreferences + StateFlow) und werden live übernommen.
 *
 * 0.6.16: Debug-Upload-Server – Server-URL konfigurieren und Dateien
 * direkt an den Host senden (statt adb pull).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsStore: SettingsStore = SettingsStore.current, onNavigateToContacts: () -> Unit = {}) {
    val themeMode by settingsStore.themeMode.collectAsState()
    val fontSize by settingsStore.fontSize.collectAsState()
    val debugMode by settingsStore.debugMode.collectAsState()
    val debugServerUrl by settingsStore.debugServerUrl.collectAsState()
    val debugApiKey by settingsStore.debugApiKey.collectAsState()
    val asrLanguageMode by settingsStore.asrLanguageMode.collectAsState()

    // 0.6.16: Upload-Status
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var uploadResult by remember { mutableStateOf<String?>(null) }
    var serverUrlInput by remember { mutableStateOf(debugServerUrl) }
    var apiKeyInput by remember { mutableStateOf(debugApiKey) }
    var maxFiles by remember { mutableIntStateOf(2) } // 0 = alle, 1-10 = letzte N Sessions

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

            // 0.6.24: ASR-Sprachmodus – Deutsch Standard, Englisch optional
            item {
                Text(
                    text = "Spracherkennung",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Deutsch ist Standard. Englisch lädt ein zweites Modell (~38 MB) und erkennt die Sprache automatisch in den ersten 3 Sekunden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsrLanguageMode.entries.forEach { mode ->
                        FilterChip(
                            selected = asrLanguageMode == mode,
                            onClick = { settingsStore.setAsrLanguageMode(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        AsrLanguageMode.DE_ONLY -> "Nur Deutsch"
                                        AsrLanguageMode.DE_EN_AUTO -> "Deutsch + Englisch"
                                    }
                                )
                            },
                        )
                    }
                }
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

            // ── 0.6.16: Debug-Upload-Server ────────────────────────────
            if (debugMode) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    SectionTitle("Debug-Upload-Server")
                }

                item {
                    Text(
                        text = "Server-URL",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        placeholder = {
                            Text(
                                "http://192.168.x.x:8520",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { settingsStore.setDebugServerUrl(serverUrlInput) },
                        enabled = serverUrlInput.isNotBlank() && serverUrlInput != debugServerUrl,
                    ) {
                        Text("URL speichern")
                    }
                }

                // 0.12.0: API-Key Eingabe (Threat Model T5/T18)
                item {
                    Text(
                        text = "API-Key",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        placeholder = {
                            Text(
                                "X-API-Key aus config.json",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { settingsStore.setDebugApiKey(apiKeyInput) },
                        enabled = apiKeyInput != debugApiKey,
                    ) {
                        Text("Key speichern")
                    }
                }

                // 0.12.0: Debug-Upload-Sektion nur in Debug-Builds (Threat Model T5/T18)
                if (BuildConfig.DEBUG_UPLOAD_ENABLED) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Testaufnahmen zum Server senden",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // 0.6.16: Datei-Limit-Auswahl
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Letzte:",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(50.dp),
                            )
                            FilterChip(
                                selected = maxFiles == 1,
                                onClick = { maxFiles = 1 },
                                label = { Text("1") },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = maxFiles == 2,
                                onClick = { maxFiles = 2 },
                                label = { Text("2") },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = maxFiles == 5,
                                onClick = { maxFiles = 5 },
                                label = { Text("5") },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = maxFiles == 0,
                                onClick = { maxFiles = 0 },
                                label = { Text("Alle") },
                            )
                        }

                        if (isUploading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(20.dp).height(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Upload läuft…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    isUploading = true
                                    uploadResult = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            try {
                                                val base = context.getExternalFilesDir(
                                                    Environment.DIRECTORY_DOWNLOADS
                                                )
                                                android.util.Log.i("DebugUpload", "SettingsScreen: base=${base?.absolutePath} exists=${base?.exists()}")
                                                val dir = File(base, "testaufnahmen")
                                                android.util.Log.i("DebugUpload", "SettingsScreen: dir=${dir.absolutePath} exists=${dir.exists()} canRead=${dir.canRead()}")
                                                // 0.6.16: In-memory filter statt listFiles(filter) (Android-Bug)
                                                val allEntries = dir.listFiles()
                                                val hasMatchingFiles = allEntries?.any { f ->
                                                    f.isFile && f.extension.lowercase() in setOf("wav", "log", "md", "json")
                                                } == true
                                                android.util.Log.i("DebugUpload", "SettingsScreen: entries=${allEntries?.size ?: "null"} hasMatching=$hasMatchingFiles")
                                                if (!dir.exists() || !hasMatchingFiles) {
                                                    return@withContext "Keine Testaufnahmen gefunden (Pfad: ${dir.absolutePath}, Einträge: ${allEntries?.size ?: "null"})"
                                                }
                                                val sessionId = "manual_${System.currentTimeMillis()}"
                                                val uploadResult = DebugUploadClient.uploadDebugBundle(
                                                    dir, sessionId,
                                                    // 0.6.21: maxFiles = Session-Anzahl direkt (kein *2 –
                                                    // die Gruppierung zählt Sessions, nicht Einzeldateien)
                                                    maxFiles = maxFiles,
                                                    skipChunks = true,
                                                )
                                                uploadResult.getOrElse { "Fehler: ${it.message}" }
                                            } catch (e: Exception) {
                                                "Fehler: ${e.message}"
                                            }
                                        }
                                        uploadResult = result
                                        isUploading = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text("Alle Testaufnahmen hochladen")
                            }
                        }

                        uploadResult?.let { result ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.contains("Fehler"))
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // ── Über / Modelle ─────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.settings_title).let { "Über / Modelle" })
            }

            item {
                InfoRow("App-Version", "v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})")
                InfoRow("Sprachmodell (ASR)", "Kroko Zipformer-Transducer (Deutsch, offline)")
                val ctx = LocalContext.current
                InfoRow("Segmentation (Diarization)", assetSize(ctx, "segmentation.onnx"))
                InfoRow("Embedding (Diarization)", assetSize(ctx, "embedding.onnx"))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_github),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/qutschwalze/meeting-transcriber-sherpa/releases/latest")
                        )
                        ctx.startActivity(intent)
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "100% offline – keine Cloud, kein Netzwerk für Transkription und Diarization.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Kontakte (navigierbar) ─────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.contacts_title))
            }

            item {
                val scope = rememberCoroutineScope()
                Text(
                    text = "Sprecher-Profile verwalten (umbenennen, zusammenführen, löschen)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        scope.launch { onNavigateToContacts() }
                    },
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
