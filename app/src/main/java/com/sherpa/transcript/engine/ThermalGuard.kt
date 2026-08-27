package com.sherpa.transcript.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Thermal-Status des Geräts (API 29+, [PowerManager.currentThermalStatus]).
 *
 * Ab THERMAL_STATUS_MODERATE (2) drosselt das System die CPU – genau dann, wenn
 * die rolling Diarization ihre teuren ONNX-Inferenzen fährt. Der Aufnahme-Loop
 * im LiveViewModel überspringt in diesem Zustand Chunk-Ticks (Cooling-Gap), bis
 * das Gerät wieder im Budget ist. Audio geht dabei NICHT verloren: Der
 * ChunkedAudioBuffer puffert, und processFinalChunk() drainiert beim Stop den
 * kompletten Rest (kein Segmentverlust, nur temporär höhere Latenz).
 *
 * Auf APIs < 29 (ohne Thermal-API) meldet der Guard nie Überhitzung.
 */
class ThermalGuard(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** true, sobald das Gerät moderat überhitzt ist oder drosselt. */
    fun isOverheating(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val status = powerManager.currentThermalStatus
        val hot = status >= PowerManager.THERMAL_STATUS_MODERATE
        if (hot) {
            Log.w(TAG, "thermal status=$status – Inferenz-Ticks werden pausiert")
        }
        return hot
    }

    companion object {
        private const val TAG = "ThermalGuard"
    }
}