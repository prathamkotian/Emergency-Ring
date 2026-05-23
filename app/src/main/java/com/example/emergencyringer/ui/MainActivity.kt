package com.example.emergencyringer.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.emergencyringer.data.PriorityContact
import com.example.emergencyringer.util.AudioOverrideManager

private const val TAG = "EmergencyRinger_MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    
    // Shared state to trigger permission updates
    private val permissionsRefreshState = mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { 
        Log.d(TAG, "Permissions result received - refreshing UI")
        permissionsRefreshState.value++
    }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) {
            Log.d(TAG, "Contact selected: $contactUri")
            parseAndAddContact(contactUri)
        } else {
            Log.d(TAG, "Contact picker cancelled by user")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            EmergencyRingerTheme {
                // Observe permission refresh state
                val refreshCount by permissionsRefreshState

                // Reactive permission state that updates when permissions change
                var hasAllPermissions by remember { mutableStateOf(false) }
                
                // State for duplicate contact dialog
                var showDuplicateDialog by remember { mutableStateOf(false) }
                var duplicateContactName by remember { mutableStateOf("") }
                var duplicatePhoneNumber by remember { mutableStateOf("") }

                // Re-check permissions whenever permissions are granted or user returns from settings
                LaunchedEffect(refreshCount) {
                    hasAllPermissions = checkPermissions()
                    Log.d(TAG, "Permission state refreshed - hasAll: $hasAllPermissions")
                }
                
                // Observe UI events from ViewModel
                LaunchedEffect(Unit) {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is UiEvent.DuplicateContact -> {
                                duplicateContactName = event.contactName
                                duplicatePhoneNumber = event.phoneNumber
                                showDuplicateDialog = true
                                Log.d(TAG, "Duplicate contact detected: ${event.contactName}")
                            }
                        }
                    }
                }

                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                EmergencyRingerScreen(
                    uiState = uiState.copy(
                        hasPhonePermission = hasAllPermissions,
                        hasContactsPermission = hasAllPermissions
                    ),
                    onAddContact = { showContactPicker() },
                    onRemoveContact = { viewModel.removeContact(it.id) },
                    onOpenBatterySettings = { openBatterySettings() }
                )
                
                // Duplicate Contact Dialog
                if (showDuplicateDialog) {
                    AlertDialog(
                        onDismissRequest = { showDuplicateDialog = false },
                        title = { Text("Contact Already Exists") },
                        text = { Text("\"$duplicateContactName\" ($duplicatePhoneNumber) is already in your priority contacts.") },
                        confirmButton = {
                            TextButton(onClick = { showDuplicateDialog = false }) {
                                Text("OK")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Activity resumed - refreshing permission state")
        permissionsRefreshState.value++
    }

    private fun requestRequiredPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun checkPermissions(): Boolean {
        val required = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showContactPicker() {
        Log.d(TAG, "Opening contact picker")
        contactPickerLauncher.launch(null)
    }

    private fun parseAndAddContact(contactUri: Uri) {
        Log.d(TAG, "Parsing contact from URI: $contactUri")
        
        // Step 1: Query the contact URI to get contact ID and basic info
        val contactCursor = contentResolver.query(
            contactUri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.LOOKUP_KEY
            ),
            null,
            null,
            null
        )

        contactCursor?.use {
            if (it.moveToFirst()) {
                val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val displayName = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                val lookupKey = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))

                Log.d(TAG, "Contact info - ID: $contactId, Name: $displayName, LookupKey: $lookupKey")
                
                // Step 2: Query the phone number for this contact
                val phoneCursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC LIMIT 1"
                )

                phoneCursor?.use { phoneCur ->
                    if (phoneCur.moveToFirst()) {
                        val phoneNumber = phoneCur.getString(phoneCur.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        Log.d(TAG, "Contact parsed - Name: $displayName, Phone: $phoneNumber, LookupKey: $lookupKey")
                        
                        val deviceContact = DeviceContact(
                            displayName = displayName,
                            phoneNumber = phoneNumber,
                            lookupKey = lookupKey
                        )
                        
                        Log.d(TAG, "Adding device contact to emergency ringer: $displayName")
                        viewModel.addFromDeviceContact(deviceContact)
                    } else {
                        Log.w(TAG, "No phone number found for contact: $displayName")
                    }
                }
            } else {
                Log.w(TAG, "No data found in contact cursor")
            }
        }
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }


}

