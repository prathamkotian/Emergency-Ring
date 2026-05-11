package com.example.emergencyringer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.emergencyringer.service.RingerForegroundService

/**
 * BootReceiver
 *
 * Restarts the foreground service after:
 *   - Device reboot
 *   - App update (MY_PACKAGE_REPLACED)
 *
 * Without this, the service dies on reboot and the app is useless
 * until the user manually opens it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(TAG, "Boot/update received — restarting RingerForegroundService")
            RingerForegroundService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
