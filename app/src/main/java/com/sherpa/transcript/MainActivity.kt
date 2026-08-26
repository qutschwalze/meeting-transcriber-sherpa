package com.sherpa.transcript

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.sherpa.transcript.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sherpa.transcript.data.local.SettingsStore
import com.sherpa.transcript.data.local.ThemeMode
import com.sherpa.transcript.ui.navigation.AppNavigation
import com.sherpa.transcript.ui.theme.SherpaTranscriptTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Phase 9 (0.9.0): Geteilte Audiodatei? → an das LiveViewModel weiterreichen.
        // Der Import läuft im Live-Screen (derselbe ViewModel über viewModel()-Scope);
        // wir cachen den Intent bis zur ersten Komposition von AppNavigation.
        val sharedUri = extractSharedAudioUri(intent)

        setContent {
            // Phase 5 (0.6.8): Dark Mode aus den Einstellungen (System/hell/dunkel)
            val themeMode by SettingsStore.current.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SherpaTranscriptTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var permissionGranted by mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        permissionGranted = granted
                    }

                    // Phase 9: LiveViewModel HIER holen (Composable-Kontext), nicht im LaunchedEffect
                    val liveViewModel: com.sherpa.transcript.ui.live.LiveViewModel = viewModel()

                    LaunchedEffect(Unit) {
                        if (!permissionGranted) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        // Phase 9c: Share-Intent über PendingImport-Bridge an den
                        // Live-Screen weiterreichen (dort läuft der Import auf der
                        // sichtbaren nav-scoped Instanz – Fix für leeren Live-Screen).
                        if (sharedUri != null) {
                            com.sherpa.transcript.ui.live.PendingImport.put(
                                sharedUri, extractSharedAudioName(intent)
                            )
                        }
                    }

                    AppNavigation()
                }
            }
        }
    }

    /** Phase 9: Extrahiert die Audio-URI aus einem SEND-Share-Intent (oder null). */
    private fun extractSharedAudioUri(intent: android.content.Intent?): android.net.Uri? {
        if (intent?.action != android.content.Intent.ACTION_SEND) return null
        val type = intent.type ?: return null
        if (!type.startsWith("audio/")) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri
    }

    /** Phase 9: Anzeigename der geteilten Datei (Fallback "Sprachnachricht"). */
    private fun extractSharedAudioName(intent: android.content.Intent?): String {
        @Suppress("DEPRECATION")
        val uri = intent?.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        var name = "Sprachnachricht"
        uri?.let {
            contentResolver.query(it, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { n -> name = n }
                }
            }
        }
        return name
    }
}
