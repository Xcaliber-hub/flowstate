package com.flowstate.focus.ui.syllabus

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.exams.ExamRepository
import com.flowstate.focus.data.syllabus.SyllabusRepository
import com.flowstate.focus.data.time.TodayProvider
import com.flowstate.focus.domain.ChapterEdits
import com.flowstate.focus.domain.ChapterProgress
import com.flowstate.focus.domain.Exam
import com.flowstate.focus.domain.ExamCountdown
import com.flowstate.focus.domain.Syllabus
import com.flowstate.focus.domain.SyllabusExam
import com.flowstate.focus.domain.SyllabusKeys
import com.flowstate.focus.domain.SyllabusStep
import com.flowstate.focus.service.FocusPrefill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@Immutable
data class SyllabusUi(
    val loaded: Boolean = false,
    val syllabus: Syllabus = Syllabus("", emptyList()),
    val progress: Map<String, ChapterProgress> = emptyMap(),
    /** The user's own exam (Exams screen) that matches each syllabus exam, by syllabus exam id. */
    val examDates: Map<String, Exam> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
) {
    fun progressOf(subject: String, chapter: String): ChapterProgress {
        val key = SyllabusKeys.chapterKey(subject, chapter)
        return progress[key] ?: ChapterProgress.empty(key)
    }

    fun examDate(examId: String): LocalDate? = examDates[examId]?.date
}

/**
 * 1.8.0 syllabus tracker, shared by the Syllabus tab of Tasks and the subject screen. The chosen tab and
 * exam are remembered on the device.
 */
@HiltViewModel
class SyllabusViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repo: SyllabusRepository,
    exams: ExamRepository,
    todayProvider: TodayProvider,
    private val prefill: FocusPrefill,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("syllabus_ui", Context.MODE_PRIVATE)

    private val _tab = MutableStateFlow(prefs.getInt(KEY_TAB, TAB_TASKS))
    /** 0 = Tasks, 1 = Syllabus. */
    val tab: StateFlow<Int> = _tab.asStateFlow()

    private val _examId = MutableStateFlow(prefs.getString(KEY_EXAM, null) ?: DEFAULT_EXAM)
    val examId: StateFlow<String> = _examId.asStateFlow()

    val ui: StateFlow<SyllabusUi> = combine(repo.syllabus, repo.progress, exams.exams, todayProvider.today) { syl, progress, userExams, today ->
        SyllabusUi(true, syl, progress, matchExams(syl, userExams, today), today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyllabusUi())

    fun setTab(tab: Int) {
        _tab.value = tab
        prefs.edit().putInt(KEY_TAB, tab).apply()
    }

    fun selectExam(id: String) {
        _examId.value = id
        prefs.edit().putString(KEY_EXAM, id).apply()
    }

    fun toggle(subject: String, chapter: String, step: SyllabusStep) = edit(subject, chapter) { ChapterEdits.toggle(it, step, System.currentTimeMillis()) }
    fun setPyqSets(subject: String, chapter: String, n: Int) = edit(subject, chapter) { ChapterEdits.setPyqSets(it, n, System.currentTimeMillis()) }
    fun setPyqTarget(subject: String, chapter: String, n: Int) = edit(subject, chapter) { ChapterEdits.setPyqTarget(it, n, System.currentTimeMillis()) }
    fun setMocks(subject: String, chapter: String, n: Int) = edit(subject, chapter) { ChapterEdits.setMocks(it, n, System.currentTimeMillis()) }
    fun setNote(subject: String, chapter: String, note: String) = edit(subject, chapter) { it.copy(note = note.trim()) }

    /** "Focus on this": the Focus screen opens with the chapter filled in as the session's subject. */
    fun focusOn(chapter: String) { prefill.subject.value = chapter }

    private fun edit(subject: String, chapter: String, change: (ChapterProgress) -> ChapterProgress) {
        viewModelScope.launch { repo.update(SyllabusKeys.chapterKey(subject, chapter), change) }
    }

    companion object {
        const val TAB_TASKS = 0
        const val TAB_SYLLABUS = 1
        const val DEFAULT_EXAM = "jee"
        private const val KEY_TAB = "tab"
        private const val KEY_EXAM = "exam"

        /** For each syllabus exam: the pinned matching upcoming exam, else the nearest one. */
        fun matchExams(syl: Syllabus, userExams: List<Exam>, today: LocalDate): Map<String, Exam> =
            syl.exams.mapNotNull { se: SyllabusExam ->
                val upcoming = userExams.filter { se.matches(it.name) && !ExamCountdown.isPast(it, today) }
                (upcoming.firstOrNull { it.pinned } ?: upcoming.minByOrNull { it.date.toEpochDay() })?.let { se.id to it }
            }.toMap()
    }
}
