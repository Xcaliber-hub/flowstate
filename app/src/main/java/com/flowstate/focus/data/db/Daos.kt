package com.flowstate.focus.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE startedAt >= :fromMs ORDER BY startedAt DESC")
    fun since(fromMs: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ---- Session sync (1.6.0) ----

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    /** Sessions pulled from the account's other devices; one already stored (same cloudId) is skipped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoring(rows: List<SessionEntity>): List<Long>

    @Query("SELECT * FROM sessions WHERE cloudId IS NULL")
    suspend fun unsynced(): List<SessionEntity>

    @Query("UPDATE sessions SET cloudId = :cloudId, ownerUid = :ownerUid, origin = :origin WHERE id = :id")
    suspend fun markSynced(id: Long, cloudId: String, ownerUid: String, origin: String)

    @Query("UPDATE sessions SET cloudId = NULL WHERE id IN (:ids)")
    suspend fun unmarkSynced(ids: List<Long>)

    @Query("UPDATE sessions SET ownerUid = :uid WHERE ownerUid IS NULL AND id IN (:ids)")
    suspend fun claim(ids: List<Long>, uid: String)

    @Query("SELECT DISTINCT ownerUid FROM sessions WHERE ownerUid IS NOT NULL")
    suspend fun owners(): List<String>

    @Query("DELETE FROM sessions WHERE cloudId = :cloudId")
    suspend fun deleteByCloudId(cloudId: String)

    /** On sign-out: forget the sessions that were only pulled from the account's other devices. */
    @Query("DELETE FROM sessions WHERE origin IS NOT NULL AND origin != :deviceId")
    suspend fun deletePulled(deviceId: String)
}

@Dao
interface TaskDao {
    @Query(
        "SELECT * FROM tasks ORDER BY done ASC, priority DESC, " +
            "CASE WHEN deadline IS NULL THEN 1 ELSE 0 END, deadline ASC, createdAt DESC"
    )
    fun observe(): Flow<List<TaskEntity>>

    @Insert
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE done = 1")
    suspend fun clearDone()
}
