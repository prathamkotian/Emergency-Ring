package com.example.emergencyringer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.room.Room
import com.example.emergencyringer.data.AppDatabase
import com.example.emergencyringer.service.RingerForegroundService

class EmergencyRingerApp : Application() {

    // Lazy singleton DB — one instance for the whole app lifetime
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
        .fallbackToDestructiveMigration() // replace with proper migrations in v2
        .build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Start the foreground service on app launch
        RingerForegroundService.start(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Channel 1: Persistent service notification (low priority — not intrusive)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Emergency Ringer Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Emergency Ringer is monitoring calls"
                setShowBadge(false)
            }

            // Channel 2: Override alert notification
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                "Override Triggered",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shown when a priority call overrides silent mode"
            }

            nm.createNotificationChannels(listOf(serviceChannel, alertChannel))
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "emergency_ringer_service"
        const val CHANNEL_ALERT   = "emergency_ringer_alert"
    }
}
