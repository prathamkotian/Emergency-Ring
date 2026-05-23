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
    val hasPhonePermission: Boolean = false,
    val hasContactsPermission: Boolean = false
)

sealed class UiEvent {
    data class DuplicateContact(val contactName: String, val phoneNumber: String) : UiEvent()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EmergencyRingerApp
    private val dao = app.database.priorityContactDao()
    private val callEventDao = app.database.callEventDao()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    // Holds the user's manual sort order: list of contact IDs in display order.
    // Stored in memory; persisted to SharedPreferences so it survives restarts.
    private val prefs by lazy {
        application.getSharedPreferences("emergency_ringer_ui", android.content.Context.MODE_PRIVATE)
    }

    private val _sortOrder = MutableStateFlow<List<Long>>(loadSortOrder())

    val uiState: StateFlow<MainUiState> = combine(
        dao.getAllContacts(),
        callEventDao.getTotalOverrides(),
        _sortOrder
    ) { contacts, overrides, sortOrder ->
        Log.d(TAG, "UI State updated — contacts: ${contacts.size}, overrides: $overrides")

        // Apply manual sort: contacts present in sortOrder first (in that order),
        // then any newly-added contacts appended at the end.
        val sortedContacts = if (sortOrder.isEmpty()) {
            contacts
        } else {
            val byId = contacts.associateBy { it.id }
            val ordered = sortOrder.mapNotNull { byId[it] }
            val unsorted = contacts.filter { it.id !in sortOrder }
            ordered + unsorted
        }

        MainUiState(
            priorityContacts = sortedContacts,
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
        viewModelScope.launch {
            try {
                val insertedId = dao.insert(contact)
                Log.d(TAG, "Contact inserted with ID: $insertedId")
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting contact", e)
            }
        }
    }

    fun removeContact(contactId: Long) {
        viewModelScope.launch {
            try {
                dao.softDelete(contactId)
                // Remove from sort order too
                val updated = _sortOrder.value.filter { it != contactId }
                _sortOrder.value = updated
                saveSortOrder(updated)
                Log.d(TAG, "Contact $contactId removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting contact", e)
            }
        }
    }

    fun removeAllContacts() {
        viewModelScope.launch {
            try {
                uiState.value.priorityContacts.forEach { contact ->
                    dao.softDelete(contact.id)
                }
                _sortOrder.value = emptyList()
                saveSortOrder(emptyList())
                Log.d(TAG, "All contacts removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting all contacts", e)
            }
        }
    }

    // Called when user finishes a drag-reorder gesture.
    // `reorderedIds` is the new desired display order as a list of contact IDs.
    fun updateSortOrder(reorderedIds: List<Long>) {
        _sortOrder.value = reorderedIds
        saveSortOrder(reorderedIds)
        Log.d(TAG, "Sort order updated: $reorderedIds")
    }

    // ─────────────────────────────────────────
    // Device contact picker
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
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val keyIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: continue
                val number = it.getString(numberIdx) ?: continue
                val key = it.getString(keyIdx) ?: ""
                results.add(DeviceContact(displayName = name, phoneNumber = PhoneNormalizer.normalize(number), lookupKey = key))
            }
        }
        return results.distinctBy { it.phoneNumber }
    }

    fun addFromDeviceContact(deviceContact: DeviceContact) {
        val normalizedPhone = PhoneNormalizer.normalize(deviceContact.phoneNumber)
        viewModelScope.launch {
            try {
                val existingContact = dao.findByNumber(normalizedPhone)
                if (existingContact != null) {
                    _uiEvent.emit(UiEvent.DuplicateContact(existingContact.displayName, normalizedPhone))
                    return@launch
                }
                val contact = PriorityContact(
                    displayName = deviceContact.displayName,
                    phoneNumber = normalizedPhone,
                    lookupKey = deviceContact.lookupKey
                )
                val insertedId = dao.insert(contact)
                Log.d(TAG, "Device contact inserted with ID: $insertedId")
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting device contact", e)
            }
        }
    }

    // ─────────────────────────────────────────
    // Sort order persistence
    // ─────────────────────────────────────────

    private fun saveSortOrder(ids: List<Long>) {
        prefs.edit()
            .putString("contact_sort_order", ids.joinToString(","))
            .apply()
    }

    private fun loadSortOrder(): List<Long> {
        val raw = prefs.getString("contact_sort_order", "") ?: ""
        return if (raw.isBlank()) emptyList()
        else raw.split(",").mapNotNull { it.toLongOrNull() }
    }
}