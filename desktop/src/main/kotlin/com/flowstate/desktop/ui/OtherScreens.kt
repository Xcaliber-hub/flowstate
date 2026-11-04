package com.flowstate.desktop.ui

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.desktop.FlowstateConfig
import com.flowstate.desktop.cloud.CloudState
import com.flowstate.desktop.store.DesktopSettings
import com.flowstate.focus.data.db.TaskEntity
import com.flowstate.focus.domain.CHECK_IN_CHOICES
import com.flowstate.focus.domain.checkInLabel
import com.flowstate.focus.domain.groups.PresetAvatars
import java.time.LocalDate
import java.time.ZoneId

// ---------------------------------------------------------------- Tasks

@Composable
fun TasksScreen(tasks: List<TaskEntity>, onAdd: (TaskEntity) -> Unit, onToggle: (TaskEntity) -> Unit, onDelete: (TaskEntity) -> Unit, onClearDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(1) }
    var due by remember { mutableStateOf(0) }
    val open = tasks.count { !it.done }
    Column(Modifier.fillMaxSize()) {
        TopBar("Tasks", if (open == 1) "1 open task" else "$open open tasks", if (tasks.isEmpty()) 0f else (tasks.size - open) / tasks.size.toFloat()) {
            if (tasks.any { it.done }) PillButton("Clear done", height = 40.dp, style = BtnStyle.Dim, onClick = onClearDone)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SectionCard(Modifier.weight(1f).fillMaxHeight()) {
                T("Your list", 15.sp, FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (tasks.isEmpty()) T("Nothing here yet. Add your first task on the right.", 13.sp, color = Slate.Mist)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    tasks.forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Slate.Fog.copy(alpha = 0.08f)))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(if (t.done) Slate.Paper else Color.Transparent)
                                    .border(2.dp, if (t.done) Slate.Paper else Slate.Slate, CircleShape).clickable { onToggle(t) },
                                contentAlignment = Alignment.Center,
                            ) { if (t.done) Icon(Icons.Rounded.Check, "Done", tint = Slate.Ink, modifier = Modifier.size(14.dp)) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                T(t.title, 14.5.sp, FontWeight.SemiBold, if (t.done) Slate.Mist else Slate.Paper)
                                Spacer(Modifier.height(3.dp))
                                T(listOfNotNull(t.category.ifBlank { null }, priorityLabel(t.priority), t.deadline?.let { "due " + deadlineLabel(it) }).joinToString(" · "), 12.sp, color = Slate.Mist)
                            }
                            Box(Modifier.size(26.dp).clip(CircleShape).clickable { onDelete(t) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "Delete task", tint = Slate.Mist, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            SectionCard(Modifier.width(360.dp)) {
                T("Add a task", 15.sp, FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                PillField(title, { title = it.take(80) }, "What needs doing?", Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PillField(category, { category = it.take(30) }, "Subject or category (optional)", Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                T("Priority", 12.5.sp, color = Slate.Mist)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("Low", "Medium", "High"), priority) { priority = it }
                Spacer(Modifier.height(16.dp))
                T("Deadline", 12.5.sp, color = Slate.Mist)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("None", "Today", "Tomorrow", "Week"), due) { due = it }
                Spacer(Modifier.height(20.dp))
                PillButton("Add task", Icons.Rounded.Add, BtnStyle.Primary, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    val today = LocalDate.now()
                    val deadline = when (due) { 1 -> today; 2 -> today.plusDays(1); 3 -> today.plusDays(7); else -> null }
                        ?.atTime(23, 59)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                    onAdd(TaskEntity(title = title.trim(), category = category.trim(), priority = priority, deadline = deadline, createdAt = System.currentTimeMillis()))
                    title = ""; category = ""; due = 0
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Sign in

class AccountActions(
    val onSignIn: (String, String) -> Unit = { _, _ -> },
    val onSignUp: (String, String, String) -> Unit = { _, _, _ -> },
    val onReset: (String) -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onSaveProfile: (String, String) -> Unit = { _, _ -> },
    val onClearMessages: () -> Unit = {},
)

@Composable
fun SignInPanel(state: CloudState, a: AccountActions, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(modifier.width(440.dp).card(28.dp).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T("Flowstate account", 22.sp, FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        T("The same account as on your phone. Your groups, stats and the timer follow you.", 13.sp, color = Slate.Mist, maxLines = 3, align = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        if (!state.configured) {
            T("Firebase is not configured in this build.", 14.sp, color = Slate.Fog)
            return@Column
        }
        Segmented(listOf("Sign in", "Create account"), tab) { tab = it; a.onClearMessages() }
        Spacer(Modifier.height(18.dp))
        val submit = { if (tab == 0) a.onSignIn(email, password) else a.onSignUp(name, email, password) }
        if (tab == 1) { PillField(name, { name = it.take(40) }, "Your name", Modifier.fillMaxWidth()); Spacer(Modifier.height(10.dp)) }
        PillField(email, { email = it.trim().take(254) }, "Email", Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        PillField(password, { password = it.take(128) }, "Password", Modifier.fillMaxWidth(), password = true, onEnter = submit)
        Spacer(Modifier.height(18.dp))
        PillButton(if (tab == 0) "Sign in" else "Create account", style = BtnStyle.Primary, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), onClick = submit)
        if (tab == 0) {
            Spacer(Modifier.height(12.dp))
            T("Forgot password?", 13.sp, FontWeight.SemiBold, Slate.Fog, modifier = Modifier.clickable { a.onReset(email) })
        }
        state.error?.let { Spacer(Modifier.height(14.dp)); T(it, 13.sp, color = Color(0xFFE6A5A5), maxLines = 5, align = TextAlign.Center) }
        state.notice?.let { Spacer(Modifier.height(14.dp)); T(it, 13.sp, color = Slate.Fog, maxLines = 5, align = TextAlign.Center) }
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: DesktopSettings, onUpdate: ((DesktopSettings) -> DesktopSettings) -> Unit, cloud: CloudState, account: AccountActions, dataFolder: String, onOpenFolder: () -> Unit, update: com.flowstate.desktop.DesktopUpdate? = null, onDownloadUpdate: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", "Flowstate ${FlowstateConfig.VERSION} for Windows", 1f)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SectionCard(Modifier.fillMaxWidth()) {
                    T("Timer", 15.sp, FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    StepperRow("Pomodoro focus", "${settings.focusMin} min", { onUpdate { it.copy(focusMin = (it.focusMin - 5).coerceAtLeast(5)) } }, { onUpdate { it.copy(focusMin = (it.focusMin + 5).coerceAtMost(90)) } })
                    StepperRow("Short break", "${settings.shortBreakMin} min", { onUpdate { it.copy(shortBreakMin = (it.shortBreakMin - 1).coerceAtLeast(1)) } }, { onUpdate { it.copy(shortBreakMin = (it.shortBreakMin + 1).coerceAtMost(30)) } })
                    StepperRow("Long break", "${settings.longBreakMin} min", { onUpdate { it.copy(longBreakMin = (it.longBreakMin - 5).coerceAtLeast(5)) } }, { onUpdate { it.copy(longBreakMin = (it.longBreakMin + 5).coerceAtMost(60)) } })
                    StepperRow("Rounds before a long break", "${settings.rounds}", { onUpdate { it.copy(rounds = (it.rounds - 1).coerceAtLeast(1)) } }, { onUpdate { it.copy(rounds = (it.rounds + 1).coerceAtMost(8)) } })
                    StepperRow("Countdown length", "${settings.countdownMin} min", { onUpdate { it.copy(countdownMin = (it.countdownMin - 5).coerceAtLeast(5)) } }, { onUpdate { it.copy(countdownMin = (it.countdownMin + 5).coerceAtMost(180)) } })
                    StepperRow("Daily goal", formatDuration(settings.dailyGoalMin * 60_000L), { onUpdate { it.copy(dailyGoalMin = (it.dailyGoalMin - 15).coerceAtLeast(15)) } }, { onUpdate { it.copy(dailyGoalMin = (it.dailyGoalMin + 15).coerceAtMost(16 * 60)) } })
                }
                SectionCard(Modifier.fillMaxWidth()) {
                    T("Focus", 15.sp, FontWeight.Bold)
                    ToggleRow("Strict mode by default", "No pausing or quitting until the block ends", settings.strictByDefault) { v -> onUpdate { it.copy(strictByDefault = v) } }
                    ToggleRow("Still focusing? check-ins", "Pauses the timer if nobody answers within 2 minutes", settings.inactivityCheck) { v -> onUpdate { it.copy(inactivityCheck = v) } }
                    if (settings.inactivityCheck) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            CHECK_IN_CHOICES.forEach { m ->
                                val on = settings.checkInMinutes == m
                                Box(
                                    Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) Slate.Paper else Slate.Steel.copy(alpha = 0.5f))
                                        .clickable { onUpdate { it.copy(checkInMinutes = m) } }.padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) { T("Every ${checkInLabel(m)}", 13.sp, FontWeight.SemiBold, if (on) Slate.Ink else Slate.Fog) }
                            }
                        }
                    }
                    ToggleRow("Sound with alerts", "A short chime when a phase ends", settings.sound) { v -> onUpdate { it.copy(sound = v) } }
                    ToggleRow("Keep running in the tray", "Closing the window keeps the timer and alerts going", settings.closeToTray) { v -> onUpdate { it.copy(closeToTray = v) } }
                    Spacer(Modifier.height(8.dp))
                    T("App blocking is phone-only: Windows has no equivalent of Android's focus guard, so Flowstate on PC doesn't block apps.", 12.sp, color = Slate.Mist, maxLines = 3)
                }
            }
            Column(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (cloud.user == null) SignInPanel(cloud, account, Modifier.fillMaxWidth())
                else ProfileCard(cloud, account)
                SectionCard(Modifier.fillMaxWidth()) {
                    T("About", 15.sp, FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    T("Version ${FlowstateConfig.VERSION}", 13.sp, color = Slate.Fog)
                    if (update != null) {
                        Spacer(Modifier.height(10.dp))
                        T("Flowstate ${update.version} is available.", 13.sp, FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        PillButton("Download installer", style = BtnStyle.Primary, height = 40.dp, onClick = onDownloadUpdate)
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    T("Data folder: $dataFolder", 12.sp, color = Slate.Mist, maxLines = 2, modifier = Modifier.clickable(onClick = onOpenFolder))
                    Spacer(Modifier.height(4.dp))
                    T("New versions: github.com/Xcaliber-hub/flowstate/releases", 12.sp, color = Slate.Mist, maxLines = 2)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(cloud: CloudState, a: AccountActions) {
    val user = cloud.user!!
    var name by remember(user.name) { mutableStateOf(user.name) }
    var avatar by remember(user.avatarId) { mutableStateOf(user.avatarId) }
    SectionCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, avatar, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                T(user.name, 18.sp, FontWeight.Bold)
                T(user.email, 12.5.sp, color = Slate.Mist)
            }
        }
        Spacer(Modifier.height(16.dp))
        PillField(name, { name = it.take(40) }, "Name", Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        T("Avatar", 12.5.sp, color = Slate.Mist)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("") + PresetAvatars.all.map { it.id }).forEach { id ->
                val on = id == avatar
                Box(Modifier.clip(CircleShape).border(2.dp, if (on) Slate.Paper else Color.Transparent, CircleShape).padding(3.dp).clickable { avatar = id }) {
                    Avatar(name, id, 38.dp, ring = false)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Save profile", style = BtnStyle.Primary, height = 44.dp, enabled = !cloud.busy && name.isNotBlank()) { a.onSaveProfile(name, avatar) }
            PillButton("Sign out", height = 44.dp, onClick = a.onSignOut)
        }
        cloud.error?.let { Spacer(Modifier.height(12.dp)); T(it, 12.5.sp, color = Color(0xFFE6A5A5), maxLines = 5) }
        cloud.notice?.let { Spacer(Modifier.height(12.dp)); T(it, 12.5.sp, color = Slate.Fog, maxLines = 4) }
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 14.sp, modifier = Modifier.weight(1f))
        CircleIconButton(Icons.Rounded.Remove, 30.dp, contentDescription = "Less", onClick = onMinus)
        T(value, 14.sp, FontWeight.SemiBold, modifier = Modifier.width(84.dp), align = TextAlign.Center)
        CircleIconButton(Icons.Rounded.Add, 30.dp, contentDescription = "More", onClick = onPlus)
    }
}

@Composable
private fun ToggleRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(label, 14.sp, FontWeight.SemiBold)
            T(detail, 12.sp, color = Slate.Mist)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Slate.Ink, checkedTrackColor = Slate.Paper, uncheckedThumbColor = Slate.Mist, uncheckedTrackColor = Slate.Steel, uncheckedBorderColor = Slate.Slate),
        )
    }
}
