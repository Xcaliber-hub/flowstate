package com.flowstate.focus.data.repo

import com.flowstate.focus.data.db.SessionDao
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.db.TaskDao
import com.flowstate.focus.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface SessionRepository {
    fun sessionsSince(fromMs: Long): Flow<List<SessionEntity>>
    suspend fun insert(session: SessionEntity)
    suspend fun all(): List<SessionEntity>
    suspend fun delete(id: Long)
}

class SessionRepositoryImpl @Inject constructor(private val dao: SessionDao) : SessionRepository {
    override fun sessionsSince(fromMs: Long): Flow<List<SessionEntity>> = dao.since(fromMs)
    override suspend fun insert(session: SessionEntity) = dao.insert(session)
    override suspend fun all(): List<SessionEntity> = dao.all()
    override suspend fun delete(id: Long) = dao.deleteById(id)
}

interface TaskRepository {
    fun observe(): Flow<List<TaskEntity>>
    suspend fun add(task: TaskEntity)
    suspend fun update(task: TaskEntity)
    suspend fun delete(task: TaskEntity)
    suspend fun clearDone()
}

class TaskRepositoryImpl @Inject constructor(private val dao: TaskDao) : TaskRepository {
    override fun observe(): Flow<List<TaskEntity>> = dao.observe()
    override suspend fun add(task: TaskEntity) = dao.insert(task)
    override suspend fun update(task: TaskEntity) = dao.update(task)
    override suspend fun delete(task: TaskEntity) = dao.delete(task)
    override suspend fun clearDone() = dao.clearDone()
}
