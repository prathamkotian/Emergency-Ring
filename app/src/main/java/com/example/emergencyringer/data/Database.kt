package com.example.emergencyringer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────
// ENTITY — Priority Contact
// ─────────────────────────────────────────

@Entity(tableName = "priority_contacts")
data class PriorityContact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,           // normalized E.164 e.g. +919876543210

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "lookup_key")
    val lookupKey: String? = null,     // Android contacts lookup key (stable across number changes)

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true       // soft delete — future-proofs sync
)

// ─────────────────────────────────────────
// ENTITY — Call Log (audit trail)
// ─────────────────────────────────────────

@Entity(tableName = "call_events")
data class CallEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    @ColumnInfo(name = "display_name")
    val displayName: String?,

    @ColumnInfo(name = "was_override_triggered")
    val wasOverrideTriggered: Boolean,

    @ColumnInfo(name = "previous_ringer_mode")
    val previousRingerMode: Int,       // AudioManager.RINGER_MODE_*

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────
// DAO — Priority Contacts
// ─────────────────────────────────────────

@Dao
interface PriorityContactDao {

    @Query("SELECT * FROM priority_contacts WHERE is_active = 1 ORDER BY display_name ASC")
    fun getAllContacts(): Flow<List<PriorityContact>>

    @Query("SELECT * FROM priority_contacts WHERE is_active = 1 ORDER BY display_name ASC")
    suspend fun getAllContactsOnce(): List<PriorityContact>

    @Query("""
        SELECT * FROM priority_contacts 
        WHERE is_active = 1 
        AND phone_number = :normalizedNumber 
        LIMIT 1
    """)
    suspend fun findByNumber(normalizedNumber: String): PriorityContact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: PriorityContact): Long

    @Update
    suspend fun update(contact: PriorityContact)

    @Query("UPDATE priority_contacts SET is_active = 0 WHERE id = :id")
    suspend fun softDelete(id: Long)

    @Query("SELECT COUNT(*) FROM priority_contacts WHERE is_active = 1")
    fun getActiveCount(): Flow<Int>
}

// ─────────────────────────────────────────
// DAO — Call Events
// ─────────────────────────────────────────

@Dao
interface CallEventDao {

    @Insert
    suspend fun insert(event: CallEvent)

    @Query("SELECT * FROM call_events ORDER BY timestamp DESC LIMIT 50")
    fun getRecentEvents(): Flow<List<CallEvent>>

    @Query("SELECT COUNT(*) FROM call_events WHERE was_override_triggered = 1")
    fun getTotalOverrides(): Flow<Int>
}

// ─────────────────────────────────────────
// DATABASE
// ─────────────────────────────────────────

@Database(
    entities = [PriorityContact::class, CallEvent::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun priorityContactDao(): PriorityContactDao
    abstract fun callEventDao(): CallEventDao

    companion object {
        const val DATABASE_NAME = "emergency_ringer.db"
    }
}
