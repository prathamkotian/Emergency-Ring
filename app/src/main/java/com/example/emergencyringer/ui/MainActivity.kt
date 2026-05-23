package com.example.emergencyringer.ui

import android.Manifest
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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.emergencyringer.data.PriorityContact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "EmergencyRinger_MainActivity"

// ─────────────────────────────────────────────────────────────────
// DESIGN TOKENS
// ─────────────────────────────────────────────────────────────────

private object Glass {
    val DeepNavy       = Color(0xFF050B18)
    val NavySurface    = Color(0xFF0A1628)
    val SheetBg        = Color(0xFF0D1520)

    val GlassWhite6    = Color(0x0FFFFFFF)
    val GlassWhite10   = Color(0x1AFFFFFF)
    val GlassWhite15   = Color(0x26FFFFFF)

    val BorderSubtle   = Color(0x14FFFFFF)
    val BorderRegular  = Color(0x26FFFFFF)
    val BorderStrong   = Color(0x40FFFFFF)

    val TealPrimary    = Color(0xFF30D5C8)
    val TealDim        = Color(0xFF1A9E93)
    val TealGlow       = Color(0xFF30D5C8).copy(alpha = 0.18f)
    val OrangeDanger   = Color(0xFFFF6B35)
    val AmberWarn      = Color(0xFFFFA726)
    val GreenOk        = Color(0xFF4CAF50)
    val RedError       = Color(0xFFEF5350)
    val PurpleAccent   = Color(0xFFAB47BC)
    val BlueAccent     = Color(0xFF42A5F5)
    val PinkAccent     = Color(0xFFEC407A)
    val OrangeAccent   = Color(0xFFFF7043)

    val TextPrimary    = Color(0xFFF0F4FF)
    val TextSecondary  = Color(0xFF9BA8C0)
    val TextTertiary   = Color(0xFF5C6880)

    val BlobBlue       = Color(0xFF1565C0)
    val BlobTeal       = Color(0xFF006064)
    val BlobIndigo     = Color(0xFF283593)

    val avatarColors = listOf(TealPrimary, PurpleAccent, BlueAccent, PinkAccent, TealDim, OrangeAccent)

    fun avatarColor(name: String) = avatarColors[name.hashCode().mod(avatarColors.size)]

    fun formattedDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

// ─────────────────────────────────────────────────────────────────
// ACTIVITY
// ─────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val permissionsRefreshState = mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsRefreshState.value++ }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) parseAndAddContact(contactUri)
    }

    private val multiContactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { intent ->
                val contactUris = mutableListOf<Uri>()
                
                // Try to get multiple URIs from clipData (multi-select support)
                intent.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        contactUris.add(clipData.getItemAt(i).uri)
                    }
                }
                
                // Fallback to single URI if clipData not available
                if (contactUris.isEmpty()) {
                    intent.data?.let { contactUris.add(it) }
                }
                
                // Add all selected contacts
                contactUris.forEach { parseAndAddContact(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        setContent {
            EmergencyRingerTheme {
                val refreshCount by permissionsRefreshState
                var hasAllPermissions by remember { mutableStateOf(false) }
                var showDuplicateDialog by remember { mutableStateOf(false) }
                var duplicateContactName by remember { mutableStateOf("") }
                var duplicatePhoneNumber by remember { mutableStateOf("") }

                LaunchedEffect(refreshCount) { hasAllPermissions = checkPermissions() }

                LaunchedEffect(Unit) {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is UiEvent.DuplicateContact -> {
                                duplicateContactName = event.contactName
                                duplicatePhoneNumber = event.phoneNumber
                                showDuplicateDialog = true
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
                    onRemoveAllContacts = { viewModel.removeAllContacts() },
                    onOpenBatterySettings = { openBatterySettings() },
                    onReorder = { ids -> viewModel.updateSortOrder(ids) }
                )

                if (showDuplicateDialog) {
                    GlassAlertDialog(
                        title = "Already Added",
                        message = "\"$duplicateContactName\" ($duplicatePhoneNumber) is already in your priority contacts.",
                        confirmLabel = "Got it",
                        onConfirm = { showDuplicateDialog = false },
                        onDismiss = { showDuplicateDialog = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
        if (notGranted.isNotEmpty()) permissionLauncher.launch(notGranted.toTypedArray())
    }

    private fun checkPermissions() = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun showContactPicker() {
        // Open Contacts app with ACTION_PICK for multi-select capability
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra("android.intent.extra.ALLOW_MULTIPLE", true)
        }
        try {
            multiContactPickerLauncher.launch(intent)
        } catch (e: Exception) {
            // Fallback to single contact picker if multi-select not supported
            contactPickerLauncher.launch(null)
        }
    }

    private fun parseAndAddContact(contactUri: Uri) {
        val contactCursor = contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.LOOKUP_KEY),
            null, null, null
        )
        contactCursor?.use {
            if (it.moveToFirst()) {
                val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val displayName = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                val lookupKey = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))
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
                        viewModel.addFromDeviceContact(DeviceContact(displayName, phoneNumber, lookupKey))
                    }
                }
            }
        }
    }

    private fun openBatterySettings() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}

