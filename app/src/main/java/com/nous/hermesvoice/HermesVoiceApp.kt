package com.nous.hermesvoice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class HermesVoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hermes Voice assistant foreground service"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "hermes_voice_service"
    }
}