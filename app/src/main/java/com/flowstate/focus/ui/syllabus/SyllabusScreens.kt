package com.flowstate.focus.ui.syllabus

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.domain.ChapterProgress
import com.flowstate.focus.domain.ExamCountdown
import com.flowstate.focus.domain.SyllabusExam
import com.flowstate.focus.domain.SyllabusMath
import com.flowstate.focus.domain.SyllabusStep
import com.flowstate.focus.domain.SyllabusSubject
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.Cookie10
import com.flowstate.focus.ui.components.CookieBadge
import com.flowstate.focus.ui.components.DepthSurface
import com.flowstate.focus.ui.components.Entrance
import com.flowstate.focus.ui.components.ExpressiveChip
import com.flowstate.focus.ui.components.GlassCard
import com.flowstate.focus.ui.components.ScreenContainer
import com.flowstate.focus.ui.components.ScreenTopBar
import com.flowstate.focus.ui.components.SectionLabel
import com.flowstate.focus.ui.components.TopBarMenuItem
import com.flowstate.focus.ui.components.TopBarSpec
import com.flowstate.focus.ui.components.WavyProgress
import com.flowstate.focus.ui.components.screenContentPadding

/* 1.8.0 syllabus tracker screens, after the approved mockups (flowstate-1.8.0-*.png). */

/** The exam chips: JEE Main / NEET / IAT. */
@Composable
fun SyllabusExamChips(exams: List<SyllabusExam>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        exams.forEach { e ->
            val on = e.id == selected
            ExpressiveChip(on, e.chip, onClick = { onSelect(e.id) }, icon = if (on) Icons.Rounded.Check else null, modifier = Modifier.height(36.dp))
        }
    }
}

/** Overall card and one card per subject, for the Syllabus tab of Tasks. */
fun LazyListScope.syllabusOverviewItems(
    ui: SyllabusUi,
    exam: SyllabusExam?,
    liquid: Boolean,
    onSelectExam: (String) -> Unit,
    onOpenSubject: (String) -> Unit,
) {
    item(key = "syl-exams") { SyllabusExamChips(ui.syllabus.exams, exam?.id.orEmpty(), onSelectExam) }
    if (exam == null) {
        if (ui.loaded) item(key = "syl-missing") {
            Text("The chapter lists could not be loaded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val overall = SyllabusMath.stats(exam.subjects.flatMap { s -> s.chapters.map { ui.progressOf(s.name, it) } })
    item(key = "syl-overall-${exam.id}") { Entrance(1) { OverallCard(ui, exam, overall.fraction, overall.done, overall.chapters, overall.revisions, liquid) } }
    exam.subjects.forEachIndexed { i, subject ->
        item(key = "syl-${exam.id}-${subject.name}") {
            Entrance(2 + i) { SubjectCard(ui, exam, subject, liquid) { onOpenSubject(subject.name) } }
        }
    }
    item(key = "syl-footer") {
        Text(
            "Chapters use NCERT names, mapped from the official units. Bars show how many chapters reached each step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun OverallCard(ui: SyllabusUi, exam: SyllabusExam, fraction: Float, done: Int, total: Int, revisions: Int, liquid: Boolean) {
    val cs = MaterialTheme.colorScheme
    val userExam = ui.examDates[exam.id]
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CookieBadge(78.dp, cs.secondary, shape = Cookie10) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AutoSizeText("${SyllabusMath.percent(fraction)}%", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Black), color = cs.onSecondary, minSize = 14.sp)
                    Text("OVERALL", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold), color = cs.onSecondary.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                SectionLabel(exam.title)
                AutoSizeText("$done of $total done", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold), modifier = Modifier.padding(top = 4.dp), minSize = 13.sp)
                val revText = if (revisions == 1) "1 revision logged" else "$revisions revisions logged"
                val d = userExam?.let { ExamCountdown.dLabel(it, ui.today) }
                AutoSizeText(if (d != null) "$d · $revText" else revText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp), minSize = 9.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        WavyProgress({ fraction }, Modifier.fillMaxWidth(), color = cs.secondary, track = cs.surfaceVariant, strokeWidth = 6.dp, amplitude = 3.dp, wavelength = 15.dp)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutoSizeText(exam.source, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), minSize = 9.sp)
            Spacer(Modifier.width(8.dp))
            Text(if (exam.subjects.size == 1) "1 subject" else "${exam.subjects.size} subjects", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun SubjectCard(ui: SyllabusUi, exam: SyllabusExam, subject: SyllabusSubject, liquid: Boolean, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val progressOf: (String) -> ChapterProgress = { ui.progressOf(subject.name, it) }
    val stats = SyllabusMath.stats(subject.chapters, progressOf)
    val next = SyllabusMath.upNext(subject.chapters, progressOf, ui.examDate(exam.id))
    GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onOpen), liquid) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyllabusRing(stats.fraction, 58.dp, 7.dp) {
                AutoSizeText("${SyllabusMath.percent(stats.fraction)}%", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), minSize = 10.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                AutoSizeText(subject.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), minSize = 12.sp)
                AutoSizeText("${stats.done} of ${stats.chapters} chapters done", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp), minSize = 9.sp)
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.Top) {
                    Text("Up next", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = cs.primary, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 1.dp))
                    Spacer(Modifier.width(5.dp))
                    // Long chapter names wrap between words onto a second line rather than shrinking to nothing.
                    Text(
                        next?.let { "${it.chapter} · ${it.step.upNext}" } ?: "All done",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(cs.surface.copy(alpha = 0.55f)).border(1.dp, cs.secondary.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Open ${subject.name}", tint = cs.secondary, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SyllabusStep.entries.forEachIndexed { i, step ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    SyllabusBar(stats.stepShare[i], height = 5.dp)
                    AutoSizeText(step.short, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), color = cs.outline, modifier = Modifier.padding(top = 3.dp), minSize = 7.sp)
                }
            }
        }
    }
}