// ─────────────────────────────────────────────────────────────────
// SCREEN
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyRingerScreen(
    uiState: MainUiState,
    onAddContact: () -> Unit,
    onRemoveContact: (PriorityContact) -> Unit,
    onRemoveAllContacts: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onReorder: (List<Long>) -> Unit
) {
    val allGood = uiState.hasPhonePermission

    // Contact detail sheet state
    var selectedContact by remember { mutableStateOf<PriorityContact?>(null) }
    var showRemoveAllConfirm by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Glass.DeepNavy)) {

        AuroraBackground()

        Scaffold(
            containerColor = Color.Transparent,
            floatingActionButton = { GlassFAB(onClick = onAddContact) }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { AppTitleHeader() }

                item {
                    HeroStatusCard(
                        isActive = allGood,
                        totalOverrides = uiState.totalOverrides,
                        contactCount = uiState.priorityContacts.size
                    )
                }

                if (!uiState.hasPhonePermission) {
                    item {
                        GlassWarningCard(
                            icon = Icons.AutoMirrored.Rounded.PhoneMissed,
                            title = "Phone Permission Required",
                            subtitle = "Needed to detect incoming calls",
                            actionLabel = "Grant",
                            onAction = {},
                            accentColor = Glass.OrangeDanger
                        )
                    }
                }

                item {
                    GlassWarningCard(
                        icon = Icons.Rounded.BatteryAlert,
                        title = "Disable Battery Optimization",
                        subtitle = "Keeps the service alive in the background",
                        actionLabel = "Fix",
                        onAction = onOpenBatterySettings,
                        accentColor = Glass.AmberWarn
                    )
                }

                item {
                    SectionHeader(
                        title = "Priority Contacts",
                        count = uiState.priorityContacts.size,
                        onRemoveAll = { showRemoveAllConfirm = true }
                    )
                }

                if (uiState.priorityContacts.isEmpty()) {
                    item { EmptyContactsCard(onAdd = onAddContact) }
                }

                itemsIndexed(
                    items = uiState.priorityContacts,
                    key = { _, contact -> contact.id }
                ) { _, contact ->
                    GlassContactRow(
                        contact = contact,
                        onTap = { selectedContact = contact },
                        onRemove = { onRemoveContact(contact) }
                    )
                }
            }
        }

        // ── Contact Detail Bottom Sheet ──
        if (selectedContact != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedContact = null },
                sheetState = sheetState,
                containerColor = Color.Transparent,
                dragHandle = null,
                windowInsets = WindowInsets(0)
            ) {
                ContactDetailSheet(
                    contact = selectedContact!!,
                    onDismiss = {
                        scope.launch {
                            sheetState.hide()
                            selectedContact = null
                        }
                    },
                    onRemove = {
                        scope.launch {
                            sheetState.hide()
                            selectedContact = null
                        }
                        onRemoveContact(selectedContact!!)
                    }
                )
            }
        }

        // ── Remove All Confirmation Dialog ──
        if (showRemoveAllConfirm) {
            GlassAlertDialog(
                title = "Remove All Contacts?",
                message = "This will remove all priority contacts and they will no longer bypass silent mode. This action cannot be undone.",
                confirmLabel = "Remove All",
                onConfirm = { 
                    showRemoveAllConfirm = false
                    onRemoveAllContacts()
                },
                onDismiss = { showRemoveAllConfirm = false },
                destructive = true
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// CONTACT DETAIL BOTTOM SHEET
// ─────────────────────────────────────────────────────────────────

@Composable
fun ContactDetailSheet(
    contact: PriorityContact,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    val accentColor = Glass.avatarColor(contact.displayName)
    var showRemoveConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(Glass.SheetBg)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(colors = listOf(Glass.BorderStrong, Glass.BorderSubtle)),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 36.dp)
        ) {
            // ── Drag pill ──
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Glass.BorderStrong)
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(24.dp))

            // ── Avatar hero ──
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Outer glow ring
                Box(contentAlignment = Alignment.Center) {
                    // Diffuse glow
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f))
                    )
                    // Avatar circle
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.22f))
                            .border(2.dp, accentColor.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contact.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            color = accentColor,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = contact.displayName,
                    color = Glass.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )

                Spacer(Modifier.height(4.dp))

                // Priority badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accentColor.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(accentColor))
                        Text("Priority Contact", color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Info tiles ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoTile(
                    icon = Icons.Rounded.Phone,
                    label = "Phone Number",
                    value = contact.phoneNumber,
                    accentColor = accentColor
                )
                InfoTile(
                    icon = Icons.Rounded.CalendarMonth,
                    label = "Added On",
                    value = Glass.formattedDate(contact.createdAt),
                    accentColor = accentColor
                )
                InfoTile(
                    icon = Icons.Rounded.NotificationsActive,
                    label = "Override Behaviour",
                    value = "Rings at full volume — bypasses Silent & Vibrate modes",
                    accentColor = Glass.TealPrimary
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Action buttons ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Close button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Glass.GlassWhite10)
                        .border(1.dp, Glass.BorderRegular, RoundedCornerShape(16.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Close", color = Glass.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }

                // Remove button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Glass.RedError.copy(alpha = 0.12f))
                        .border(1.dp, Glass.RedError.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .clickable { showRemoveConfirm = true },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.PersonRemove, contentDescription = null, tint = Glass.RedError, modifier = Modifier.size(18.dp))
                        Text("Remove", color = Glass.RedError, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        GlassAlertDialog(
            title = "Remove ${contact.displayName}?",
            message = "They will no longer bypass silent mode.",
            confirmLabel = "Remove",
            onConfirm = { showRemoveConfirm = false; onRemove() },
            onDismiss = { showRemoveConfirm = false },
            destructive = true
        )
    }
}

// ── Info Tile ──
@Composable
fun InfoTile(icon: ImageVector, label: String, value: String, accentColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass.GlassWhite6)
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accentColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = Glass.TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(value, color = Glass.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// GLASS CONTACT ROW  (tap = open sheet, long-press = drag)
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassContactRow(
    contact: PriorityContact,
    onTap: () -> Unit,
    onRemove: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = Glass.avatarColor(contact.displayName)
    var showConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(Glass.BorderRegular, Glass.BorderSubtle)
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onTap)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.2f))
                    .border(1.5.dp, accentColor.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    color = accentColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(contact.displayName, color = Glass.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(contact.phoneNumber, color = Glass.TextSecondary, fontSize = 12.sp)
            }

            SoundWaveIndicator(color = accentColor)

            Spacer(Modifier.width(4.dp))

            // Remove button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Glass.RedError.copy(alpha = 0.12f))
                    .clickable { showConfirm = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Remove", tint = Glass.RedError, modifier = Modifier.size(16.dp))
            }
        }
    }

    if (showConfirm) {
        GlassAlertDialog(
            title = "Remove ${contact.displayName}?",
            message = "They will no longer bypass silent mode.",
            confirmLabel = "Remove",
            onConfirm = { onRemove(); showConfirm = false },
            onDismiss = { showConfirm = false },
            destructive = true
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// AURORA BACKGROUND
// ─────────────────────────────────────────────────────────────────

@Composable
fun AuroraBackground() {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.size(320.dp).offset(x = (-80).dp, y = (-60).dp)
            .alpha(0.22f).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Glass.BlobBlue, Color.Transparent))))
        Box(modifier = Modifier.size(260.dp).align(Alignment.TopEnd).offset(x = 60.dp, y = 80.dp)
            .alpha(0.18f).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Glass.BlobTeal, Color.Transparent))))
        Box(modifier = Modifier.size(300.dp).align(Alignment.BottomCenter).offset(y = 100.dp)
            .alpha(0.15f).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Glass.BlobIndigo, Color.Transparent))))
    }
}

