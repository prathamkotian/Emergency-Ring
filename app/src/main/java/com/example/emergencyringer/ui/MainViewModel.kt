package com.example.emergencyringer.ui

import android.app.Application
import android.content.ContentResolver
import android.provider.ContactsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.emergencyringer.EmergencyRingerApp
import com.example.emergencyringer.data.PriorityContact
import com.example.emergencyringer.util.PhoneNormalizer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "EmergencyRinger_ViewModel"

data class DeviceContact(
    val displayName: String,
    val phoneNumber: String,
    val lookupKey: String
)

data class MainUiState(
    val priorityContacts: List<PriorityContact> = emptyList(),
    val totalOverrides: Int = 0,
    val isServiceRunning: Boolean = true,
    val hasDndAccess: Boolean = false,
    val hasPhonePermission: Boolean = false,
    val hasContactsPermission: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EmergencyRingerApp
    private val dao = app.database.priorityContactDao()
    private val callEventDao = app.database.callEventDao()

    val uiState: StateFlow<MainUiState> = combine(
        dao.getAllContacts(),
        callEventDao.getTotalOverrides()
    ) { contacts, overrides ->
        Log.d(TAG, "UI State updated - Total contacts: ${contacts.size}, Total overrides: $overrides")
        MainUiState(
            priorityContacts = contacts,
            totalOverrides = overrides
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MainUiState()
    )

    // ─────────────────────────────────────────
    // Contact management
    // ─────────────────────────────────────────

    fun addContact(contact: PriorityContact) {
        Log.d(TAG, "addContact() called for: ${contact.displayName} (${contact.phoneNumber})")
        viewModelScope.launch {
            try {
                val insertedId = dao.insert(contact)
                Log.d(TAG, "Contact inserted successfully with ID: $insertedId")
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting contact", e)
            }
        }
    }

    fun removeContact(contactId: Long) {
        Log.d(TAG, "removeContact() called for ID: $contactId")
        viewModelScope.launch {
            try {
                dao.softDelete(contactId)
                Log.d(TAG, "Contact soft-deleted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting contact", e)
            }
        }
    }

    // ─────────────────────────────────────────
    // Pick from device contacts
    // ─────────────────────────────────────────

    fun fetchDeviceContacts(): List<DeviceContact> {
        val resolver: ContentResolver = getApplication<Application>().contentResolver
        val results = mutableListOf<DeviceContact>()

        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIdx   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val keyIdx    = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)

            while (it.moveToNext()) {
                val name   = it.getString(nameIdx)   ?: continue
                val number = it.getString(numberIdx) ?: continue
                val key    = it.getString(keyIdx)    ?: ""

                results.add(DeviceContact(
                    displayName = name,
                    phoneNumber = PhoneNormalizer.normalize(number),
                    lookupKey = key
                ))
            }
        }

        return results.distinctBy { it.phoneNumber }
    }

    fun addFromDeviceContact(deviceContact: DeviceContact) {
        Log.d(TAG, "addFromDeviceContact() called - Name: ${deviceContact.displayName}, Raw phone: ${deviceContact.phoneNumber}")
        
        val normalizedPhone = PhoneNormalizer.normalize(deviceContact.phoneNumber)
        Log.d(TAG, "Phone number normalized to: $normalizedPhone")
        
        viewModelScope.launch {
            try {
                val contact = PriorityContact(
                    displayName = deviceContact.displayName,
                    phoneNumber = normalizedPhone,
                    lookupKey = deviceContact.lookupKey
                )
                val insertedId = dao.insert(contact)
                Log.d(TAG, "Device contact inserted successfully with ID: $insertedId - $contact")
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting device contact", e)
            }
        }
    }
}
