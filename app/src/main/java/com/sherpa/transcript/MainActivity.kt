package com.sherpa.transcript

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import com.sherpa.transcript.R
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
import com.sherpa.transcript.service.QuickStartTileService
import com.sherpa.transcript.ui.navigation.AppNavigation
import com.sherpa.transcript.ui.theme.SherpaTranscriptTheme

class MainActivity : ComponentActivity() {

    /**
     * 0.11.5: Kompositorischer State fuer Quick-Start – wird vom Tile gesetzt
     * und vom LaunchedEffect beobachtet (auch bei onNewIntent).
     */
    private var quickStartPending by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
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
                            android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    )

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        permissionGranted = granted
                    }

                    val liveViewModel: com.sherpa.transcript.ui.live.LiveViewModel = viewModel()

                    LaunchedEffect(Unit) {
                        if (!permissionGranted) {
                            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }

                    // Quick-Start: wartet auf Permission, startet Aufnahme
                    LaunchedEffect(permissionGranted, quickStartPending) {
                        if (permissionGranted && quickStartPending) {
                            quickStartPending = false
                            try {
                                liveViewModel.startRecording()
                            } catch (t: Throwable) {
                                Log.e(TAG, "QuickStart fehlgeschlagen: ${t.message}", t)
                            }
                        }
                    }

                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val wantsQuickStart = intent?.action == QuickStartTileService.ACTION_QUICK_START
        if (wantsQuickStart) {
            quickStartPending = true
        }

        val sharedUri = extractSharedAudioUri(intent)
        if (sharedUri != null) {
            com.sherpa.transcript.ui.live.PendingImport.put(
                sharedUri, extractSharedAudioName(intent)
            )
        }
    }

    private fun extractSharedAudioUri(intent: Intent?): android.net.Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val type = intent.type ?: return null
        if (!type.startsWith("audio/")) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
    }

    private fun extractSharedAudioName(intent: Intent?): String {
        @Suppress("DEPRECATION")
        val uri = intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        var name = "Sprachnachricht"
        uri?.let {
            contentResolver.query(
                it, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { n -> name = n }
                }
            }
        }
        return name
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