// ─────────────────────────────────────────
// COMPOSABLE UI
// ─────────────────────────────────────────

@Composable
fun EmergencyRingerScreen(
    uiState: MainUiState,
    onAddContact: () -> Unit,
    onRemoveContact: (PriorityContact) -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val allGood = uiState.hasPhonePermission

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddContact,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add contact")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            item {
                Spacer(Modifier.height(16.dp))
                HeaderCard(
                    isActive = allGood,
                    totalOverrides = uiState.totalOverrides
                )
            }

            // Permission warnings
            if (!uiState.hasPhonePermission) {
                item {
                    WarningCard(
                        icon = Icons.Default.Phone,
                        title = "Phone Permission Required",
                        subtitle = "Needed to detect incoming calls",
                        actionLabel = "Grant",
                        onAction = { /* handled by system */ },
                        color = Color(0xFFFF6B35)
                    )
                }
            }

            // Battery optimization tip (always show)
            item {
                WarningCard(
                    icon = Icons.Default.BatteryAlert,
                    title = "Disable Battery Optimization",
                    subtitle = "Prevents Android from killing the service",
                    actionLabel = "Disable",
                    onAction = onOpenBatterySettings,
                    color = Color(0xFFF4A261)
                )
            }

            // Contact list header
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Priority Contacts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${uiState.priorityContacts.size} contacts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Empty state
            if (uiState.priorityContacts.isEmpty()) {
                item { EmptyContactsCard(onAddContact) }
            }

            // Contact rows
            items(uiState.priorityContacts, key = { it.id }) { contact ->
                ContactRow(contact = contact, onRemove = { onRemoveContact(contact) })
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun HeaderCard(isActive: Boolean, totalOverrides: Int) {
    val bgColor = if (isActive) Color(0xFF1B4332) else Color(0xFF3D1515)
    val statusText = if (isActive) "Active & Monitoring" else "Setup Required"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF52B788) else Color(0xFFE63946))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusText,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Emergency Ringer",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Calls full volume even on silent",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
            if (totalOverrides > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "🔔 $totalOverrides override${if (totalOverrides > 1) "s" else ""} triggered",
                    color = Color(0xFF95D5B2),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun WarningCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    color: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            TextButton(onClick = onAction) {
                Text(actionLabel, color = color, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ContactRow(contact: PriorityContact, onRemove: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.displayName, fontWeight = FontWeight.Medium)
                Text(
                    contact.phoneNumber,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = { showConfirm = true }) {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Remove ${contact.displayName}?") },
            text = { Text("They will no longer trigger emergency ring.") },
            confirmButton = {
                TextButton(onClick = { onRemove(); showConfirm = false }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun EmptyContactsCard(onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(12.dp))
            Text("No priority contacts yet", fontWeight = FontWeight.Medium)
            Text(
                "Add contacts whose calls should always ring loud",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add Contact")
            }
        }
    }
}

// ─────────────────────────────────────────
// THEME
// ─────────────────────────────────────────

@Composable
fun EmergencyRingerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary   = Color(0xFF52B788),
            secondary = Color(0xFF74C69D),
            error     = Color(0xFFE63946),
            background = Color(0xFF0D1117),
            surface   = Color(0xFF161B22),
            surfaceVariant = Color(0xFF1C2128),
            onSurface = Color(0xFFE6EDF3)
        ),
        content = content
    )
}
