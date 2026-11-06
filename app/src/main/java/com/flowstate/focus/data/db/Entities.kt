package com.flowstate.focus.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One finished or abandoned focus block. Breaks are never stored.
 *
 * Since 1.6.0 (database version 2) sessions sync across the account's devices:
 * [cloudId] is the id of users/{uid}/sessions/{cloudId} once uploaded or pulled (unique, so a pulled
 * session is never stored twice), [ownerUid] the account it belongs to, and [origin] the deviceId that
 * recorded it (another device's id for a session pulled from the cloud). All three are null on older rows.
 */
@Entity(tableName = "sessions", indices = [Index("startedAt"), Index(value = ["cloudId"], unique = true)])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationMs: Long,
    val mode: String,
    val subject: String,
    val completed: Boolean,
    val cloudId: String? = null,
    val ownerUid: String? = null,
    val origin: String? = null,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    /** 0 = low, 1 = medium, 2 = high. */
    val priority: Int,
    val deadline: Long?,
    val done: Boolean = false,
    val createdAt: Long,
)

/**
 * 1.7.0 (database version 3): an exam (or any dated goal) to count down to. Local only, never synced.
 *
 * [tag] indexes [com.flowstate.focus.domain.ExamTags]; [showOnFocus] puts it in the Focus countdown
 * card; at most one exam is [pinned], and the pinned one is shown first instead of the nearest.
 */
@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val date: java.time.LocalDate,
    val emoji: String,
    val tag: Int,
    val showOnFocus: Boolean = true,
    val pinned: Boolean = false,
    val createdAt: Long,
)