// ─────────────────────────────────────────────────────────────────
// APP TITLE HEADER
// ─────────────────────────────────────────────────────────────────

@Composable
fun AppTitleHeader() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Emergency Ring", color = Glass.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text("Priority call override", color = Glass.TextSecondary, fontSize = 14.sp)
        }
        BreathingDot()
    }
}

@Composable
fun BreathingDot() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
        Box(Modifier.size(24.dp).alpha(0.4f).clip(CircleShape).background(Glass.TealPrimary))
        Box(Modifier.size(10.dp).clip(CircleShape).background(Glass.TealPrimary))
    }
}

// ─────────────────────────────────────────────────────────────────
// HERO STATUS CARD
// ─────────────────────────────────────────────────────────────────

@Composable
fun HeroStatusCard(isActive: Boolean, totalOverrides: Int, contactCount: Int) {
    val statusColor = if (isActive) Glass.GreenOk else Glass.OrangeDanger

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Glass.GlassWhite10)
        .border(1.dp, Brush.linearGradient(listOf(Glass.BorderStrong, Glass.BorderSubtle)), RoundedCornerShape(24.dp)).padding(22.dp)) {
        Column {
            Box(modifier = Modifier.clip(RoundedCornerShape(50.dp)).background(statusColor.copy(0.18f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isActive) "Active & Monitoring" else "Setup Required", color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HeroStat("Contacts", contactCount.toString(), Glass.TealPrimary)
                Box(Modifier.height(36.dp).width(1.dp).background(Glass.BorderRegular))
                HeroStat("Overrides", totalOverrides.toString(), Glass.AmberWarn)
                Box(Modifier.height(36.dp).width(1.dp).background(Glass.BorderRegular))
                HeroStat("Status", if (isActive) "On" else "Off", statusColor)
            }
            Spacer(Modifier.height(16.dp))
            Text("Trusted contacts ring at full volume even on silent or Do Not Disturb.", color = Glass.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun HeroStat(label: String, value: String, accentColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = accentColor, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        Text(label, color = Glass.TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────
// SECTION HEADER
// ─────────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String, count: Int, onRemoveAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Glass.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedContent(count, transitionSpec = { slideInVertically { -it } + fadeIn() togetherWith slideOutVertically { it } + fadeOut() }, label = "cnt") { c ->
                Text("$c contacts", color = Glass.TextTertiary, fontSize = 13.sp)
            }
            if (count > 0 && onRemoveAll != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Glass.RedError.copy(alpha = 0.15f))
                        .clickable { onRemoveAll() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Remove All", color = Glass.RedError, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// GLASS WARNING CARD
// ─────────────────────────────────────────────────────────────────

@Composable
fun GlassWarningCard(icon: ImageVector, title: String, subtitle: String, actionLabel: String, onAction: () -> Unit, accentColor: Color) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(accentColor.copy(0.08f))
        .border(1.dp, accentColor.copy(0.25f), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accentColor.copy(0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Glass.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Glass.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(accentColor.copy(0.2f)).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(actionLabel, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// SOUND WAVE INDICATOR
// ─────────────────────────────────────────────────────────────────

@Composable
fun SoundWaveIndicator(color: Color) {
    val it = rememberInfiniteTransition(label = "wave")
    val heights = (0..3).map { i ->
        it.animateFloat(3f, 14f, infiniteRepeatable(tween(500 + i * 120, easing = EaseInOutSine), RepeatMode.Reverse, StartOffset(i * 90)), label = "b$i")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(18.dp)) {
        heights.forEach { h ->
            Box(Modifier.width(3.dp).height(h.value.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(0.7f)))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// EMPTY CONTACTS CARD
// ─────────────────────────────────────────────────────────────────

@Composable
fun EmptyContactsCard(onAdd: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); visible = true }
    AnimatedVisibility(visible, enter = fadeIn(tween(500)) + scaleIn(initialScale = 0.95f, animationSpec = spring(Spring.DampingRatioMediumBouncy))) {
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Glass.GlassWhite6)
            .border(1.dp, Brush.linearGradient(listOf(Glass.BorderRegular, Glass.BorderSubtle)), RoundedCornerShape(24.dp)).padding(36.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Glass.TealGlow).border(1.dp, Glass.TealPrimary.copy(0.3f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PersonAdd, null, tint = Glass.TealPrimary, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("No priority contacts", color = Glass.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Add contacts whose calls should always ring at full volume, no matter what.", color = Glass.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                Spacer(Modifier.height(24.dp))
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Glass.TealPrimary, Glass.TealDim))).clickable(onClick = onAdd).padding(horizontal = 28.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Contact", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// FAB
// ─────────────────────────────────────────────────────────────────

@Composable
fun GlassFAB(onClick: () -> Unit) {
    val scale by animateFloatAsState(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh), label = "fab")
    Box(
        modifier = Modifier.size(60.dp).scale(scale).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Glass.TealPrimary, Glass.TealDim)))
            .border(1.dp, Color.White.copy(0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Add, "Add priority contact", tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// GLASS ALERT DIALOG
// ─────────────────────────────────────────────────────────────────

@Composable
fun GlassAlertDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = Glass.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
        text = { Text(message, color = Glass.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            Box(Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (destructive) Glass.RedError.copy(0.15f) else Glass.TealPrimary.copy(0.15f))
                .clickable(onClick = onConfirm).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(confirmLabel, color = if (destructive) Glass.RedError else Glass.TealPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Glass.TextSecondary, fontSize = 14.sp) } }
    )
}

// ─────────────────────────────────────────────────────────────────
// THEME
// ─────────────────────────────────────────────────────────────────

@Composable
fun EmergencyRingerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Glass.TealPrimary, secondary = Glass.TealDim, error = Glass.RedError,
            background = Glass.DeepNavy, surface = Glass.NavySurface,
            surfaceVariant = Color(0xFF0F1C30), onSurface = Glass.TextPrimary,
            onBackground = Glass.TextPrimary, onPrimary = Color.White
        ),
        content = content
    )
}