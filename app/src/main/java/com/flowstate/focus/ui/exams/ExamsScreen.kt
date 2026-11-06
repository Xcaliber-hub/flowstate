package com.flowstate.focus.ui.exams

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.domain.Exam
import com.flowstate.focus.domain.ExamCountdown
import com.flowstate.focus.ui.components.Cookie10
import com.flowstate.focus.ui.components.CookieBadge
import com.flowstate.focus.ui.components.DepthSurface
import com.flowstate.focus.ui.components.LocalNavLayout
import com.flowstate.focus.ui.components.NavLayout
import com.flowstate.focus.ui.components.ScreenContainer
import com.flowstate.focus.ui.components.ScreenTopBar
import com.flowstate.focus.ui.components.SectionLabel
import com.flowstate.focus.ui.components.TopBarMenuItem
import com.flowstate.focus.ui.components.TopBarSpec
import com.flowstate.focus.ui.components.WavyProgress
import com.flowstate.focus.ui.components.screenContentPadding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which exam sheet is open. Shared by the Focus card and the Exams screen. */
@Stable
class ExamSheets {
    var picker by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var editing by mutableStateOf<Exam?>(null)
}

@Composable
fun ExamSheetsHost(sheets: ExamSheets, exams: List<Exam>, today: LocalDate, vm: ExamsViewModel, onManage: (() -> Unit)?) {
    if (sheets.picker) {
        ExamPickerSheet(
            exams = exams,
            today = today,
            onDismiss = { sheets.picker = false },
            onShown = { e, on -> vm.setShown(e.id, on) },
            onDate = { e, d -> vm.save(e.copy(date = d)) },
            onPin = vm::togglePin,
            onEdit = { sheets.editing = it },
            onAdd = { sheets.adding = true },
            onManage = { sheets.picker = false; onManage?.invoke() },
        )
    }
    if (sheets.adding || sheets.editing != null) {
        val editing = sheets.editing
        ExamEditSheet(
            exam = editing,
            today = today,
            onDismiss = { sheets.adding = false; sheets.editing = null },
            onSave = { vm.save(it); sheets.adding = false; sheets.editing = null },
            onDelete = { vm.delete(it.id); sheets.adding = false; sheets.editing = null },
        )
    }
}

private val AddedFormat = DateTimeFormatter.ofPattern("d MMM")

/** All exams: the featured countdown, upcoming exams by date, and (optionally) finished ones. */
@Composable
fun ExamsScreen(vm: ExamsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val sheets = remember { ExamSheets() }
    var showDone by rememberSaveable { mutableStateOf(true) }
    val main = ExamCountdown.main(ui.exams, today)
    val upcoming = ui.exams.filter { !ExamCountdown.isPast(it, today) && it.id != main?.id }.sortedBy { it.date }
    val done = ui.exams.filter { ExamCountdown.isPast(it, today) }.sortedByDescending { it.date }
    val count = ui.exams.size
    ScreenTopBar(
        TopBarSpec(
            key = "exams",
            title = "Exams",
            subtitle = when {
                count == 0 -> "No exams yet"
                main != null -> "${if (count == 1) "1 exam" else "$count exams"} · next ${main.dLabel(today)}"
                else -> "${if (count == 1) "1 exam" else "$count exams"} · all done"
            },
            progress = main?.let { ExamCountdown.progress(it, today, addedOn(it)) },
            menuTitle = "Exams",
            menu = listOf(
                TopBarMenuItem("Add exam", "Name, date and emoji", selected = false, enabled = true) { sheets.adding = true },
                TopBarMenuItem("Show finished exams", "Past exams, marked Done", selected = showDone, enabled = true) { showDone = !showDone },
            ),
        ),
    )
    val side = LocalNavLayout.current == NavLayout.SIDE
    val base = screenContentPadding()
    val dir = LocalLayoutDirection.current
    val padding = PaddingValues(
        start = base.calculateStartPadding(dir), end = base.calculateEndPadding(dir),
        top = base.calculateTopPadding(), bottom = base.calculateBottomPadding() + 88.dp,
    )
    ScreenContainer {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!ui.loaded) return@LazyColumn
                if (count == 0) {
                    item(key = "empty") { EmptyExams(onAdd = { sheets.adding = true }) }
                }
                main?.let { m -> item(key = "hero") { HeroExam(m, today, onClick = { sheets.editing = m }) } }
                if (upcoming.isNotEmpty()) {
                    item(key = "upHeader") {
                        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp)) {
                            SectionLabel("Upcoming")
                            Spacer(Modifier.weight(1f))
                            Text("Sorted by date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                    }
                    items(upcoming, key = { it.id }) { e -> ExamRow(e, today, onClick = { sheets.editing = e }, onPin = { vm.togglePin(e) }) }
                }
                if (showDone && done.isNotEmpty()) {
                    item(key = "doneHeader") { SectionLabel("Done", Modifier.padding(start = 4.dp, top = 6.dp)) }
                    items(done, key = { it.id }) { e -> ExamRow(e, today, onClick = { sheets.editing = e }, onPin = null) }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { sheets.adding = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add exam", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                shape = RoundedCornerShape(22.dp),
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = if (side) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp else 112.dp)
                    .height(64.dp),
            )
        }
    }
    ExamSheetsHost(sheets, ui.exams, today, vm, onManage = null)
}

