package com.sherpa.transcript.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sherpa.transcript.MainActivity

/**
 * 0.11.2: Quick Settings Tile – startet sofort die Transkription.
 *
 * Erscheint in den Quick Settings (Runterwischen → Bearbeiten → Sherpa Transcript).
 * Tippen startet die App und beginnt sofort die Aufnahme (ohne extra Tippen).
 */
class QuickStartTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        qsTile?.label = "Sherpa Transkription"
        qsTile?.contentDescription = "Sofort transkribieren"
        qsTile?.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val recording = RecordingService.isRunning
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (recording) "Transkription läuft…" else "Sherpa Transkription"
        tile.contentDescription = if (recording) "Aufnahme läuft" else "Sofort transkribieren"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_QUICK_START
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapse(intent)
    }

    companion object {
        const val ACTION_QUICK_START = "com.sherpa.transcript.QUICK_START"
    }
}
