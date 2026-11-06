package com.flowstate.focus.data.exams

import com.flowstate.focus.data.db.ExamDao
import com.flowstate.focus.data.db.ExamEntity
import com.flowstate.focus.domain.EXAM_NAME_MAX
import com.flowstate.focus.domain.Exam
import com.flowstate.focus.domain.ExamTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 1.7.0 exam countdowns. Room only: nothing here is uploaded, so Firestore rules are unchanged. */
@Singleton
class ExamRepository @Inject constructor(private val dao: ExamDao) {
    val exams: Flow<List<Exam>> = dao.all().map { rows -> rows.map { it.toExam() } }

    suspend fun save(exam: Exam): Long = dao.save(exam.toEntity())
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun setShown(id: Long, shown: Boolean) = dao.setShown(id, shown)
    suspend fun pin(id: Long) = dao.pin(id)
    suspend fun unpin() = dao.clearPins()
}

private fun ExamEntity.toExam() = Exam(id, name, date, emoji, tag.coerceIn(0, ExamTags.lastIndex), showOnFocus, pinned, createdAt)

private fun Exam.toEntity() = ExamEntity(
    id = id,
    name = name.trim().take(EXAM_NAME_MAX),
    date = date,
    emoji = emoji,
    tag = tag.coerceIn(0, ExamTags.lastIndex),
    showOnFocus = showOnFocus || pinned,
    pinned = pinned,
    createdAt = createdAt,
)
