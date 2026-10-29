package com.flowstate.focus.ui.stats

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.flowstate.focus.ui.components.AutoSizeText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.StatsPeriod
import com.flowstate.focus.ui.components.Entrance
import com.flowstate.focus.ui.components.GlassCard
import com.flowstate.focus.ui.components.ScreenContainer
import com.flowstate.focus.ui.components.formatDuration
import com.flowstate.focus.ui.components.screenContentPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(settings: AppSettings, vm: StatsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val liquid = settings.liquidUi
    ScreenContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Entrance(0) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AutoSizeText("Statistics", style = MaterialTheme.typography.headlineLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            StatsPeriod.entries.forEachIndexed { index, p ->
                                SegmentedButton(
                                    selected = p == ui.period,
                                    onClick = { vm.setPeriod(p) },
                                    shape = SegmentedButtonDefaults.itemShape(index, StatsPeriod.entries.size),
                                    // No check mark: it steals width, and "Month" broke onto two lines in narrow layouts.
                                    icon = {},
                                ) { AutoSizeText(p.label, style = MaterialTheme.typography.labelLarge, minSize = 10.sp) }
                            }
                        }
                    }
                }
            }
            item(key = "chart") {
                Entrance(1) {
                    GlassCard(Modifier.fillMaxWidth(), liquid) {
                        AutoSizeText("Focus time", style = MaterialTheme.typography.titleMedium)
                        Text(formatDuration(ui.totalMs), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        if (ui.period == StatsPeriod.MONTH) LineChart(ui.buckets) else BarChart(ui.buckets)
                    }
                }
            }
            item(key = "numbers") {
                Entrance(2) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Metric("Sessions", ui.sessionCount.toString(), liquid, Modifier.weight(1f).fillMaxHeight())
                            Metric("Avg. length", formatDuration(ui.averageMs), liquid, Modifier.weight(1f).fillMaxHeight())
                        }
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Metric("Completed", "${ui.completionPercent}%", liquid, Modifier.weight(1f).fillMaxHeight())
                            Metric("Streak", if (ui.streak == 1) "1 day" else "${ui.streak} days", liquid, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
            item(key = "score") {
                Entrance(3) {
                    GlassCard(Modifier.fillMaxWidth(), liquid) {
                        Row(Modifier.fillMaxWidth()) {
                            AutoSizeText("Productivity score", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("${ui.productivityScore} / 100", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { ui.productivityScore / 100f },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Based on the last 7 days: progress toward your daily goal and how many sessions you finish.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "heatmap") {
                Entrance(4) {
                    GlassCard(Modifier.fillMaxWidth(), liquid) {
                        AutoSizeText("Last 15 weeks", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Heatmap(ui.perDay, ui.goalMs)
                    }
                }
            }
            item(key = "distribution") {
                Entrance(5) {
                    GlassCard(Modifier.fillMaxWidth(), liquid) {
                        AutoSizeText("Where your focus went", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        if (ui.distribution.isEmpty()) {
                            Text(
                                "Add a subject when you start a session to see this breakdown.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val top = ui.distribution.maxOfOrNull { it.second } ?: 1L
                        ui.distribution.forEach { (name, ms) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(formatDuration(ms), style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { ms / top.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                strokeCap = StrokeCap.Round,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(title: String, value: String, liquid: Boolean, modifier: Modifier) {
    GlassCard(modifier, liquid) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        AutoSizeText(value, style = MaterialTheme.typography.headlineMedium)
    }
}
