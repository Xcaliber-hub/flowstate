package com.flowstate.focus.ui.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.data.db.TaskEntity
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.ui.components.Entrance
import com.flowstate.focus.ui.components.GlassCard
import com.flowstate.focus.ui.components.ScreenContainer
import com.flowstate.focus.ui.components.screenContentPadding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(settings: AppSettings, vm: TasksViewModel = hiltViewModel()) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showSheet by rememberSaveable { mutableStateOf(false) }

    val visible = remember(tasks, filter) { tasks.filter { filter == null || it.category == filter } }
    val doneCount = visible.count { it.done }

    Box(Modifier.fillMaxSize()) {
        ScreenContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = screenContentPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    Entrance(0) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Tasks", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                                if (tasks.any { it.done }) TextButton(onClick = vm::clearDone) { Text("Clear done") }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                                TaskCategories.forEach { c ->
                                    FilterChip(selected = filter == c, onClick = { filter = c }, label = { Text(c) })
                                }
                            }
                        }
                    }
                }
                item(key = "progress") {
                    Entrance(1) {
                        GlassCard(Modifier.fillMaxWidth(), settings.liquidUi) {
                            Text(
                                if (visible.isEmpty()) "Nothing planned" else "$doneCount of ${visible.size} done",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                            val target = if (visible.isEmpty()) 0f else doneCount / visible.size.toFloat()
                            val animated by animateFloatAsState(target, label = "taskProgress")
                            LinearProgressIndicator(
                                progress = { animated },
                                modifier = Modifier.fillMaxWidth().height(10.dp),
                                strokeCap = StrokeCap.Round,
                            )
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Add the one thing you want to finish today.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(visible, key = { it.id }) { task ->
                    TaskRow(task, onToggle = { vm.toggle(task) }, onDelete = { vm.delete(task) }, modifier = Modifier.animateItem())
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { showSheet = true },
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("New task") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 104.dp),
        )
    }

    if (showSheet) {
        AddTaskSheet(
            onDismiss = { showSheet = false },
            onAdd = { title, category, priority, due ->
                vm.add(title, category, priority, due)
                showSheet = false
            },
        )
    }
}

@Composable
private fun TaskRow(task: TaskEntity, onToggle: () -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val priorityColor = when (task.priority) {
        2 -> cs.error
        1 -> cs.tertiary
        else -> cs.outline
    }
    val overdue = !task.done && task.deadline != null && task.deadline < System.currentTimeMillis()
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.done, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.done) cs.onSurfaceVariant else cs.onSurface,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(priorityColor))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append(PriorityLabels.getOrElse(task.priority) { "Low" }).append(" · ").append(task.category)
                            task.deadline?.let { append(" · ").append(dueLabel(it)) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) cs.error else cs.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete ${task.title}") }
        }
    }
}

private fun dueLabel(deadlineMs: Long): String {
    val date = Instant.ofEpochMilli(deadlineMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "Due today"
        date == today.plusDays(1) -> "Due tomorrow"
        date.isBefore(today) -> "Overdue"
        else -> "Due " + date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskSheet(onDismiss: () -> Unit, onAdd: (String, String, Int, Int?) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(TaskCategories.first()) }
    var priority by rememberSaveable { mutableIntStateOf(1) }
    var due by rememberSaveable { mutableStateOf<Int?>(null) }
    val dueOptions = listOf<Pair<String, Int?>>("No date" to null, "Today" to 0, "Tomorrow" to 1, "In a week" to 7)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("New task", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(120) },
                label = { Text("What needs doing?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ChipRow("Category", TaskCategories.map { it to (it == category) }) { category = TaskCategories[it] }
            ChipRow("Priority", PriorityLabels.mapIndexed { i, l -> l to (i == priority) }) { priority = it }
            ChipRow("Due", dueOptions.map { it.first to (it.second == due) }) { due = dueOptions[it].second }
            Button(onClick = { onAdd(title, category, priority, due) }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Add task")
            }
        }
    }
}

@Composable
private fun ChipRow(title: String, options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, (label, selected) ->
                FilterChip(selected = selected, onClick = { onSelect(index) }, label = { Text(label) })
            }
        }
    }
}