private enum class ChapterFilter(val label: String) { ALL("All"), IN_PROGRESS("In progress"), NOT_STARTED("Not started") }

/** One subject of one exam: header stats, filters and the chapter rows with their seven ticks. */
@Composable
fun SyllabusSubjectScreen(
    examId: String,
    subjectName: String,
    liquid: Boolean,
    onOpenFocus: () -> Unit,
    vm: SyllabusViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val exam = ui.syllabus.exam(examId)
    val subject = exam?.subject(subjectName)
    var filter by rememberSaveable { mutableStateOf(ChapterFilter.ALL) }
    var openChapter by rememberSaveable { mutableStateOf<String?>(null) }

    val chapters = subject?.chapters.orEmpty()
    val progressOf: (String) -> ChapterProgress = { ui.progressOf(subjectName, it) }
    val stats = SyllabusMath.stats(chapters, progressOf)

    ScreenTopBar(
        TopBarSpec(
            key = "syllabus_subject",
            title = subjectName,
            subtitle = "${exam?.chip ?: ""} · ${SyllabusMath.percent(stats.fraction)}% done",
            progress = stats.fraction,
            menuTitle = "Show",
            menu = ChapterFilter.entries.map { f -> TopBarMenuItem(f.label, selected = filter == f) { filter = f } },
        ),
    )

    val shown = remember(chapters, ui.progress, filter) {
        chapters.withIndex().filter { (_, c) ->
            val p = progressOf(c)
            when (filter) {
                ChapterFilter.ALL -> true
                ChapterFilter.IN_PROGRESS -> p.started && !p.complete
                ChapterFilter.NOT_STARTED -> !p.started
            }
        }
    }

    ScreenContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                GlassCard(Modifier.fillMaxWidth(), liquid) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SyllabusRing(stats.fraction, 84.dp, 9.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 10.dp)) {
                                AutoSizeText("${SyllabusMath.percent(stats.fraction)}%", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.Black), minSize = 12.sp)
                                AutoSizeText(subjectName.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold), color = cs.primary, minSize = 6.sp)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HeaderStat("${stats.done}/${stats.chapters}", "Done", Modifier.weight(1f))
                            HeaderStat("${stats.revisions}", "Revisions", Modifier.weight(1f))
                            HeaderStat("${stats.pyqSets}", "PYQ sets", Modifier.weight(1f))
                        }
                    }
                }
            }
            item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChapterFilter.entries.forEach { f ->
                        val count = when (f) { ChapterFilter.ALL -> stats.chapters; ChapterFilter.IN_PROGRESS -> stats.inProgress; ChapterFilter.NOT_STARTED -> stats.notStarted }
                        ExpressiveChip(filter == f, "${f.label} $count", onClick = { filter = f }, icon = if (filter == f) Icons.Rounded.Check else null)
                    }
                }
            }
            item(key = "hint") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(if (shown.size == 1) "1 chapter" else "${shown.size} chapters", Modifier.weight(1f))
                    Text("Tap a tick to log it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
            if (ui.loaded && subject == null) {
                item(key = "missing") { Text("This subject is not in the syllabus.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            }
            itemsIndexed(shown, key = { _, it -> it.value }) { _, (index, chapter) ->
                ChapterRow(index + 1, chapter, progressOf(chapter),
                    onOpen = { openChapter = chapter },
                    onTick = { step -> vm.toggle(subjectName, chapter, step) },
                    modifier = Modifier.animateItem())
            }
        }
    }

    val open = openChapter
    if (open != null && exam != null && subject != null && open in subject.chapters) {
        ChapterSheet(
            ui = ui, exam = exam, subject = subject, chapter = open,
            number = subject.chapters.indexOf(open) + 1,
            vm = vm,
            onDismiss = { openChapter = null },
            onFocus = { openChapter = null; vm.focusOn(open); onOpenFocus() },
        )
    }
}

@Composable
private fun HeaderStat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        AutoSizeText(value, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold), minSize = 11.sp)
        AutoSizeText(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp), minSize = 7.sp)
    }
}

@Composable
private fun ChapterRow(
    number: Int,
    chapter: String,
    p: ChapterProgress,
    onOpen: () -> Unit,
    onTick: (SyllabusStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val next = p.nextStep
    DepthSurface(RoundedCornerShape(22.dp), modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Open chapter", onClick = onOpen),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "%02d".format(number),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = cs.outline, maxLines = 1, softWrap = false,
                    modifier = Modifier.width(26.dp).padding(top = 2.dp),
                )
                // Chapter names wrap at spaces only; Compose never splits a word unless it alone is wider than the row.
                Text(chapter, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 2)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${SyllabusMath.percent(p.fraction)}%",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold),
                    color = if (p.started) cs.onSurface else cs.outline, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            SyllabusBar(p.fraction, Modifier.padding(start = 26.dp, top = 9.dp, bottom = 9.dp))
            Row(Modifier.padding(start = 26.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SyllabusStep.entries.forEach { step ->
                    val look = when {
                        p.isDone(step) -> TickLook.ON
                        step == next -> TickLook.NEXT
                        else -> TickLook.OFF
                    }
                    TickChip(step, look, onClick = { onTick(step) }, modifier = Modifier.weight(if (step == SyllabusStep.DONE) 1.25f else 1f))
                }
            }
        }
    }
}