private fun addedOn(e: Exam): LocalDate = Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
internal fun EmptyExams(onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    DepthSurface(shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CookieBadge(80.dp, cs.surfaceContainerHigh, shape = Cookie10, border = cs.secondary) {
                Icon(Icons.Rounded.Event, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(34.dp))
            }
            Text("No exams yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Text(
                "Add the exams you're preparing for and Focus will count down the days.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = onAdd, modifier = Modifier.padding(top = 16.dp).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add exam", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
internal fun HeroExam(e: Exam, today: LocalDate, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val days = ExamCountdown.daysLeft(e, today)
    val added = addedOn(e)
    DepthSurface(shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).clickable(onClick = onClick)) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CookieBadge(92.dp, cs.secondary, shape = Cookie10) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("D-DAY", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSecondary.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
                        Text(
                            if (days == 0L) "0" else "$days",
                            fontSize = if (days >= 1000) 26.sp else 34.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSecondary,
                            maxLines = 1, softWrap = false, lineHeight = 36.sp,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PushPin, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        SectionLabel(if (e.pinned) "Pinned on Focus" else "Next up")
                    }
                    Text(e.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    Text("${ExamCountdown.longDate(e.date)} · ${e.emoji}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
            WavyProgress(
                progress = { ExamCountdown.progress(e, today, added) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                strokeWidth = 6.dp, amplitude = 3.dp, wavelength = 15.dp,
            )
            Row(Modifier.padding(top = 7.dp)) {
                Text("Added ${added.format(AddedFormat)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                Spacer(Modifier.weight(1f))
                Text(ExamCountdown.timeLeft(e, today), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
internal fun ExamRow(e: Exam, today: LocalDate, onClick: () -> Unit, onPin: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val days = ExamCountdown.daysLeft(e, today)
    DepthSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ExamEmojiBadge(e, 48.dp, 22)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!e.showOnFocus && days >= 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Hidden", fontSize = 10.5.sp, color = cs.outline, maxLines = 1, softWrap = false,
                            modifier = Modifier.border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(ExamCountdown.longDate(e.date), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(if (days >= 0) "$days" else "Done", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false, lineHeight = 22.sp)
                if (days >= 0) Text(if (days == 1L) "day" else "days", fontSize = 10.5.sp, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            if (onPin != null) {
                Spacer(Modifier.width(10.dp))
                PinButton(e.pinned, onClick = onPin)
            }
        }
    }
}
