package com.flowstate.focus.ui.groups

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.data.groups.GroupMember
import com.flowstate.focus.data.groups.GroupsState
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.ui.components.GlassCard
import com.flowstate.focus.ui.components.ScreenContainer
import com.flowstate.focus.ui.components.screenContentPadding
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class AuthForm { SIGN_IN, SIGN_UP, RESET }

@Composable
fun GroupsScreen(settings: AppSettings, onOpenFocus: () -> Unit, vm: GroupsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var form by rememberSaveable { mutableStateOf(AuthForm.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf("") }
    var groupName by rememberSaveable { mutableStateOf("") }
    var joinCode by rememberSaveable { mutableStateOf("") }
    var profileName by rememberSaveable { mutableStateOf("") }
    var profileAvatar by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(state.user?.uid) { if (state.user != null) password = "" }
    val lifecycleOwner = LocalLifecycleOwner.current
    val timer by vm.timer.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) { lifecycleOwner.lifecycle.repeatOnLifecycle(STARTED) { while (true) { now = System.currentTimeMillis(); delay(1_000) } } }

    ScreenContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenContentPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Groups", style = MaterialTheme.typography.headlineLarge) }
            if (!state.configured) {
                item { GlassCard(Modifier.fillMaxWidth(), settings.liquidUi) {
                    Text("Groups are getting ready", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Sign-in and shared groups need Firebase setup by the app developer. No demo accounts or sample group data are shown.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            } else if (state.user == null) {
                item { AuthCard(state, settings.liquidUi, form, { form = it; vm.clearError() }, name, { name = it }, email, { email = it }, password, { password = it }, showPassword, { showPassword = it }, { when (form) {
                    AuthForm.SIGN_IN -> vm.signIn(email, password)
                    AuthForm.SIGN_UP -> vm.signUp(name, email, password)
                    AuthForm.RESET -> vm.resetPassword(email)
                } }) }
            } else {
                item { ProfileCard(state, settings.liquidUi, onEdit = { profileName = state.user?.name.orEmpty(); profileAvatar = state.user?.avatarId.orEmpty(); dialog = "profile" }, onSignOut = { dialog = "signout" }) }
                item {
                    GlassCard(Modifier.fillMaxWidth(), settings.liquidUi) {
                        Text("Your groups", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        if (state.groups.isEmpty()) Text("Create a group or join one with an invite code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.groups.forEach { group -> FilterChip(selected = state.selectedGroup?.id == group.id, onClick = { vm.selectGroup(group.id) }, label = { Text(group.name) }) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !state.busy, onClick = { groupName = ""; dialog = "create" }) { Text("Create") }
                            OutlinedButton(enabled = !state.busy, onClick = { joinCode = ""; dialog = "join" }) { Text("Join") }
                        }
                    }
                }
                state.selectedGroup?.let { group ->
                    item { GroupHeader(group.name, group.memberIds.size, settings.liquidUi) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join ${group.name} on Flowstate with code: ${group.id}")
                        context.startActivity(Intent.createChooser(send, "Invite friends"))
                    } }
                    item { TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Leaderboard") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("● Live (${state.members.count { now - it.updatedAt <= 90_000L && it.live?.isFocusRunning == true }})") })
                    } }
                    if (selectedTab == 0) item { WeeklyBoard(state, settings.liquidUi, now) }
                    else item { LiveRoom(state, settings.liquidUi, now, vm, onOpenFocus, timer) }
                    item {
                        val cs = MaterialTheme.colorScheme
                        val actionColors = ButtonDefaults.outlinedButtonColors(
                            containerColor = cs.surfaceContainerHigh,
                            contentColor = cs.onSurface,
                            disabledContainerColor = cs.surfaceContainerHigh,
                            disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join ${group.name} on Flowstate with code: ${group.id}")
                                context.startActivity(Intent.createChooser(send, "Share group invite"))
                            }, modifier = Modifier.weight(1f), colors = actionColors,
                                border = BorderStroke(1.dp, cs.outline)) { Text("Share invite") }
                            OutlinedButton(enabled = !state.busy, onClick = { dialog = "leave" },
                                modifier = Modifier.weight(1f), colors = actionColors,
                                border = BorderStroke(1.dp, cs.outline.copy(alpha = if (state.busy) 0.38f else 1f))) { Text("Leave group") }
                        }
                    }
                }
            }
            if (state.loading || state.busy) item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            state.notice?.let { notice -> item { GlassCard(Modifier.fillMaxWidth()) {
                Text(notice, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = vm::clearError) { Text("Dismiss") }
            } } }
            state.error?.let { error -> item { GlassCard(Modifier.fillMaxWidth()) {
                Text("Couldn't complete that", style = MaterialTheme.typography.titleMedium)
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::clearError) { Text("Dismiss") }
            } } }
        }
    }

    when (dialog) {
        "profile" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Edit profile") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ProfileAvatar(profileName, profileAvatar, 64) }
                OutlinedTextField(profileName, { profileName = it }, label = { Text("Name") }, singleLine = true,
                    enabled = !state.busy, isError = profileName.trim().length > 40,
                    supportingText = { Text("${profileName.trim().length}/40 characters") })
                PresetAvatarPicker(profileName, profileAvatar, !state.busy) { profileAvatar = it }
                Text("Your avatar is shared across your group profiles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(enabled = !state.busy && profileName.trim().isNotEmpty() && profileName.trim().length <= 40, onClick = { vm.updateProfile(profileName, profileAvatar); dialog = "" }) { Text("Save") } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
        "create" -> TextEntryDialog("Create a group", "Group name", groupName, { groupName = it }, { vm.createGroup(groupName); dialog = "" }, state.busy) { dialog = "" }
        "join" -> TextEntryDialog("Join a group", "Invite code", joinCode, { joinCode = it }, { vm.joinGroup(joinCode); dialog = "" }, state.busy) { dialog = "" }
        "leave" -> ConfirmDialog("Leave ${state.selectedGroup?.name.orEmpty()}?", "You can rejoin later with an invite code.", { vm.leaveGroup(); dialog = "" }, busy = state.busy) { dialog = "" }
        "signout" -> ConfirmDialog("Sign out?", "You'll need to sign in again to see your groups.", { password = ""; vm.signOut(); dialog = "" }, busy = state.busy) { dialog = "" }
    }
}

