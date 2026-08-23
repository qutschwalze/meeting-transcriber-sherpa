package com.sherpa.transcript.ui.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Phase 8 (0.7.4): Zentrale Bridge zur laufenden LiveViewModel-Instanz,
 * damit die Android-Notification-Aktionen (Stop / Screen wach) die aktive
 * Aufnahme erreichen können (analog SpeakerProfiles als Singleton).
 */
object RecordingBridge {
    @Volatile
    var current: LiveViewModel? = null
}

/**
 * Phase 8 (0.7.4): Empfängt die Aktions-Buttons der Aufnahme-Benachrichtigung.
 * ACTION_STOP → Aufnahme stoppen; ACTION_KEEP_SCREEN → Screen-Wach-Toggle.
 */
class RecordingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val vm = RecordingBridge.current ?: return
        when (intent.action) {
            ACTION_STOP -> vm.stopRecording()
            ACTION_KEEP_SCREEN -> vm.toggleKeepScreenOn()
        }
    }

    companion object {
        const val ACTION_STOP = "com.sherpa.transcript.action.STOP_RECORDING"
        const val ACTION_KEEP_SCREEN = "com.sherpa.transcript.action.KEEP_SCREEN"
    }
}