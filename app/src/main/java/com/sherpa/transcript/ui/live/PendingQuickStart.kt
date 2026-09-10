package com.sherpa.transcript.ui.live

/**
 * 0.12.9: PendingQuickStart – gleiches Muster wie PendingImport (0.9.3).
 *
 * Bug: MainActivity erzeugte ein Activity-scoped LiveViewModel und rief dort
 * startRecording() auf. LiveScreen nutzt aber ein NAV-scoped ViewModel
 * (eigener ViewModelStore pro NavBackStackEntry) -> andere Instanz ->
 * Recording lief unsichtbar (Notification ja, UI = Idle). Tile zeigte ACTIVE,
 * Screen zeigte Idle – 1.9-GB-WAV bei vergessenem Stop.
 * Fix: MainActivity legt nur ein Flag ab; LiveScreen konsumiert es auf SEINER
 * Instanz und startet dort die Aufnahme -> UI zeigt sofort Listening.
 */
object PendingQuickStart {
    @Volatile private var pending: Boolean = false
    @Synchronized fun put() { pending = true }
    @Synchronized fun consume(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
    @Synchronized fun isPending(): Boolean = pending
}
