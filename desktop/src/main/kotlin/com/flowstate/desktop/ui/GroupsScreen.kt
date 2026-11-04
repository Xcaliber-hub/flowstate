package com.flowstate.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.flowstate.desktop.cloud.ChatMessage
import com.flowstate.desktop.cloud.CloudState
import com.flowstate.desktop.cloud.Group
import com.flowstate.desktop.cloud.Member
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.groups.weeklyWeekStartIst
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class GroupsActions(
    val onSelect: (String) -> Unit = {},
    val onCreate: (String) -> Unit = {},
    val onJoin: (String) -> Unit = {},
    val onLeave: () -> Unit = {},
    val onSend: (String, ChatMessage?) -> Boolean = { _, _ -> true },
    val onCopy: (String) -> Unit = {},
    val onClearChatError: () -> Unit = {},
)

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

@Composable
fun GroupsScreen(state: CloudState, now: Long, a: GroupsActions, signIn: @Composable () -> Unit) {
    val group = state.selectedGroup
    var showNew by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val live = state.members.filter { it.isLiveFocusing(now) }
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Groups",
            subtitle = when {
                state.user == null -> "Sign in to focus with friends"
                group == null -> "Create a group or join one with a code"
                else -> "${group.name} · ${group.memberIds.size} ${if (group.memberIds.size == 1) "member" else "members"} · Private"
            },
            ringFraction = if (group == null) 0f else (live.size / group.memberIds.size.coerceAtLeast(1).toFloat()),
        ) {
            if (live.isNotEmpty()) Chip { LiveDot(); T(liveSummary(live, state.user?.uid), 13.sp, color = Slate.Fog) }
            if (group != null) CircleIconButton(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Leave group") { confirmLeave = true }
            if (state.user != null) CircleIconButton(Icons.Rounded.Add, contentDescription = "New group") { showNew = true }
        }
        Spacer(Modifier.height(22.dp))
        if (state.user == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { signIn() }
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            GroupList(state, now, a, Modifier.width(280.dp).fillMaxHeight(), onNew = { showNew = true })
            if (group == null) {
                Column(Modifier.weight(1f).fillMaxHeight().card(28.dp).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    T("No groups yet", 22.sp, FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    T("Create a group and share its 12-character code, or join a friend's group.", 14.sp, color = Slate.Mist, maxLines = 3, align = TextAlign.Center)
                    Spacer(Modifier.height(22.dp))
                    PillButton("New group", Icons.Rounded.Add, BtnStyle.Primary) { showNew = true }
                }
            } else {
                Leaderboard(state, group, now, a, Modifier.weight(1f).fillMaxHeight())
                ChatPanel(state, group, now, a, Modifier.width(400.dp).fillMaxHeight())
            }
        }
    }
    if (confirmLeave && group != null) ConfirmDialog("Leave ${group.name}?", "You'll leave the leaderboard and chat. You can rejoin later with the code ${group.id}.", "Leave",
        onConfirm = { confirmLeave = false; a.onLeave() }, onDismiss = { confirmLeave = false })
    if (showNew) NewGroupDialog(busy = state.busy, onCreate = { a.onCreate(it); showNew = false }, onJoin = { a.onJoin(it); showNew = false }, onDismiss = { showNew = false })
}

private fun liveSummary(live: List<Member>, me: String?): String {
    val others = live.filter { it.uid != me }
    val first = (others.firstOrNull() ?: live.first()).let { if (it.uid == me) "You" else it.name.substringBefore(' ') }
    val verb = if (first == "You") "are" else "is"
    return if (live.size == 1) "$first $verb focusing" else "$first and ${live.size - 1} more focusing"
}

@Composable
private fun GroupList(state: CloudState, now: Long, a: GroupsActions, modifier: Modifier, onNew: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 20.dp)) {
        T("Your groups", 15.sp, FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        Spacer(Modifier.height(14.dp))
        PillField(query, { query = it }, "Search groups", Modifier.fillMaxWidth(), height = 40.dp,
            leading = { Icon(Icons.Rounded.Search, null, tint = Slate.Mist, modifier = Modifier.size(16.dp)) })
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.groups.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }.forEach { g ->
                GroupRow(g, state, now, selected = g.id == state.selectedGroupId) { a.onSelect(g.id) }
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton("New group", Icons.Rounded.Add, height = 46.dp, modifier = Modifier.fillMaxWidth(), onClick = onNew)
    }
}

