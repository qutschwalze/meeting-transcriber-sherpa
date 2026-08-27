package com.sherpa.transcript.service

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Hält die CPU während der Aufnahme wach (PARTIAL_WAKE_LOCK ohne Timeout).
 *
 * Warum: Ohne WakeLock kann der Prozessor bei ausgeschaltetem Display in den
 * Schlaf gehen; AudioRecord verliert dann die Buffer-Kontrolle und bricht nach
 * längerer Screen-off-Aufnahme mit ERROR_DEAD_OBJECT ab (stille Segmente /
 * Abbruch mitten in der Session).
 *
 * Bewusst OHNE Timeout: Ein befristeter WakeLock (z. B. 10 Minuten) verfällt
 * mitten in langen Sessions – danach darf die CPU schlafen und AudioRecord
 * landet im ERROR_DEAD_OBJECT-Zustand. Stattdessen wird der Lock IMMER explizit
 * freigegeben: im RecordingService in onDestroy() (deckt stopService UND
 * Service-Kill durch das System ab) bzw. beim Fehlschlag von startForeground.
 *
 * Design: Instance-Klasse ohne DI; der RecordingService hält genau eine Instanz
 * (eine Aufnahme zur Zeit, Lifecycle des Service == Lifecycle der Aufnahme).
 */
class WakeLockManager(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    /** Akquiriert den Lock. Mehrfacher Aufruf ist safe (isHeld-Guard). */
    fun acquire() {
        if (wakeLock?.isHeld == true) {
            Log.d(TAG, "wake lock already held")
            return
        }
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire()
            Log.d(TAG, "wake lock acquired (no timeout)")
        }
    }

    /** Gibt den Lock explizit frei. Mehrfacher Aufruf ist safe. */
    fun release() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        wakeLock = null
        Log.d(TAG, "wake lock released")
    }

    fun isHeld(): Boolean = wakeLock?.isHeld == true

    companion object {
        private const val TAG = "WakeLockManager"
        private const val WAKE_LOCK_TAG = "SherpaTranscript::RecordingWakeLock"
    }
}