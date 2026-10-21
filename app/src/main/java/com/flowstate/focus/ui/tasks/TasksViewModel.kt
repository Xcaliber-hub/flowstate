package com.flowstate.focus.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.db.TaskEntity
import com.flowstate.focus.data.repo.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

val TaskCategories = listOf("Study", "Work", "Personal", "Health")
val PriorityLabels = listOf("Low", "Medium", "High")

@HiltViewModel
class TasksViewModel @Inject constructor(private val repo: TaskRepository) : ViewModel() {

    val tasks: StateFlow<List<TaskEntity>> =
        repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [dueInDays] of null means no deadline; 0 is today. Deadlines land at the end of the chosen day. */
    fun add(title: String, category: String, priority: Int, dueInDays: Int?) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val deadline = dueInDays?.let {
            LocalDate.now().plusDays(it.toLong()).atTime(23, 59)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        viewModelScope.launch {
            repo.add(TaskEntity(title = clean, category = category, priority = priority, deadline = deadline, createdAt = System.currentTimeMillis()))
        }
    }

    fun toggle(task: TaskEntity) = viewModelScope.launch { repo.update(task.copy(done = !task.done)) }
    fun delete(task: TaskEntity) = viewModelScope.launch { repo.delete(task) }
    fun clearDone() = viewModelScope.launch { repo.clearDone() }
}