@Composable
private fun GroupRow(g: Group, state: CloudState, now: Long, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val sub = when {
        selected && state.members.any { it.isLiveFocusing(now) } -> {
            val live = state.members.filter { it.isLiveFocusing(now) }
            liveSummary(live, state.user?.uid) + " · ${live.size} live"
        }
        state.lastMessages[g.id] != null -> state.lastMessages[g.id]!!.let { m -> (if (m.uid == state.user?.uid) "You" else m.name.substringBefore(' ')) + ": " + m.text }
        else -> "${g.memberIds.size} ${if (g.memberIds.size == 1) "member" else "members"}"
    }
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .then(if (selected) Modifier.background(Slate.Steel.copy(alpha = 0.55f)).border(1.dp, Slate.Fog.copy(alpha = 0.18f), shape) else Modifier)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Slate.Fog else Slate.Steel),
            contentAlignment = Alignment.Center,
        ) { T(g.name.take(1).uppercase(), 17.sp, FontWeight.Bold, if (selected) Slate.Ink else Slate.Fog) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(g.name, 14.5.sp, FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            T(sub, 12.sp, color = Slate.Mist)
        }
        if (g.id in state.unreadGroups && !selected) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(Slate.Paper))
        }
    }
}

@Composable
private fun Leaderboard(state: CloudState, group: Group, now: Long, a: GroupsActions, modifier: Modifier) {
    val members = state.members
    val me = state.user?.uid
    val weekStart = Instant.ofEpochMilli(weeklyWeekStartIst(now)).atZone(IST).toLocalDate()
    val weekEndMs = weeklyWeekStartIst(now) + 7L * 24 * 3_600_000
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    val liveCount = members.count { it.isLiveFocusing(now) }
    Column(modifier.card(28.dp).padding(horizontal = 22.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("This week", 24.sp, FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                T("${weekStart.format(fmt)} – ${weekStart.plusDays(6).format(fmt)} · ends in ${untilLabel(weekEndMs - now)}", 12.5.sp, color = Slate.Mist)
            }
            Chip(height = 32.dp) {
                if (liveCount > 0) LiveDot(6.dp)
                T(if (liveCount > 0) "$liveCount live now" else "Code ${group.id}", 13.sp, color = Slate.Fog)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (members.isEmpty()) T("Loading the board…", 14.sp, color = Slate.Mist, modifier = Modifier.align(Alignment.Center))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val step = 98.dp
                Box(Modifier.fillMaxWidth().height(step * members.size + 14.dp)) {
                    members.forEachIndexed { i, m ->
                        val color = stackColor(i, members.size)
                        StackPill(m, i + 1, color, self = m.uid == me, now = now, modifier = Modifier.offset(y = step * i).zIndex(i.toFloat()))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val total = members.sumOf { it.weeklyFocusMs }
        val rank = members.indexOfFirst { it.uid == me }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BoardTile(formatDuration(total), "Group total", Modifier.weight(1f))
            BoardTile(if (rank >= 0) "#${rank + 1}" else "—", "Your rank", Modifier.weight(1f))
            val ahead = if (rank > 0) members[rank - 1] else null
            if (ahead != null) BoardTile(formatDuration(ahead.weeklyFocusMs - members[rank].weeklyFocusMs + 60_000L), "To pass ${ahead.name.substringBefore(' ')}", Modifier.weight(1f))
            else BoardTile(group.id, "Invite code", Modifier.weight(1f).clickable { a.onCopy(group.id) }, icon = true)
        }
    }
}

@Composable
private fun BoardTile(value: String, label: String, modifier: Modifier, icon: Boolean = false) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Slate.Steel.copy(alpha = 0.4f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        T(value, 17.sp, FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, 11.5.sp, color = Slate.Mist)
            if (icon) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.ContentCopy, "Copy", tint = Slate.Mist, modifier = Modifier.size(12.dp)) }
        }
    }
}

/** Light at the top of the board, darkening down the ranks, in the Slate palette. */
private fun stackColor(index: Int, count: Int): Color {
    val stops = listOf(Slate.Fog, Slate.Mist, Slate.Slate, Slate.Steel, Color(0xFF1A2C39))
    if (count <= 1) return stops[0]
    val pos = index / (count - 1f) * (stops.size - 1)
    val i = pos.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], pos - i)
}

