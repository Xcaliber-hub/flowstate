package com.flowstate.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.StatsPeriod
import com.flowstate.focus.domain.TimerMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Statistics from the merged history: this PC's sessions plus the ones synced from the phone. */
@Composable
fun StatsScreen(all: List<SessionEntity>, goalMin: Int, myDeviceId: String, signedIn: Boolean, today: LocalDate = LocalDate.now(), onDelete: (Long) -> Unit = {}) {
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    // The whole history is walked here (date conversion per session), so it is computed once per change
    // of history, period or day, not on every recomposition (1.8.1).
    val perDay = remember(all) { StatsCalculator.perDay(all) }
    val inPeriod = remember(all, period, today) { StatsCalculator.inPeriod(all, period, today) }
    val total = inPeriod.sumOf { it.durationMs }
    val completion = if (inPeriod.isEmpty()) 0f else inPeriod.count { it.completed } / inPeriod.size.toFloat()
    val goalMs = goalMin * 60_000L
    val weekMs = (0..6).sumOf { perDay[today.minusDays(it.toLong())] ?: 0L }
    val buckets = remember(inPeriod, period, today) { StatsCalculator.buckets(period, inPeriod, today) }
    val streak = remember(perDay, today) { StatsCalculator.streak(perDay, today) }
    val score = StatsCalculator.productivityScore(weekMs, goalMs, completion)
    val fromPhone = inPeriod.filter { it.origin != null && it.origin != myDeviceId }.sumOf { it.durationMs }
    val dist = remember(inPeriod) {
        inPeriod.groupBy { it.subject.ifBlank { "No subject" } }.mapValues { (_, l) -> l.sumOf { it.durationMs } }
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Stats", "${period.label} · ${if (signedIn) "phone and PC combined" else "this PC"}", if (goalMs > 0) (perDay[today] ?: 0L) / goalMs.toFloat() else 0f) {
            Segmented(StatsPeriod.entries.map { it.label }, StatsPeriod.entries.indexOf(period)) { period = StatsPeriod.entries[it] }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatTile(formatDuration(total), "Focus time", Modifier.weight(1f))
                    StatTile("${inPeriod.size}", "Sessions", Modifier.weight(1f))
                    StatTile(if (inPeriod.isEmpty()) "—" else formatDuration(total / inPeriod.size), "Average", Modifier.weight(1f))
                    StatTile(if (streak == 1) "1 day" else "$streak days", "Streak", Modifier.weight(1f))
                }
                SectionCard(Modifier.fillMaxWidth().weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("Focus by ${when (period) { StatsPeriod.DAY -> "hour"; StatsPeriod.YEAR -> "month"; else -> "day" }}", 15.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        T("${(completion * 100).toInt()}% completed", 12.5.sp, color = Slate.Mist)
                    }
                    Spacer(Modifier.height(18.dp))
                    BarChart(buckets.map { it.valueMs }, buckets.map { it.label }, Modifier.fillMaxWidth().weight(1f))
                }
            }
            Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SectionCard(Modifier.fillMaxWidth()) {
                    T("Productivity score", 12.5.sp, color = Slate.Mist)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        T("$score", 34.sp, FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        T("/ 100", 16.sp, FontWeight.Medium, Slate.Mist, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    ProgressBar(score / 100f)
                    Spacer(Modifier.height(12.dp))
                    T("Last 7 days against your ${formatDuration(goalMin * 60_000L).replace(" 00m", "")} daily goal, plus how many sessions you finish.", 12.sp, color = Slate.Mist, maxLines = 3)
                    if (signedIn) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MiniTile(formatDuration(total - fromPhone), "On this PC", Modifier.weight(1f))
                            MiniTile(formatDuration(fromPhone), "Other devices", Modifier.weight(1f))
                        }
                    }
                }
                SectionCard(Modifier.fillMaxWidth().weight(1f)) {
                    T("Subjects", 15.sp, FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    if (dist.isEmpty()) T("No focus recorded in this period yet.", 13.sp, color = Slate.Mist)
                    dist.forEach { (name, ms) ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(name, 13.5.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            T(formatDuration(ms), 13.sp, color = Slate.Fog)
                        }
                        ProgressBar(if (total > 0) ms / total.toFloat() else 0f, height = 6.dp)
                    }
                    Spacer(Modifier.height(16.dp))
                    T("Recent sessions", 15.sp, FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        // 1.8.1: keyed by id, so an open "Delete this session?" stays with its own session when a
                        // pull or a finished block shifts the list (positional state used to slide onto the row above).
                        all.take(20).forEach { s -> key(s.id) { SessionRow(s, myDeviceId, onDelete) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(modifier.card(20.dp).padding(horizontal = 18.dp, vertical = 16.dp)) {
        T(value, 24.sp, FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        T(label, 12.sp, color = Slate.Mist)
    }
}

@Composable
private fun SessionRow(s: SessionEntity, myDeviceId: String, onDelete: (Long) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val time = Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault())
    val elsewhere = s.origin != null && s.origin != myDeviceId
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (elsewhere) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null, tint = Slate.Mist, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(s.subject.ifBlank { TimerMode.labelFor(s.mode) }, 13.sp, FontWeight.SemiBold)
            T(time.format(DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.ENGLISH)), 11.5.sp, color = Slate.Mist)
        }
        T(formatDuration(s.durationMs), 13.sp, color = Slate.Fog)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(22.dp).clip(CircleShape).clickable { confirm = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Delete session", tint = Slate.Mist, modifier = Modifier.size(14.dp))
        }
    }
    if (confirm) {
        // The id is fixed when the dialog opens, and the text names the session, so the right one is deleted.
        val targetId = remember { s.id }
        val label = "${formatDuration(s.durationMs)} of ${s.subject.ifBlank { TimerMode.labelFor(s.mode) }} on ${time.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH))}"
        ConfirmDialog("Delete this session?", "$label will be removed from your history, stats and streak on every device. This can't be undone.", "Delete",
            onConfirm = { confirm = false; onDelete(targetId) }, onDismiss = { confirm = false })
    }
}

@Composable
private fun BarChart(values: List<Long>, labels: List<String>, modifier: Modifier) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(60_000L)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.56f).coerceAtMost(46.dp.toPx())
            for (g in 1..3) {
                val y = size.height * (1 - g / 3f)
                drawLine(Slate.Fog.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            values.forEachIndexed { i, v ->
                val h = (v / max.toFloat()) * (size.height - 8f)
                val x = slot * i + (slot - barW) / 2
                drawRoundRect(Slate.Fog.copy(alpha = 0.06f), Offset(x, 0f), Size(barW, size.height), CornerRadius(barW / 2))
                if (v > 0) drawRoundRect(
                    Brush.verticalGradient(listOf(Slate.Paper, Slate.Mist, Slate.Slate), startY = size.height - h, endY = size.height),
                    Offset(x, size.height - h), Size(barW, h), CornerRadius(barW / 2),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { l -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { T(l, 11.sp, color = Slate.Mist) } }
        }
    }
}
