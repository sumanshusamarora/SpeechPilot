package com.speechpilot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SpeechPilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_ID_SESSION,
            "Active Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while a coaching session is running"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID_SESSION = "speechpilot_session"
    }
}
