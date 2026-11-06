package com.flowstate.focus.ui.exams

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.focus.domain.EXAM_NAME_MAX
import com.flowstate.focus.domain.Exam
import com.flowstate.focus.domain.ExamCountdown
import com.flowstate.focus.domain.ExamEmojis
import com.flowstate.focus.domain.ExamTags
import com.flowstate.focus.ui.ambient.CheckSwitch
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.CookieBadge
import com.flowstate.focus.ui.components.DepthSurface
import com.flowstate.focus.ui.components.SectionLabel
import kotlinx.coroutines.launch
import java.time.LocalDate

fun tagColor(i: Int) = Color(ExamTags[i.coerceIn(0, ExamTags.lastIndex)])

/** "D-76" etc. for display. */
fun Exam.dLabel(today: LocalDate) = ExamCountdown.dLabel(this, today)

@Composable
fun ExamEmojiBadge(exam: Exam, size: androidx.compose.ui.unit.Dp, fontSize: Int) {
    CookieBadge(size, MaterialTheme.colorScheme.surfaceContainerHigh, border = tagColor(exam.tag)) {
        Text(exam.emoji, fontSize = fontSize.sp)
    }
}

/**
 * The D-Day card on Focus. Swipe or tap to step through the exams shown on Focus (pinned first,
 * then nearest); the chevron opens the picker. With nothing to show it invites adding an exam.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DDayCard(exams: List<Exam>, today: LocalDate, onOpenPicker: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shown = remember(exams, today) { ExamCountdown.forFocus(exams, today) }
    DepthSurface(shape = RoundedCornerShape(30.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.height(64.dp).padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shown.isEmpty()) {
                Row(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = if (exams.isEmpty()) onAdd else onOpenPicker),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CookieBadge(44.dp, cs.surfaceContainerHigh, border = cs.secondary) {
                        Icon(if (exams.isEmpty()) Icons.Rounded.Add else Icons.Rounded.Event, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        AutoSizeText(if (exams.isEmpty()) "Add an exam" else "No countdown shown", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            if (exams.isEmpty()) "Count down the days to it here" else "Pick exams to show on Focus",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                val pager = rememberPagerState { shown.size }
                val scope = rememberCoroutineScope()
                LaunchedEffect(shown.size) { if (pager.currentPage >= shown.size) pager.scrollToPage(0) }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
                        val e = shown[page]
                        Row(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).clickable(
                                role = Role.Button,
                                onClickLabel = if (shown.size > 1) "Next exam" else "Exam options",
                            ) {
                                if (shown.size > 1) scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % shown.size) } else onOpenPicker()
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ExamEmojiBadge(e, 44.dp, 20)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        e.dLabel(today),
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
                                        maxLines = 1, softWrap = false,
                                    )
                                    Text(" · ", color = cs.outline, maxLines = 1, softWrap = false)
                                    Text(e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    ExamCountdown.shortDate(e.date, today),
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false,
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                            }
                        }
                    }
                    if (shown.size > 1) {
                        Row(
                            Modifier.align(Alignment.BottomEnd).padding(bottom = 12.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(shown.size.coerceAtMost(6)) { i ->
                                val on = i == pager.currentPage.coerceAtMost(5)
                                Box(Modifier.size(width = if (on) 14.dp else 5.dp, height = 5.dp).clip(CircleShape).background(if (on) cs.secondary else cs.outlineVariant))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(cs.background.copy(alpha = 0.55f))
                    .clickable(role = Role.Button, onClick = onOpenPicker)
                    .semantics { contentDescription = "Choose exams to show" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** Material date picker for an exam date. The picker works in UTC midnights. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamDatePicker(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val start = (initial ?: LocalDate.now()).toEpochDay() * 86_400_000L
    val state = rememberDatePickerState(initialSelectedDateMillis = start)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L))) }
                onDismiss()
            }, enabled = state.selectedDateMillis != null) { Text("Set date") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state, showModeToggle = true) }
}

/**
 * The picker behind the card's chevron: tick which exams show on Focus, set each date, pin one as
 * main, rename or delete (through the edit sheet), add a new one, or open the Exams screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamPickerSheet(
    exams: List<Exam>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onShown: (Exam, Boolean) -> Unit,
    onDate: (Exam, LocalDate) -> Unit,
    onPin: (Exam) -> Unit,
    onEdit: (Exam) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var dateFor by remember { mutableStateOf<Exam?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainer.copy(alpha = 0.97f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AutoSizeText("Countdowns", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold))
                    Text("Tick the exams to show on Focus", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                CloseButton(onDismiss)
            }
            Spacer(Modifier.height(12.dp))
            if (exams.isEmpty()) {
                Text(
                    "No exams yet. Add one and Focus will count down the days to it.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                val sorted = remember(exams, today) {
                    exams.sortedWith(compareBy<Exam> { ExamCountdown.isPast(it, today) }.thenByDescending { it.pinned }.thenBy { it.date })
                }
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sorted, key = { it.id }) { e ->
                        val past = ExamCountdown.isPast(e, today)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(cs.surfaceContainerHigh.copy(alpha = 0.6f))
                                .padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = e.showOnFocus && !past,
                                enabled = !past,
                                onCheckedChange = { onShown(e, it) },
                                colors = CheckboxDefaults.colors(checkedColor = cs.secondary, checkmarkColor = cs.onSecondary),
                                modifier = Modifier.semantics { contentDescription = "Show ${e.name} on Focus" },
                            )
                            ExamEmojiBadge(e, 40.dp, 18)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(
                                    Modifier
                                        .padding(top = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(role = Role.Button, onClickLabel = "Change date") { dateFor = e }
                                        .padding(vertical = 2.dp, horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Event, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "${ExamCountdown.longDate(e.date)} · ${e.dLabel(today)}",
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = { onEdit(e) }) { Icon(Icons.Rounded.Edit, contentDescription = "Rename or delete ${e.name}", tint = cs.onSurfaceVariant) }
                            PinButton(e.pinned, enabled = !past, onClick = { onPin(e) })
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onManage, modifier = Modifier.weight(1f).height(52.dp)) { AutoSizeText("All exams") }
                Button(
                    onClick = onAdd,
                    modifier = Modifier.weight(1.3f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    AutoSizeText("Add exam")
                }
            }
        }
    }
    dateFor?.let { e -> ExamDatePicker(e.date, onDismiss = { dateFor = null }, onPick = { onDate(e, it) }) }
}

@Composable
fun CloseButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(cs.surfaceContainerHigh).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Close, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
}

/** Pinned: a squarer filled button. Not pinned: an outlined circle. */
@Composable
fun PinButton(pinned: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = if (pinned) RoundedCornerShape(14.dp) else CircleShape
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .then(if (pinned) Modifier.background(cs.secondary) else Modifier.border(1.dp, cs.outlineVariant.copy(alpha = 0.7f), shape))
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = if (pinned) "Unpin main exam" else "Pin as main exam" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, contentDescription = null,
            tint = if (pinned) cs.onSecondary else cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(18.dp),
        )
    }
}

