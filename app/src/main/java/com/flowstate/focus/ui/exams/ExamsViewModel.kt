package com.flowstate.focus.ui.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.exams.ExamRepository
import com.flowstate.focus.domain.Exam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExamsUi(val loaded: Boolean = false, val exams: List<Exam> = emptyList())

/** Exam countdowns, shared by the Focus card, its picker sheet and the Exams screen. */
@HiltViewModel
class ExamsViewModel @Inject constructor(private val repo: ExamRepository) : ViewModel() {
    val ui: StateFlow<ExamsUi> = repo.exams.map { ExamsUi(true, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExamsUi())

    fun save(exam: Exam) = viewModelScope.launch { repo.save(exam) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun setShown(id: Long, shown: Boolean) = viewModelScope.launch { repo.setShown(id, shown) }
    fun togglePin(exam: Exam) = viewModelScope.launch { if (exam.pinned) repo.unpin() else repo.pin(exam.id) }
}
