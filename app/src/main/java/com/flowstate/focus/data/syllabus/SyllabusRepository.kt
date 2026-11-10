package com.flowstate.focus.data.syllabus

import android.content.Context
import android.util.Log
import com.flowstate.focus.data.db.SyllabusDao
import com.flowstate.focus.data.db.SyllabusProgressEntity
import com.flowstate.focus.domain.ChapterProgress
import com.flowstate.focus.domain.Syllabus
import com.flowstate.focus.domain.SyllabusParser
import com.flowstate.focus.domain.SyllabusStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1.8.0 syllabus tracker. The chapter lists ship in assets/syllabus.json; progress lives in Room
 * (syllabus_progress), keyed by subject + normalised chapter name so a chapter shared by JEE, NEET and
 * IAT is one row. Local only: nothing is uploaded, so Firestore rules are unchanged.
 */
@Singleton
class SyllabusRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SyllabusDao,
) {
    @Volatile private var cached: Syllabus? = null
    private val writeMutex = Mutex()

    /** The bundled syllabus, parsed once. */
    val syllabus: Flow<Syllabus> = flow { emit(load()) }

    suspend fun load(): Syllabus = cached ?: withContext(Dispatchers.IO) {
        cached ?: runCatching {
            SyllabusParser.parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        }.onFailure { Log.w("Flowstate", "Syllabus asset unreadable", it) }
            .getOrDefault(Syllabus("", emptyList()))
            .also { cached = it }
    }

    /** Every chapter's progress by key; a chapter with no row has no progress yet. */
    val progress: Flow<Map<String, ChapterProgress>> = dao.observeAll().map { rows -> rows.associate { it.key to it.toProgress() } }

    /** Reads, changes and saves one chapter's progress atomically. */
    suspend fun update(key: String, change: (ChapterProgress) -> ChapterProgress) = writeMutex.withLock {
        val current = dao.get(key)?.toProgress() ?: ChapterProgress.empty(key)
        val next = change(current)
        if (next != current) dao.upsert(next.toEntity(System.currentTimeMillis()))
    }

    private companion object { const val ASSET = "syllabus.json" }
}

internal fun SyllabusProgressEntity.toProgress(): ChapterProgress {
    val ticks = buildMap {
        doneAt?.let { put(SyllabusStep.DONE, it) }
        r1At?.let { put(SyllabusStep.R1, it) }
        r2At?.let { put(SyllabusStep.R2, it) }
        r3At?.let { put(SyllabusStep.R3, it) }
        r4At?.let { put(SyllabusStep.R4, it) }
        pyqAt?.let { put(SyllabusStep.PYQ, it) }
        mockAt?.let { put(SyllabusStep.MOCK, it) }
    }
    return ChapterProgress(key, ticks, pyqSets, pyqTarget, mocks, note)
}

internal fun ChapterProgress.toEntity(now: Long) = SyllabusProgressEntity(
    key = key,
    doneAt = ticks[SyllabusStep.DONE], r1At = ticks[SyllabusStep.R1], r2At = ticks[SyllabusStep.R2],
    r3At = ticks[SyllabusStep.R3], r4At = ticks[SyllabusStep.R4], pyqAt = ticks[SyllabusStep.PYQ], mockAt = ticks[SyllabusStep.MOCK],
    pyqSets = pyqSets, pyqTarget = pyqTarget, mocks = mocks, note = note.take(NOTE_MAX), updatedAt = now,
)

const val NOTE_MAX = 500
