package com.sherpa.transcript.service

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sherpa.transcript.MainActivity

/**
 * 0.11.4: Quick Settings Tile – startet sofort die Transkription.
 *
 * Erscheint in den Quick Settings (Runterwischen -> Bearbeiten -> Sherpa Transcript).
 * Tippen startet die App und beginnt sofort die Aufnahme (ohne extra Tippen).
 *
 * Hinweis (0.11.4): startActivityAndCollapse(Intent) ist auf MIUI (Xiaomi)
 * blockiert – daher PendingIntent-Ansatz, der auf allen OEMs funktioniert.
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
        tile.label = if (recording) "Transkription laeuft..." else "Sherpa Transkription"
        tile.contentDescription = if (recording) "Aufnahme laeuft" else "Sofort transkribieren"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_QUICK_START
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(pendingIntent)
    }

    companion object {
        const val ACTION_QUICK_START = "com.sherpa.transcript.QUICK_START"
    }
}
