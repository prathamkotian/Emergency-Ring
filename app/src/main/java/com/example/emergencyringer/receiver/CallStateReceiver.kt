package com.example.emergencyringer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.example.emergencyringer.EmergencyRingerApp
import com.example.emergencyringer.data.CallEvent
import com.example.emergencyringer.util.AudioOverrideManager
import com.example.emergencyringer.util.PhoneNormalizer
import kotlinx.coroutines.*

/**
 * CallStateReceiver
 *
 * Listens for phone state changes. On RINGING:
 *   1. Get the incoming number
 *   2. Check if it's in the priority contacts list
 *   3. If yes → apply audio override
 *
 * On IDLE (call ended or missed):
 *   4. Restore previous audio state
 *   5. Log the event to Room DB
 */
class CallStateReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        Log.d(TAG, "Phone state: $state, number: ${incomingNumber.takeLast(4).padStart(incomingNumber.length, '*')}")

        val app = context.applicationContext as EmergencyRingerApp
        val audioManager = AudioOverrideManager(context)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> handleRinging(incomingNumber, app, audioManager)
            TelephonyManager.EXTRA_STATE_IDLE    -> handleIdle(app, audioManager)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> { /* call answered — keep override active */ }
        }
    }

    // ─────────────────────────────────────────
    // RINGING: check priority list & override
    // ─────────────────────────────────────────

    private fun handleRinging(number: String, app: EmergencyRingerApp, audio: AudioOverrideManager) {
        if (number.isBlank()) {
            Log.w(TAG, "Ringing with unknown number — skipping")
            return
        }

        scope.launch {
            val normalizedIncoming = PhoneNormalizer.normalize(number)
            val allContacts = app.database.priorityContactDao().getAllContactsOnce()

            val matchedContact = allContacts.firstOrNull { contact ->
                PhoneNormalizer.matches(contact.phoneNumber, normalizedIncoming)
            }

            if (matchedContact != null) {
                Log.i(TAG, "Priority contact matched: ${matchedContact.displayName} — applying override")
                val overrideApplied = audio.applyOverride()

                // Log to DB — fire and forget
                app.database.callEventDao().insert(
                    CallEvent(
                        phoneNumber = number,
                        displayName = matchedContact.displayName,
                        wasOverrideTriggered = overrideApplied,
                        previousRingerMode = audio.isOverrideCurrentlyActive().let { 0 }
                    )
                )
            } else {
                Log.d(TAG, "Number not in priority list — no override")
            }
        }
    }

    // ─────────────────────────────────────────
    // IDLE: always restore, safe if no override
    // ─────────────────────────────────────────

    private fun handleIdle(app: EmergencyRingerApp, audio: AudioOverrideManager) {
        Log.d(TAG, "Call ended — restoring audio state")
        audio.restoreState()
    }

    companion object {
        private const val TAG = "CallStateReceiver"
    }
}