@Composable
private fun StackPill(m: Member, rank: Int, color: Color, self: Boolean, now: Long, modifier: Modifier) {
    val shape = RoundedCornerShape(54.dp)
    val dark = color.luminance() > 0.35f
    val fg = if (dark) Slate.Ink else Slate.Paper
    val sub = if (dark) Slate.Ink.copy(alpha = 0.7f) else Slate.Mist
    Row(
        modifier.fillMaxWidth().height(108.dp).shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape).background(color)
            .then(if (self) Modifier.border(2.dp, Slate.Paper, shape) else Modifier)
            .padding(start = 28.dp, end = 30.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("$rank", 17.sp, FontWeight.SemiBold, sub, modifier = Modifier.width(26.dp))
        Avatar(m.name, m.avatarId, 46.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            T(if (self) "${m.name} · You" else m.name, 16.sp, FontWeight.SemiBold, fg)
            Spacer(Modifier.height(4.dp))
            val live = m.live
            if (m.isLiveFocusing(now) && live != null) {
                val where = when (m.livePlatform) { "windows" -> " on PC"; "android" -> " on phone"; else -> "" }
                val clock = if (live.countsUp) formatElapsed(live.elapsedAt(now)) else formatClock(live.remainingAt(now))
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Slate.Ink.copy(alpha = 0.55f)).border(1.dp, Slate.Fog.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Slate.Paper))
                    Spacer(Modifier.width(6.dp))
                    T((if (live.status == Status.PAUSED) "Paused$where" else "Focusing$where") + " · " + clock, 11.5.sp, FontWeight.SemiBold, Slate.Paper)
                }
            } else T("Weekly focus", 12.5.sp, color = sub)
        }
        T(formatDuration(m.weeklyFocusMs), 21.sp, FontWeight.Bold, fg)
    }
}

private fun untilLabel(ms: Long): String {
    val h = (ms / 3_600_000).coerceAtLeast(0)
    return if (h >= 24) "${h / 24}d ${h % 24}h" else "${h}h ${(ms / 60_000) % 60}m"
}

// ---------------------------------------------------------------- chat

@Composable
private fun ChatPanel(state: CloudState, group: Group, now: Long, a: GroupsActions, modifier: Modifier) {
    val me = state.user?.uid
    val chat = state.chat
    val membersById = state.members.associateBy { it.uid }
    var draft by remember(group.id) { mutableStateOf("") }
    var replyTo by remember(group.id) { mutableStateOf<ChatMessage?>(null) }
    Column(modifier.card(28.dp).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width((34 + 14 * (state.members.take(3).size - 1).coerceAtLeast(0)).dp).height(34.dp)) {
                state.members.take(3).forEachIndexed { i, m -> Avatar(m.name, m.avatarId, 34.dp, modifier = Modifier.offset(x = (14 * i).dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("Group chat", 15.sp, FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                T("${group.name} · ${group.memberIds.size} members", 12.5.sp, color = Slate.Mist)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Slate.Fog.copy(alpha = 0.08f)))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!chat.loaded) T("Loading messages…", 13.sp, color = Slate.Mist, modifier = Modifier.align(Alignment.Center))
            else if (chat.messages.isEmpty()) T("No messages yet. Say hi!", 13.sp, color = Slate.Mist, modifier = Modifier.align(Alignment.Center))
            val msgs = chat.messages
            val myNewest = msgs.firstOrNull { it.uid == me && !it.pending }
            LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom)) {
                item { Spacer(Modifier.height(10.dp)) }
                items(msgs.size, key = { msgs[it].id }) { i ->
                    val m = msgs[i]
                    val older = msgs.getOrNull(i + 1)
                    val newer = msgs.getOrNull(i - 1)
                    val day = dayOf(m.sentAt)
                    Column {
                        if (older == null || dayOf(older.sentAt) != day) DaySeparator(day)
                        val firstOfRun = older == null || older.uid != m.uid || dayOf(older.sentAt) != day
                        val lastOfRun = newer == null || newer.uid != m.uid
                        Bubble(m, mine = m.uid == me, showName = firstOfRun, showAvatar = lastOfRun, showTime = lastOfRun, onReply = { replyTo = m })
                        if (m == myNewest) SeenBy(chat.seenBy, m, me, membersById)
                    }
                }
            }
        }
        chat.error?.let { err ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                T(err, 12.sp, color = Color(0xFFE6A5A5), maxLines = 3, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Close, "Dismiss", tint = Slate.Mist, modifier = Modifier.size(16.dp).clickable { a.onClearChatError() })
            }
        }
        replyTo?.let { r ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(Slate.Steel.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    T("Replying to ${if (r.uid == me) "yourself" else r.name}", 12.sp, FontWeight.SemiBold, Slate.Fog)
                    T(r.text, 12.sp, color = Slate.Mist)
                }
                Icon(Icons.Rounded.Close, "Cancel reply", tint = Slate.Mist, modifier = Modifier.size(18.dp).clickable { replyTo = null })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val send = { if (a.onSend(draft, replyTo)) { draft = ""; replyTo = null } }
            PillField(draft, { if (it.length <= 500) draft = it }, "Message the group", Modifier.weight(1f), height = 50.dp, onEnter = send)
            Spacer(Modifier.width(10.dp))
            CircleIconButton(Icons.AutoMirrored.Rounded.Send, 50.dp, filled = true, enabled = draft.isNotBlank(), contentDescription = "Send") { send() }
        }
    }
}

