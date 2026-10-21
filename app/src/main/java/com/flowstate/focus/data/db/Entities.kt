package com.flowstate.focus.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One finished or abandoned focus block. Breaks are never stored. */
@Entity(tableName = "sessions", indices = [Index("startedAt")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationMs: Long,
    val mode: String,
    val subject: String,
    val completed: Boolean,
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
