package com.flowstate.focus.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.domain.CHECK_IN_CHOICES
import com.flowstate.focus.domain.checkInLabel
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.ThemeMode
import com.flowstate.focus.service.FocusBlockerService
import com.flowstate.focus.ui.components.Entrance
import com.flowstate.focus.ui.components.GlassCard
import com.flowstate.focus.ui.components.ScreenContainer
import com.flowstate.focus.ui.components.formatDuration
import com.flowstate.focus.ui.components.screenContentPadding
import com.flowstate.focus.ui.theme.AccentColors
import kotlin.math.roundToInt
import android.provider.Settings as AndroidSettings

private fun isBlockerEnabled(context: Context): Boolean {
    val enabled = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val expected = ComponentName(context, FocusBlockerService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenBlockedApps: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var blockerOn by remember { mutableStateOf(isBlockerEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { blockerOn = isBlockerEnabled(context) }
    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val liquid = s.liquidUi

    ScreenContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Entrance(0) { Text("Settings", style = MaterialTheme.typography.headlineLarge) } }

            item {
                Entrance(1) {
                    Group("Appearance", liquid) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { i, mode ->
                                SegmentedButton(
                                    selected = s.themeMode == mode,
                                    onClick = { vm.update { it.copy(themeMode = mode) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                ) { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ToggleRow("Dynamic color", "Use colors from your wallpaper", s.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }
                        }
                        if (!s.dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            Text("Accent color", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AccentColors.forEachIndexed { index, color ->
                                    val selected = s.accentIndex == index
                                    Box(
                                        Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            .semantics { contentDescription = "Accent color ${index + 1}" }
                                            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                                vm.update { it.copy(accentIndex = index) }
                                            })
                                    )
                                }
                            }
                        }
                        ToggleRow("Liquid UI", "Breathing cards, fluid navigation and a living progress ring", s.liquidUi) { v -> vm.update { it.copy(liquidUi = v) } }
                        ToggleRow("Side navigation", "Experimental: move the tabs to a rail on the right edge", s.sideNav) { v -> vm.update { it.copy(sideNav = v) } }
                    }
                }
            }

            item {
                Entrance(2) {
                    Group("Timer", liquid) {
                        SliderRow("Focus length", s.focusMin, 5..90, "min") { v -> vm.update { it.copy(focusMin = v) } }
                        SliderRow("Short break", s.shortBreakMin, 1..30, "min") { v -> vm.update { it.copy(shortBreakMin = v) } }
                        SliderRow("Long break", s.longBreakMin, 5..60, "min") { v -> vm.update { it.copy(longBreakMin = v) } }
                        SliderRow("Rounds before long break", s.rounds, 2..8, "") { v -> vm.update { it.copy(rounds = v) } }
                        SliderRow("Countdown default", s.countdownMin, 5..180, "min") { v -> vm.update { it.copy(countdownMin = v) } }
                        // Up to 16 hours, in 15-minute steps.
                        SliderRow("Daily goal", s.dailyGoalMin, DAILY_GOAL_RANGE_MIN, "", step = 15, format = { formatDuration(it * 60_000L) }) { v -> vm.update { it.copy(dailyGoalMin = v) } }
                        ToggleRow("Keep screen on", "While a session is running", s.keepScreenOn) { v -> vm.update { it.copy(keepScreenOn = v) } }
                    }
                }
            }

            item {
                Entrance(3) {
                    Group("Focus mode", liquid) {
                        ToggleRow("Strict mode by default", "Locks each session until it ends", s.strictByDefault) { v -> vm.update { it.copy(strictByDefault = v) } }
                        ToggleRow("Focus Guard", "Nudge me if I leave for another app", s.focusGuard) { v -> vm.update { it.copy(focusGuard = v) } }
                        ToggleRow(
                            "Check-in",
                            "Ask \"Still focusing?\" every ${checkInLabel(s.checkInMinutes)} and pause if I don't answer within 2 minutes",
                            s.inactivityCheck,
                        ) { v -> vm.update { it.copy(inactivityCheck = v) } }
                        if (s.inactivityCheck) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                CHECK_IN_CHOICES.forEachIndexed { i, minutes ->
                                    SegmentedButton(
                                        selected = s.checkInMinutes == minutes,
                                        onClick = { vm.update { it.copy(checkInMinutes = minutes) } },
                                        shape = SegmentedButtonDefaults.itemShape(i, CHECK_IN_CHOICES.size),
                                        icon = {},
                                    ) { Text(checkInLabel(minutes), maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                        }
                        LinkRow("Blocked apps", "${s.blockedPackages.size} selected", onOpenBlockedApps)
                        LinkRow(
                            "App blocking service",
                            if (blockerOn) "On" else "Off. Tap to set up",
                        ) { if (blockerOn) context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) else showDisclosure = true }
                        LinkRow("Keep the timer reliable", "Exempt Flowstate from battery optimization") {
                            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                }
            }

            item {
                Entrance(4) {
                    Group("Data", liquid) {
                        LinkRow("Export sessions", "Save your history as a CSV file") { exporter.launch("flowstate-sessions.csv") }
                        Text(
                            "Your data stays on this device. Android's automatic backup restores your settings and history when you set up a new phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Allow app blocking") },
            text = {
                Text(
                    "Flowstate uses Android's Accessibility Service to notice which app is open during a focus session, " +
                        "so it can send you back to your work. It only sees the name of the app that comes to the front. " +
                        "It does not read your screen, messages or passwords, and nothing leaves your device.\n\n" +
                        "On the next screen, find \"Flowstate focus blocker\" and turn it on."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun Group(title: String, liquid: Boolean, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Daily focus goal limits in minutes: 15 minutes to 16 hours. */
private val DAILY_GOAL_RANGE_MIN = 15..960

/**
 * Keeps the thumb position local while dragging and writes to DataStore once, when the finger lifts.
 * A [step] above 1 snaps the slider to multiples of it; [format] overrides the "value suffix" label.
 */
@Composable
private fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    suffix: String,
    step: Int = 1,
    format: ((Int) -> String)? = null,
    onCommit: (Int) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    val snapped = if (step > 1) ((local / step).roundToInt() * step).coerceIn(range) else local.roundToInt()
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(format?.invoke(snapped) ?: "$snapped $suffix".trim(), style = MaterialTheme.typography.titleSmall)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(snapped) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