private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
private fun DaySeparator(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)) }
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Slate.Fog.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 3.dp)) {
            T(label, 12.sp, FontWeight.SemiBold, Slate.Fog)
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean, showName: Boolean, showAvatar: Boolean, showTime: Boolean, onReply: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val time = Instant.ofEpochMilli(m.sentAt).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase()
    Column(Modifier.fillMaxWidth().hoverable(interaction).padding(top = if (showName) 8.dp else 0.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (showName && !mine) T(m.name, 12.sp, FontWeight.SemiBold, Slate.Fog, modifier = Modifier.padding(start = 44.dp, bottom = 4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (!mine) {
                if (showAvatar) Avatar(m.name, m.avatarId, 32.dp) else Spacer(Modifier.width(32.dp))
                Spacer(Modifier.width(10.dp))
            }
            if (mine && hovered) ReplyButton(onReply)
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier.widthIn(max = 270.dp).clip(shape)
                    .background(if (mine) Slate.Steel.copy(alpha = 0.85f) else Slate.Ink.copy(alpha = 0.55f))
                    .border(1.dp, Slate.Fog.copy(alpha = if (mine) 0.25f else 0.18f), shape)
                    .padding(horizontal = 15.dp, vertical = 9.dp)
            ) {
                m.replyTo?.let { r ->
                    Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(Slate.Window.copy(alpha = 0.35f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Box(Modifier.width(2.dp).height(30.dp).background(Slate.Fog.copy(alpha = 0.6f)))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            T(r.name, 11.5.sp, FontWeight.SemiBold, Slate.Fog)
                            T(r.text, 11.5.sp, color = Slate.Mist)
                        }
                    }
                }
                T(m.text, 14.sp, color = if (m.pending) Slate.Fog else Slate.Paper, maxLines = 40, lineHeight = 19.sp)
            }
            if (!mine && hovered) ReplyButton(onReply)
        }
        if (showTime) T(
            (if (m.pending) "Sending…" else time) + if (m.edited) " · Edited" else "", 11.sp, color = Slate.Mist,
            modifier = Modifier.padding(start = if (mine) 0.dp else 46.dp, top = 4.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun ReplyButton(onReply: () -> Unit) {
    Box(Modifier.padding(horizontal = 6.dp).size(26.dp).clip(CircleShape).background(Slate.Steel.copy(alpha = 0.7f)).clickable(onClick = onReply), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Rounded.Reply, "Reply", tint = Slate.Fog, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SeenBy(seenBy: Map<String, Long>, m: ChatMessage, me: String?, members: Map<String, Member>) {
    val who = seenBy.filter { (uid, at) -> uid != me && at >= m.sentAt }.keys.mapNotNull { members[it] }
    if (who.isEmpty()) return
    val names = who.map { it.name.substringBefore(' ') }
    val text = when (names.size) { 1 -> names[0]; 2 -> "${names[0]} and ${names[1]}"; else -> names.dropLast(1).joinToString(", ") + " and " + names.last() }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width((16 + 9 * (who.take(3).size - 1)).dp).height(16.dp)) {
            who.take(3).forEachIndexed { i, w -> Avatar(w.name, w.avatarId, 16.dp, ring = false, modifier = Modifier.offset(x = (9 * i).dp)) }
        }
        Spacer(Modifier.width(6.dp))
        T("Seen by $text", 11.5.sp, FontWeight.SemiBold, Slate.Mist)
    }
}

@Composable
private fun NewGroupDialog(busy: Boolean, onCreate: (String) -> Unit, onJoin: (String) -> Unit, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Slate.Deep,
        title = { T("New group", 18.sp, FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Segmented(listOf("Create", "Join with code"), tab) { tab = it; text = "" }
                PillField(text, { text = if (tab == 1) it.uppercase().filter(Char::isLetterOrDigit).take(12) else it.take(40) },
                    if (tab == 0) "Group name" else "12-character code", Modifier.width(360.dp))
            }
        },
        confirmButton = {
            PillButton(if (tab == 0) "Create" else "Join", style = BtnStyle.Primary, height = 42.dp, enabled = text.isNotBlank() && !busy) {
                if (tab == 0) onCreate(text) else onJoin(text)
            }
        },
        dismissButton = { PillButton("Cancel", height = 42.dp, onClick = onDismiss) },
    )
}
