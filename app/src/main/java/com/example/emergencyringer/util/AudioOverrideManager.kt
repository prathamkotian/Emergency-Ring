package com.example.emergencyringer.util

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log
import androidx.core.content.getSystemService

/**
 * AudioOverrideManager
 *
 * Responsibilities:
 *  1. Save the phone's current ringer state BEFORE touching anything
 *  2. Override to full-volume normal ring mode
 *  3. Override DND if the OS allows it
 *  4. Restore everything perfectly on call end
 *
 * State is persisted to SharedPreferences so a service crash + restart
 * can still restore correctly.
 */
class AudioOverrideManager(private val context: Context) {

    private val audio: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val notif: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────

    /**
     * Call when a priority contact's call is RINGING.
     * Returns true if override was applied.
     */
    fun applyOverride(): Boolean {
        return try {
            saveCurrentState()
            setFullVolume()
            overrideDnd()
            prefs.edit().putBoolean(KEY_OVERRIDE_ACTIVE, true).apply()
            Log.i(TAG, "Override applied — ringer mode: ${audio.ringerMode}, volume: ${audio.getStreamVolume(AudioManager.STREAM_RING)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply override", e)
            false
        }
    }

    /**
     * Call when the call ends (IDLE state) or on missed call.
     * Safe to call even if no override was applied.
     */
    fun restoreState() {
        if (!prefs.getBoolean(KEY_OVERRIDE_ACTIVE, false)) return

        try {
            val savedMode   = prefs.getInt(KEY_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
            val savedVolume = prefs.getInt(KEY_RINGER_VOLUME, audio.getStreamMaxVolume(AudioManager.STREAM_RING) / 2)
            val savedFilter = prefs.getInt(KEY_DND_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)

            audio.ringerMode = savedMode
            audio.setStreamVolume(AudioManager.STREAM_RING, savedVolume, 0)

            if (notif.isNotificationPolicyAccessGranted && savedFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                notif.setInterruptionFilter(savedFilter)
            }

            prefs.edit().putBoolean(KEY_OVERRIDE_ACTIVE, false).apply()
            Log.i(TAG, "State restored — mode: $savedMode, volume: $savedVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore state", e)
        }
    }

    fun isDndAccessGranted(): Boolean = notif.isNotificationPolicyAccessGranted

    fun isOverrideCurrentlyActive(): Boolean = prefs.getBoolean(KEY_OVERRIDE_ACTIVE, false)

    // ─────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────

    private fun saveCurrentState() {
        val currentVolume = audio.getStreamVolume(AudioManager.STREAM_RING)
        val currentMode   = audio.ringerMode
        val currentFilter = notif.currentInterruptionFilter

        prefs.edit()
            .putInt(KEY_RINGER_MODE,   currentMode)
            .putInt(KEY_RINGER_VOLUME, currentVolume)
            .putInt(KEY_DND_FILTER,    currentFilter)
            .apply()

        Log.d(TAG, "Saved state — mode: $currentMode, volume: $currentVolume, filter: $currentFilter")
    }

    private fun setFullVolume() {
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(
            AudioManager.STREAM_RING,
            maxVolume,
            AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE // don't play the volume-change sound
        )
    }

    private fun overrideDnd() {
        if (!notif.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "DND access not granted — cannot override DND")
            return
        }
        val current = notif.currentInterruptionFilter
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
            notif.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            Log.i(TAG, "DND overridden from filter: $current")
        }
    }

    companion object {
        private const val TAG         = "AudioOverrideManager"
        private const val PREFS_NAME  = "audio_override_state"
        private const val KEY_RINGER_MODE   = "ringer_mode"
        private const val KEY_RINGER_VOLUME = "ringer_volume"
        private const val KEY_DND_FILTER    = "dnd_filter"
        private const val KEY_OVERRIDE_ACTIVE = "override_active"
    }
}
