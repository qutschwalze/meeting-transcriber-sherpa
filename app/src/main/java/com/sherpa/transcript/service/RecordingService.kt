package com.sherpa.transcript.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.sherpa.transcript.MainActivity
import com.sherpa.transcript.R

/**
 * Foreground-Service, der die App während der Aufnahme im Vordergrund hält.
 *
 * Warum: Ohne einen aktiven Foreground-Service mit Mikrofon-Typ kann Android/MIUI
 * den AppOp für RECORD_AUDIO entziehen ("App op 27 missing, silencing record") und
 * die Aufnahme stummschalten, sobald die App in den Hintergrund wandert oder vom
 * System unter Druck gesetzt wird. Der Service signalisiert dem System eine aktive
 * Mikrofon-Nutzung und macht das Recording für den User sichtbar (Notification).
 *
 * Der Service nimmt selbst KEIN Audio auf – das macht der AudioCaptureManager im
 * LiveViewModel. Er hält nur den Prozess am Leben und den Mikrofon-AppOp aktiv.
 */
class RecordingService : Service() {

    private val wakeLockManager by lazy { WakeLockManager(this) }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                val intent = Intent(context, RecordingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed: ${t.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RecordingService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "RecordingService onStartCommand")
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: Mikrofon-Typ explizit angeben (Pflicht für FOREGROUND_SERVICE_MICROPHONE)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "RecordingService foreground (microphone type)")
            // 0.10.6: CPU-WakeLock während der Aufnahme (Screen-off-Aufnahmen
            // überstehen sonst Doze; AudioRecord → ERROR_DEAD_OBJECT)
            wakeLockManager.acquire()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.message}")
            wakeLockManager.release()
            stopSelf()
        }
        return START_NOT_STICKY // nur vom ViewModel gesteuert, kein Auto-Restart
    }

    override fun onDestroy() {
        Log.d(TAG, "RecordingService destroyed")
        // 0.10.6: WakeLock IMMER explizit freigeben – onDestroy deckt stopService
        // UND Service-Kill durchs System ab (kein Timeout-Fallback nötig)
        wakeLockManager.release()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Transkription läuft")
            .setContentText("Mikrofon aktiv – Aufnahme wird transkribiert")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aufnahme-Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an, dass die Transkription läuft"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
