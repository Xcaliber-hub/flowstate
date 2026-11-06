package com.flowstate.focus.ui.ambient

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fireplace
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.domain.AmbientMix
import com.flowstate.focus.domain.AmbientSound
import com.flowstate.focus.domain.FollowTimer
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.ambient.AmbientHold
import com.flowstate.focus.service.ambient.AmbientState
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.ConnectedButtonGroup
import com.flowstate.focus.ui.components.Cookie8
import com.flowstate.focus.ui.components.CookieBadge
import com.flowstate.focus.ui.components.DepthSurface
import com.flowstate.focus.ui.components.ExpressiveChip
import com.flowstate.focus.ui.components.SectionLabel
import com.flowstate.focus.ui.components.SplitSlider
import com.flowstate.focus.ui.components.WavyProgress
import com.flowstate.focus.ui.components.WavySlider
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val AmbientSound.icon: ImageVector get() = when (this) {
    AmbientSound.RAIN -> Icons.Rounded.WaterDrop
    AmbientSound.LIBRARY -> Icons.Rounded.AutoStories
    AmbientSound.BROWN -> Icons.Rounded.GraphicEq
    AmbientSound.CAFE -> Icons.Rounded.LocalCafe
    AmbientSound.FIRE -> Icons.Rounded.Fireplace
    AmbientSound.WAVES -> Icons.Rounded.Waves
    AmbientSound.CRICKETS -> Icons.Rounded.NightsStay
}

/** Ticks every 15 s, only while a countdown line is on screen. */
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberMinuteClock(active: Boolean): Long {
    val now by produceState(System.currentTimeMillis(), active) {
        value = System.currentTimeMillis()
        while (active) { delay(15_000); value = System.currentTimeMillis() }
    }
    return now
}

private fun minutesLeft(ms: Long) = ((ms + 59_999) / 60_000).coerceAtLeast(1)

private data class BarText(val status: String, val detail: String, val progress: Float)

private fun barText(s: AmbientState, t: TimerState, now: Long): BarText {
    val m = s.mix
    val status = when {
        !s.on -> "Ambient · off"
        s.audible -> "Ambient · playing"
        else -> "Ambient · paused"
    }
    val focusRunning = t.phase == Phase.FOCUS && t.status == Status.RUNNING
    return when {
        s.hold == AmbientHold.NO_SOUNDS -> BarText(status, "Pick a sound below", 0f)
        s.on && s.hold == AmbientHold.BREAK -> BarText(status, "Paused for your break", 0f)
        s.on && s.hold == AmbientHold.TIMER_PAUSED -> BarText(status, "Paused with the timer", 0f)
        m.follow == FollowTimer.WITH_FOCUS && s.on && focusRunning && !t.countsUp -> {
            val left = t.remainingAt(now)
            BarText(status, "Fades out with the block · ${minutesLeft(left)} min", 1f - left / t.phaseDurationMs.toFloat().coerceAtLeast(1f))
        }
        m.follow == FollowTimer.WITH_FOCUS && s.on && focusRunning -> BarText(status, "Fades out when you finish", 1f)
        m.follow == FollowTimer.WITH_FOCUS -> BarText(status, if (s.on) "Playing until you pause" else "Starts with your next focus block", if (s.on) 1f else 0f)
        m.follow == FollowTimer.MINUTES_30 && s.on && s.stopAt > 0 -> {
            val left = (s.stopAt - now).coerceAtLeast(0)
            BarText(status, "Stops in ${minutesLeft(left)} min", 1f - left / (30 * 60_000f))
        }
        s.on -> BarText(status, "Plays until you pause", 1f)
        else -> BarText(status, "Tap play for background sound", 0f)
    }
}

/** The player bar under the timer card: play/pause, the mix, a wavy progress line and the sound chips. */
@Composable
fun AmbientBar(vm: AmbientViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    val t by vm.timer.collectAsStateWithLifecycle()
    AmbientBarContent(s, t, vm, onExpand, modifier)
}

