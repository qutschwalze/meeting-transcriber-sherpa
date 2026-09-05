package com.sherpa.transcript.service

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sherpa.transcript.MainActivity
import com.sherpa.transcript.SherpaTranscriptApp

/**
 * 0.12.0: Quick Settings Tile – startet sofort die Transkription.
 *
 * Erscheint in den Quick Settings (Runterwischen → Bearbeiten → Sherpa Transcript).
 * Tippen startet die App und beginnt sofort die Aufnahme.
 */
class QuickStartTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        startRecordingFromTile()
    }

    private fun startRecordingFromTile() {
        // App-Intent mit Quick-Start-Flag senden
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_QUICK_START
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapse(intent)
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.label = "Sherpa Transkription"
        tile.contentDescription = "Sofort transkribieren"
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    companion object {
        const val ACTION_QUICK_START = "com.sherpa.transcript.QUICK_START"
    }
}
