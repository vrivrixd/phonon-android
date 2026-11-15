package io.github.vrivrixd.phonon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Marks the engine's process as a foreground service while it is in use. Without this Android can
 * treat the engine as a background process and hold it to the slow cores (cpuset /restricted): on
 * a Galaxy A53 with the screen locked speech was made at 0.9x real time, so it stuttered, against
 * about 3x as a foreground service.
 *
 * It stops itself after a while unused, so the process goes back to the background when idle. If
 * Android refuses it (it may, while the app is not on screen), it is asked again on the next use,
 * and speech still works meanwhile, only slower.
 */
class ForegroundKeeper : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastUse > IDLE_MS) stopSelf() else handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // On every start, not only the first: Android expects it after each startForegroundService.
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(ID, notification)
            }
            foreground = true
            handler.removeCallbacks(check)
            handler.postDelayed(check, CHECK_MS)
        } catch (e: Exception) {
            Log.w(TAG, "could not become a foreground service", e)
            foreground = false
            stopSelf()
        }
        // Never restarted by the system on its own: from the background that would be refused.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        foreground = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        // No notification permission is requested, so from Android 13 on nothing shows. On older
        // versions this quiet notification appears while the engine is in use.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.speech_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_engine)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.speech_notification))
            .build()
    }

    companion object {
        private const val TAG = "PhononKeeper"
        private const val CHANNEL = "speech"
        private const val ID = 1
        private const val CHECK_MS = 5_000L
        private const val IDLE_MS = 120_000L
        private const val RETRY_MS = 2_000L

        @Volatile private var foreground = false
        @Volatile private var lastUse = 0L
        @Volatile private var lastAsk = 0L

        /** Whether the engine is a foreground service right now. */
        val active: Boolean get() = foreground

        /** Call whenever the engine is used. */
        fun keep(context: Context) {
            val now = SystemClock.elapsedRealtime()
            lastUse = now
            if (foreground || now - lastAsk < RETRY_MS) return
            lastAsk = now
            try {
                context.startForegroundService(Intent(context, ForegroundKeeper::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start", e)
            }
        }
    }
}