@Composable
private fun GroupHeader(name: String, members: Int, liquid: Boolean, invite: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        OutlinedButton(onClick = invite) { Text("Invite friends") }
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium)
        Text("$members members · Private", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AuthCard(state: GroupsState, liquid: Boolean, form: AuthForm, changeForm: (AuthForm) -> Unit, name: String, setName: (String) -> Unit, email: String, setEmail: (String) -> Unit, password: String, setPassword: (String) -> Unit, showPassword: Boolean, setShowPassword: (Boolean) -> Unit, submit: () -> Unit) {
    val validEmail = email.trim().matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))
    val passwordValid = if (form == AuthForm.SIGN_UP) password.length >= 8 else password.isNotEmpty()
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        Text(if (form == AuthForm.SIGN_UP) "Create your account" else if (form == AuthForm.RESET) "Reset password" else "Sign in to your groups", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp)); Text("Focus together, without changing how your personal timer works.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (form == AuthForm.SIGN_UP) OutlinedTextField(name, setName, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(email, setEmail, Modifier.fillMaxWidth(), label = { Text("Email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true)
        if (form != AuthForm.RESET) {
            OutlinedTextField(password, setPassword, Modifier.fillMaxWidth(), label = { Text("Password") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { setShowPassword(!showPassword) }) { Text(if (showPassword) "Hide" else "Show") } }, singleLine = true)
            if (!passwordValid && password.isNotEmpty()) Text("Use at least 8 characters.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = submit, enabled = !state.busy && validEmail && (form == AuthForm.RESET || (passwordValid && (form != AuthForm.SIGN_UP || name.isNotBlank()))), modifier = Modifier.fillMaxWidth()) { Text(if (form == AuthForm.SIGN_UP) "Create account" else if (form == AuthForm.RESET) "Send reset email" else "Sign in") }
        if (form == AuthForm.SIGN_IN) TextButton(onClick = { changeForm(AuthForm.RESET) }) { Text("Forgot password?") }
        TextButton(onClick = { changeForm(if (form == AuthForm.SIGN_UP) AuthForm.SIGN_IN else AuthForm.SIGN_UP) }) { Text(if (form == AuthForm.SIGN_UP) "Already have an account? Sign in" else "New here? Create an account") }
        if (form == AuthForm.RESET) TextButton(onClick = { changeForm(AuthForm.SIGN_IN) }) { Text("Back to sign in") }
    }
}