/** Add or edit an exam: name, date, emoji, tag, show on Focus, pin. Editing also offers delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamEditSheet(exam: Exam?, today: LocalDate, onDismiss: () -> Unit, onSave: (Exam) -> Unit, onDelete: (Exam) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by rememberSaveable { mutableStateOf(exam?.name ?: "") }
    var epochDay by rememberSaveable { mutableStateOf(exam?.date?.toEpochDay()) }
    var emoji by rememberSaveable { mutableStateOf(exam?.emoji ?: ExamEmojis.first()) }
    var tag by rememberSaveable { mutableIntStateOf(exam?.tag ?: 2) }
    var shown by rememberSaveable { mutableStateOf(exam?.showOnFocus ?: true) }
    var pinned by rememberSaveable { mutableStateOf(exam?.pinned ?: false) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val date = epochDay?.let(LocalDate::ofEpochDay)
    val canSave = name.isNotBlank() && date != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainer.copy(alpha = 0.97f),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AutoSizeText(
                    if (exam == null) "Add exam" else "Edit exam",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    modifier = Modifier.weight(1f),
                )
                CloseButton(onDismiss)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(EXAM_NAME_MAX) },
                label = { Text("Exam name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Box(Modifier.padding(top = 12.dp)) {
                OutlinedTextField(
                    value = date?.let(ExamCountdown::longDate) ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date") },
                    placeholder = { Text("Pick a date") },
                    trailingIcon = { Icon(Icons.Rounded.Event, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClickLabel = "Pick a date") { picking = true })
            }
            if (date != null) {
                val d = java.time.temporal.ChronoUnit.DAYS.between(today, date)
                Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Flag, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    val label = when {
                        d > 1 -> "D-$d · $d days from today"
                        d == 1L -> "D-1 · tomorrow"
                        d == 0L -> "D-Day · today"
                        else -> "Already passed · shown as Done"
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            SectionLabel("Emoji", Modifier.padding(top = 18.dp))
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExamEmojis.forEach { e ->
                    val on = e == emoji
                    CookieBadge(
                        44.dp, if (on) cs.secondary else cs.surfaceContainerHigh,
                        border = if (on) null else cs.outlineVariant.copy(alpha = 0.6f),
                        modifier = Modifier.clickable(role = Role.RadioButton) { emoji = e },
                    ) { Text(e, fontSize = 20.sp) }
                }
            }

            SectionLabel("Tag", Modifier.padding(top = 16.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExamTags.indices.forEach { i ->
                    val on = i == tag
                    val shape = if (on) RoundedCornerShape(12.dp) else CircleShape
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(shape)
                            .background(tagColor(i))
                            .border(if (on) 3.dp else 1.dp, if (on) cs.onSurface else cs.outlineVariant.copy(alpha = 0.6f), shape)
                            .clickable(role = Role.RadioButton) { tag = i }
                            .semantics { contentDescription = "Tag colour ${i + 1}" },
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFF06141B), modifier = Modifier.size(16.dp)) }
                }
            }

            ToggleLine("Show on Focus", "Swipe to it from the countdown", shown || pinned) { shown = it; if (!it) pinned = false }
            ToggleLine("Pin as main exam", "Shown first instead of the nearest", pinned) { pinned = it; if (it) shown = true }

            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(56.dp)) { AutoSizeText("Cancel") }
                Button(
                    onClick = {
                        val d = date ?: return@Button
                        val base = exam ?: Exam(name = name, date = d)
                        onSave(base.copy(name = name.trim(), date = d, emoji = emoji, tag = tag, showOnFocus = shown || pinned, pinned = pinned))
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1.4f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary),
                ) {
                    Icon(if (exam == null) Icons.Rounded.Add else Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    AutoSizeText(if (exam == null) "Save exam" else "Save changes")
                }
            }
            if (exam != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)) {
                    Text("Delete exam", color = cs.error)
                }
            }
        }
    }
    if (picking) ExamDatePicker(date, onDismiss = { picking = false }, onPick = { epochDay = it.toEpochDay() })
    if (confirmDelete && exam != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${exam.name}?") },
            text = { Text("Its countdown is removed from Focus. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(exam) }) { Text("Delete", color = cs.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleLine(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            AutoSizeText(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        CheckSwitch(checked, onChange)
    }
}
