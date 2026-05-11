package com.example.emergencyringer.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.emergencyringer.EmergencyRingerApp
import com.example.emergencyringer.R
import com.example.emergencyringer.ui.MainActivity

/**
 * RingerForegroundService
 *
 * A persistent foreground service that Android won't kill.
 * Its only job: stay alive and show a notification so Android
 * keeps this process running — which keeps CallStateReceiver active.
 *
 * The actual call logic lives in CallStateReceiver.
 * This service is the anchor that keeps everything alive.
 */
class RingerForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // START_STICKY: if killed, restart with null intent — we don't need the intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Service destroyed — will be restarted by system (START_STICKY)")
    }

    private fun buildNotification() = NotificationCompat.Builder(this, EmergencyRingerApp.CHANNEL_SERVICE)
        .setContentTitle("Emergency Ringer Active")
        .setContentText("Monitoring calls from priority contacts")
        .setSmallIcon(R.drawable.ic_shield)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val TAG = "RingerForegroundService"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, RingerForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RingerForegroundService::class.java))
        }
    }
}
