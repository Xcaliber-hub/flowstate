package com.flowstate.focus.ui.syllabus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.focus.data.syllabus.NOTE_MAX
import com.flowstate.focus.domain.ChapterEdits
import com.flowstate.focus.domain.ChapterProgress
import com.flowstate.focus.domain.RevisionPlan
import com.flowstate.focus.domain.SyllabusExam
import com.flowstate.focus.domain.SyllabusMath
import com.flowstate.focus.domain.SyllabusStep
import com.flowstate.focus.domain.SyllabusSubject
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.SectionLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun dayMonth(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

private fun weekdayDayMonth(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

/** The line under a step: when it was ticked, or when it is suggested. */
internal fun stepSubtitle(p: ChapterProgress, step: SyllabusStep, examDate: LocalDate?): String {
    p.ticks[step]?.let { at ->
        return when (step) {
            SyllabusStep.DONE -> "Finished ${dayMonth(at)}"
            SyllabusStep.PYQ -> "Sets solved · ticks at ${p.pyqTarget}"
            SyllabusStep.MOCK -> "Chapter tests taken"
            else -> "Done ${dayMonth(at)}"
        }
    }
    return when (step) {
        SyllabusStep.DONE -> "Not started"
        SyllabusStep.PYQ -> "Sets solved · ticks at ${p.pyqTarget}"
        SyllabusStep.MOCK -> "Chapter tests taken"
        else -> {
            val by = RevisionPlan.suggestedBy(p, step, examDate)
            when {
                by != null -> "Suggested by ${weekdayDayMonth(by)}"
                step == SyllabusStep.R4 && examDate != null -> "Before the exam"
                step == SyllabusStep.R1 -> "After the chapter"
                else -> "After ${RevisionPlan.previousOf(step)?.full}"
            }
        }
    }
}

/** Chapter details: the seven-step checklist with dates, counters, note and "Focus on this". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSheet(
    ui: SyllabusUi,
    exam: SyllabusExam,
    subject: SyllabusSubject,
    chapter: String,
    number: Int,
    vm: SyllabusViewModel,
    onDismiss: () -> Unit,
    onFocus: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val p = ui.progressOf(subject.name, chapter)
    val examDate = ui.examDate(exam.id)
    val also = ui.syllabus.alsoIn(exam.id, subject.name, chapter)
    var editingNote by rememberSaveable { mutableStateOf(false) }
    var editingTarget by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainer.copy(alpha = 0.97f),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("${exam.chip} · ${subject.name} · ${"%02d".format(number)}")
                    // Wraps between words only (a single word never exceeds the sheet width here).
                    Text(
                        chapter,
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp),
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                SyllabusRing(p.fraction, 56.dp, 6.dp) {
                    AutoSizeText("${SyllabusMath.percent(p.fraction)}%", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold), minSize = 9.sp)
                }
            }
            SyllabusBar(p.fraction, Modifier.padding(top = 12.dp), height = 8.dp)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.doneSteps} of ${SyllabusStep.COUNT} steps", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(8.dp).weight(1f))
                if (also.isNotEmpty()) {
                    AutoSizeText("Also in " + also.joinToString(" · ") { it.chip }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, minSize = 9.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val next = p.nextStep
            SyllabusStep.entries.forEach { step ->
                val on = p.isDone(step)
                val isNext = step == next
                CheckLine(
                    title = step.full,
                    subtitle = stepSubtitle(p, step, examDate),
                    on = on, next = isNext,
                    onToggle = { vm.toggle(subject.name, chapter, step) },
                ) {
                    when (step) {
                        SyllabusStep.PYQ -> Stepper(
                            value = "${p.pyqSets} / ${p.pyqTarget}",
                            onMinus = { vm.setPyqSets(subject.name, chapter, p.pyqSets - 1) },
                            onPlus = { vm.setPyqSets(subject.name, chapter, p.pyqSets + 1) },
                            onValueClick = { editingTarget = true },
                            valueLabel = "PYQ target, tap to change",
                        )
                        SyllabusStep.MOCK -> Stepper(
                            value = "${p.mocks}",
                            onMinus = { vm.setMocks(subject.name, chapter, p.mocks - 1) },
                            onPlus = { vm.setMocks(subject.name, chapter, p.mocks + 1) },
                        )
                        else -> if (isNext) {
                            Button(
                                onClick = { vm.toggle(subject.name, chapter, step) },
                                colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary),
                                modifier = Modifier.height(40.dp),
                            ) { AutoSizeText("Mark", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)) }
                        }
                    }
                }
            }
            if (p.note.isNotBlank()) {
                Text(
                    p.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editingNote = true }.padding(vertical = 4.dp),
                )
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { editingNote = true }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    AutoSizeText("Note", style = MaterialTheme.typography.titleSmall)
                }
                Button(
                    onClick = onFocus,
                    colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary),
                    modifier = Modifier.weight(1.7f).height(56.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    AutoSizeText("Focus on this", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }

    if (editingNote) {
        var text by rememberSaveable { mutableStateOf(p.note) }
        AlertDialog(
            onDismissRequest = { editingNote = false },
            title = { Text("Note") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(NOTE_MAX) },
                    placeholder = { Text("Weak spots, formulas, page numbers…") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { vm.setNote(subject.name, chapter, text); editingNote = false }) { AutoSizeText("Save") } },
            dismissButton = { TextButton(onClick = { editingNote = false }) { AutoSizeText("Cancel") } },
        )
    }
    if (editingTarget) {
        var text by rememberSaveable { mutableStateOf(p.pyqTarget.toString()) }
        val value = text.toIntOrNull()
        AlertDialog(
            onDismissRequest = { editingTarget = false },
            title = { Text("PYQ sets target") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { s -> text = s.filter(Char::isDigit).take(2) },
                    label = { Text("PYQs tick when this many sets are solved") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = value != null && value in 1..ChapterEdits.MAX_COUNT, onClick = {
                    value?.let { vm.setPyqTarget(subject.name, chapter, it) }; editingTarget = false
                }) { AutoSizeText("Save") }
            },
            dismissButton = { TextButton(onClick = { editingTarget = false }) { AutoSizeText("Cancel") } },
        )
    }
}

@Composable
private fun CheckLine(
    title: String,
    subtitle: String,
    on: Boolean,
    next: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val box = Modifier.size(40.dp)
        when {
            on -> Box(
                box.clip(RoundedCornerShape(14.dp)).background(cs.secondary).clickable(role = Role.Checkbox, onClickLabel = "Untick $title", onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, contentDescription = "$title done", tint = cs.onSecondary, modifier = Modifier.size(20.dp)) }
            next -> Box(box.clip(CircleShape).dashedOutline(cs.primary, 20.dp, 2.dp).clickable(role = Role.Checkbox, onClickLabel = "Tick $title", onClick = onToggle))
            else -> Box(box.clip(CircleShape).border(1.5.dp, cs.outlineVariant, CircleShape).clickable(role = Role.Checkbox, onClickLabel = "Tick $title", onClick = onToggle))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AutoSizeText(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = if (on || next) FontWeight.Bold else FontWeight.Medium),
                color = if (on || next) cs.onSurface else cs.onSurfaceVariant,
            )
            AutoSizeText(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp), minSize = 9.sp)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, onValueClick: (() -> Unit)? = null, valueLabel: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp, topEnd = 6.dp, bottomEnd = 6.dp))
                .background(cs.surfaceContainerHigh).clickable(onClickLabel = "Less", onClick = onMinus),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Remove, contentDescription = "Less", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
        Box(
            Modifier.height(36.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHigh)
                .then(if (onValueClick != null) Modifier.clickable(onClickLabel = valueLabel, onClick = onValueClick) else Modifier)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text(value, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false) }
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp))
                .background(cs.surfaceContainerHigh).clickable(onClickLabel = "More", onClick = onPlus),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Add, contentDescription = "More", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
    }
}