@Composable
private fun ProfileCard(state: GroupsState, liquid: Boolean, onEdit: () -> Unit, onSignOut: () -> Unit) {
    val user = state.user ?: return
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(user.name, user.avatarId, 56)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(user.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Group profile", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onEdit, enabled = !state.busy) { Text("Edit") }
        }
        TextButton(enabled = !state.busy, onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun LiveRoom(state: GroupsState, liquid: Boolean, now: Long, vm: GroupsViewModel, onOpenFocus: () -> Unit, timer: TimerState) {
    val group = state.selectedGroup ?: return
    val context = LocalContext.current
    val uid = state.user?.uid
    val locallyActive = uid != null && timer.isFocusRunning
    val own = if (locallyActive) state.members.firstOrNull { it.uid == uid }?.copy(live = timer, updatedAt = now, name = state.user?.name.orEmpty(), avatarId = state.user?.avatarId.orEmpty())
        ?: state.user?.let { user -> GroupMember(user.uid, user.name, 0L, timer, now, avatarId = user.avatarId) } else null
    val members = state.members.filterNot { locallyActive && it.uid == uid }.let { if (own != null) it + own else it }
    val active = members.filter { now - it.updatedAt <= 90_000L && it.live?.isFocusRunning == true }
    val inactive = members.filterNot { now - it.updatedAt <= 90_000L && it.live?.isFocusRunning == true }
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        Text("Focusing now", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        // The first row reserves one of three slots for the invite action.
        val rows = listOf(active.take(2)) + active.drop(2).chunked(3)
        val slots = maxOf(1, rows.size)
        repeat(slots) { rowIndex ->
            val rowUsers = rows.getOrNull(rowIndex).orEmpty()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rowIndex == 0) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedButton(onClick = { val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join ${group.name} on Flowstate with code: ${group.id}"); context.startActivity(Intent.createChooser(send, "Invite friends")) }, modifier = Modifier.size(58.dp)) { Text("+") }
                    Text("Invite a friend", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                rowUsers.forEach { member -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProfileAvatar(member.name, member.avatarId, 58)
                    Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(clock(member.live!!.elapsedAt(now)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                repeat(((if (rowIndex == 0) 2 else 3) - rowUsers.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (inactive.isNotEmpty()) {
            Spacer(Modifier.height(18.dp)); androidx.compose.material3.HorizontalDivider()
            Spacer(Modifier.height(12.dp)); Text("Not focusing", style = MaterialTheme.typography.titleMedium)
            inactive.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { member -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        ProfileAvatar(member.name, member.avatarId, 44)
                        Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(when { now - member.updatedAt > 90_000L -> "Offline"; member.live?.status == Status.PAUSED -> "Paused"; member.live?.phase != com.flowstate.focus.domain.Phase.IDLE && member.live != null -> "Break"; else -> "Idle" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenFocus, modifier = Modifier.fillMaxWidth()) { Text("Start Focus") }
    }
}

@Composable
private fun WeeklyBoard(state: GroupsState, liquid: Boolean, now: Long) {
    val todayUtc = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
    val monday = todayUtc.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val sorted = state.members.sortedWith(compareByDescending<GroupMember> { it.weeklyFocusMs }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.uid })
    GlassCard(Modifier.fillMaxWidth(), liquid) {
        Text("This week", style = MaterialTheme.typography.titleLarge)
        Text("Monday–Sunday · UTC · ${monday.format(dateFmt)} – ${monday.plusDays(6).format(dateFmt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sorted.isEmpty()) { Spacer(Modifier.height(12.dp)); Text("No weekly focus totals yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val weekEnd = monday.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        Text("Week ends in ${duration((weekEnd - now).coerceAtLeast(0))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sorted.forEachIndexed { index, member ->
            val self = member.uid == state.user?.uid
            Row(Modifier.fillMaxWidth().padding(top = 14.dp).then(if (self) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), MaterialTheme.shapes.small).padding(6.dp) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", modifier = Modifier.size(30.dp).semantics { contentDescription = "Rank ${index + 1}" }, style = MaterialTheme.typography.titleMedium)
                ProfileAvatar(member.name, member.avatarId, 38, ring = when (index) { 0 -> 0xFFFFC107; 1 -> 0xFFB0BEC5; 2 -> 0xFFB87333; else -> null })
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(member.name + if (self) " · You" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall); Text("Weekly focus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(duration(member.weeklyFocusMs), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TextEntryDialog(title: String, label: String, value: String, update: (String) -> Unit, confirm: () -> Unit, busy: Boolean, dismiss: () -> Unit) = AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(value, update, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = confirm, enabled = value.isNotBlank() && !busy) { Text("Continue") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: () -> Unit, busy: Boolean = false, dismiss: () -> Unit) = AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = confirm, enabled = !busy) { Text("Confirm") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })

private fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, (seconds / 60) % 60, seconds % 60)
}

private fun duration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}