@Composable
fun AmbientBarContent(s: AmbientState, t: TimerState, vm: AmbientActions, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ticking = s.on && (s.mix.follow != FollowTimer.OFF)
    val now = rememberMinuteClock(ticking)
    val text = barText(s, t, if (ticking) maxOf(now, System.currentTimeMillis()) else now)
    DepthSurface(shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CookieBadge(
                    52.dp, cs.secondary, shape = Cookie8,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = vm::toggle)
                        .semantics { contentDescription = if (s.on) "Pause ambient sound" else "Play ambient sound" },
                ) {
                    Icon(if (s.on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = cs.onSecondary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        SectionLabel(text.status)
                    }
                    Text(
                        s.mix.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    WavyProgress(
                        progress = { text.progress },
                        modifier = Modifier.padding(top = 6.dp).widthIn(max = 180.dp).fillMaxWidth(),
                        strokeWidth = 4.dp,
                        amplitude = 2.2.dp,
                        wavelength = 11.dp,
                        animate = s.audible,
                    )
                    Text(
                        text.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(cs.background.copy(alpha = 0.55f))
                        .border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = onExpand)
                        .semantics { contentDescription = "Open the ambient mixer" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ExpandLess, contentDescription = null, tint = cs.secondary) }
            }
            Row(
                Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AmbientSound.entries.forEach { snd ->
                    ExpressiveChip(selected = s.mix.isOn(snd), label = snd.label, icon = snd.icon, onClick = { vm.toggleSound(snd) })
                }
            }
        }
    }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

/** The full mixer: per-sound volume, master volume, auto-pause and "Follow the timer". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbientMixerSheet(vm: AmbientViewModel, onDismiss: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
    ) { AmbientMixerContent(s, vm, onDismiss) }
}

@Composable
fun AmbientMixerContent(s: AmbientState, vm: AmbientActions, onDismiss: () -> Unit) {
    val m: AmbientMix = s.mix
    val cs = MaterialTheme.colorScheme
    run {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AutoSizeText("Ambient mix", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold))
                    Text(
                        "${m.name} · ${m.active.size} of ${AmbientSound.entries.size} sounds",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                // M3 Expressive split button: the action, and a chevron that closes the sheet.
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 8.dp, bottomEnd = 8.dp))
                            .background(cs.secondary)
                            .clickable(role = Role.Button, onClick = vm::toggle)
                            .padding(start = 14.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (s.on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = cs.onSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (s.on) "Pause" else "Play", color = cs.onSecondary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                    Box(
                        Modifier
                            .size(width = 44.dp, height = 48.dp)
                            .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 24.dp, bottomEnd = 24.dp))
                            .background(cs.secondary)
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .semantics { contentDescription = "Close the mixer" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = cs.onSecondary) }
                }
            }

            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                SectionLabel("Master")
                Spacer(Modifier.width(10.dp))
                WavySlider(m.master, vm::setMaster, Modifier.weight(1f), onValueChangeFinished = vm::commit, label = "Master volume")
                Text(pct(m.master), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.width(44.dp))
            }

            Column(Modifier.padding(top = 8.dp)) {
                AmbientSound.entries.forEach { snd ->
                    val on = m.isOn(snd)
                    Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        CookieBadge(
                            42.dp,
                            if (on) cs.secondary else cs.surfaceContainerHigh,
                            shape = Cookie8,
                            border = if (on) null else cs.outlineVariant.copy(alpha = 0.6f),
                            modifier = Modifier.clickable(role = Role.Switch) { vm.toggleSound(snd) },
                        ) { Icon(snd.icon, contentDescription = null, tint = if (on) cs.onSecondary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.width(12.dp))
                        AutoSizeText(
                            snd.label,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                            color = if (on) cs.onSurface else cs.onSurfaceVariant,
                            modifier = Modifier.width(92.dp),
                        )
                        SplitSlider(
                            value = m.level(snd),
                            on = on,
                            onValueChange = { vm.setLevel(snd, it) },
                            onValueChangeFinished = vm::commit,
                            label = "${snd.label} volume",
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (on) pct(m.level(snd)) else "Off",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (on) cs.onSurface else cs.outline,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.width(44.dp),
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 2.dp), color = cs.outlineVariant.copy(alpha = 0.4f))

            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Coffee, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    AutoSizeText("Auto-pause on breaks", style = MaterialTheme.typography.titleSmall)
                    Text("Silence during breaks, resume with focus", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                CheckSwitch(m.autoPauseOnBreaks, vm::setAutoPause)
            }

            Row(Modifier.padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Timer, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                SectionLabel("Follow the timer")
            }
            ConnectedButtonGroup(FollowTimer.entries, m.follow, { it.label }, vm::setFollow)
            Text(m.follow.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** M3 switch with a check in the thumb when on, as in the mockups. */
@Composable
fun CheckSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize), tint = cs.secondary) }
        } else null,
        colors = SwitchDefaults.colors(
            checkedTrackColor = cs.secondary,
            checkedThumbColor = cs.onSecondary,
            checkedBorderColor = cs.secondary,
        ),
    )
}
