package com.flowstate.focus.data.db

import kotlinx.serialization.Serializable

/**
 * Desktop twin of the Android Room entity, so the shared StatsCalculator compiles unchanged.
 * One finished or abandoned focus block. Breaks are never stored.
 *
 * [cloudId] is the id of users/{uid}/sessions/{cloudId} once synced, [ownerUid] the account it belongs
 * to, and [origin] the deviceId that recorded it (another device's id for a session pulled from the cloud).
 */
@Serializable
data class SessionEntity(
    val id: Long = 0,
    val startedAt: Long,
    val durationMs: Long,
    val mode: String,
    val subject: String,
    val completed: Boolean,
    val cloudId: String? = null,
    val ownerUid: String? = null,
    val origin: String? = null,
)

@Serializable
data class TaskEntity(
    val id: Long = 0,
    val title: String,
    val category: String,
    /** 0 = low, 1 = medium, 2 = high. */
    val priority: Int,
    val deadline: Long?,
    val done: Boolean = false,
    val createdAt: Long,
)
