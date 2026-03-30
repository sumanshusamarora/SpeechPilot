package com.speechpilot.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.speechpilot.R
import com.speechpilot.MainActivity
import com.speechpilot.SpeechPilotApp

/**
 * Foreground service that keeps the app process alive during an active coaching session.
 *
 * This service is started when a session begins and stopped when the session ends.
 * It does not own the session or audio pipeline — those remain in [SpeechCoachSessionManager]
 * which runs in its own [kotlinx.coroutines.CoroutineScope].
 * Its sole responsibility is to post a persistent notification so Android does not
 * kill the process when the user backgrounds the app mid-session.
 *
 * Start with [ACTION_START] to enter foreground; stop the service normally to remove
 * the notification and release the foreground state.
 *
 * Limitation (Phase 1): the session lives in [SpeechCoachSessionManager]'s coroutine scope
 * and will not survive process death. This service prevents process death during backgrounding
 * but does not recover a session after the process has been killed.
 */
class SpeechCoachingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSessionForeground()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSessionForeground() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SpeechPilotApp.CHANNEL_ID_SESSION)
            .setContentTitle("SpeechPilot")
            .setContentText("Coaching session active")
            .setSmallIcon(R.drawable.ic_speechpilot_logo)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_START = "com.speechpilot.action.SESSION_START"
        const val ACTION_STOP = "com.speechpilot.action.SESSION_STOP"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context): Intent =
            Intent(context, SpeechCoachingService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, SpeechCoachingService::class.java).apply { action = ACTION_STOP }
    }
}
