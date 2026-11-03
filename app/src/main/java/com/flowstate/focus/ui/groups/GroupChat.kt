package com.flowstate.focus.ui.groups

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.data.groups.CHAT_MAX_CHARS
import com.flowstate.focus.data.groups.ChatMessage
import com.flowstate.focus.data.groups.GroupMember
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.FrostedGlassSurface
import com.flowstate.focus.ui.components.Glass
import com.flowstate.focus.ui.components.GlassCircleButton
import com.flowstate.focus.ui.components.LocalBlurSource
import com.flowstate.focus.ui.components.LocalGlassUi
import com.flowstate.focus.ui.components.TopBarHeight
import com.flowstate.focus.ui.components.TopBarTopGap
import com.flowstate.focus.ui.components.blurSource
import com.flowstate.focus.ui.components.glassPanel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Messages from one person less than this far apart stack into one run (one name, one avatar). */
private const val RUN_GAP_MS = 5 * 60_000L
private val STARTERS = listOf("Who's focusing tonight?", "Starting a 50 min block", "Good luck everyone")

/**
 * The "Group chat" pill under the group name: members' avatars, the newest message and an unread dot.
 */
@Composable
internal fun ChatPill(avatars: List<GroupMember>, unread: Boolean, preview: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val glass = LocalGlassUi.current
    val shape = CircleShape
    val content = if (glass) cs.onSurface else cs.onSecondaryContainer
    CompositionLocalProvider(LocalContentColor provides content) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .then(if (glass) Modifier.glassPanel(shape, Glass.accentWash(strong = true)) else Modifier.clip(shape).background(cs.secondaryContainer))
                .clickable(onClickLabel = "Open group chat", onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatars.isNotEmpty()) { AvatarStack(avatars.map { it.name to it.avatarId }, 36.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text("Group chat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(preview ?: "Say hi to the group", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = content.copy(alpha = 0.75f))
            }
            if (unread) Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary).semantics { contentDescription = "Unread messages" })
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Overlapping avatar illustrations, each with a thin ring so they read as separate coins. */
@Composable
private fun AvatarStack(people: List<Pair<String, String>>, size: Dp) {
    val step = size * 0.62f
    val ring = MaterialTheme.colorScheme.background
    Box(Modifier.width(size + step * (people.size - 1).coerceAtLeast(0)).height(size)) {
        people.forEachIndexed { i, (name, avatarId) ->
            Box(Modifier.offset(x = step * i).size(size).clip(CircleShape).background(ring).padding(2.dp)) {
                ProfileAvatar(name, avatarId, (size.value - 4).toInt())
            }
        }
    }
}

/**
 * Full-screen group chat. The messages scroll under a frosted top bar (back button and a pill with the
 * group) and a frosted message bar, both shaped like the app's top bar and tinted with the theme colour.
 */
@Composable
fun GroupChatScreen(onBack: () -> Unit, vm: GroupsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val group = state.selectedGroup
    if (group == null || state.user == null) { LaunchedEffect(Unit) { onBack() }; return }
    DisposableEffect(group.id) { vm.openChat(group.id); onDispose { vm.closeChat() } }

    val me = state.user?.uid
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val chatLayer = rememberGraphicsLayer()
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    var composerHeight by remember { mutableStateOf(TopBarHeight + 24.dp) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
    val others = state.members.filter { it.uid != me }
    val faces = (others.ifEmpty { state.members }).take(3).map { it.name to it.avatarId }
    val send: (String) -> Unit = { text ->
        if (vm.sendChat(group.id, text)) { draft = ""; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    // Scrolling up to the oldest loaded messages fetches the page before them.
    val messages by rememberUpdatedState(chat.messages)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (messages.isNotEmpty() && last >= messages.size - 5) vm.loadOlderChat() }
    }
    // A new message at the bottom scrolls into view when you are already near the bottom.
    LaunchedEffect(chat.messages.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalBlurSource provides chatLayer) {
            // Everything in here is what the frosted bars blur.
            Box(Modifier.fillMaxSize().blurSource(chatLayer)) {
                val topPad = statusTop + TopBarTopGap + TopBarHeight + 12.dp
                val bottomPad = bottomInset + composerHeight + 8.dp
                when {
                    !chat.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    chat.messages.isEmpty() -> EmptyChat(group.name, faces, onStarter = send,
                        modifier = Modifier.fillMaxSize().padding(top = topPad, bottom = bottomPad))
                    else -> LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topPad, bottom = bottomPad),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        itemsIndexed(chat.messages, key = { _, m -> m.id }) { i, m ->
                            MessageRow(m, mine = m.uid == me, older = chat.messages.getOrNull(i + 1), newer = chat.messages.getOrNull(i - 1),
                                onLongPress = { if (m.uid == me && !m.pending) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); deleting = m } })
                        }
                        if (chat.loadingOlder) item(key = "older") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        }
                        if (chat.reachedStart) item(key = "start") {
                            Text("This is the start of ${group.name}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                        }
                    }
                }
            }

            // Top bar: same shape as the app's frosted top bar.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = statusTop + TopBarTopGap).height(TopBarHeight),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onBack() }
                FrostedGlassSurface(CircleShape, Modifier.weight(1f).height(TopBarHeight), tinted = true) {
                    Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (faces.isNotEmpty()) { AvatarStack(faces, 32.dp); Spacer(Modifier.width(10.dp)) }
                        Column(Modifier.weight(1f)) {
                            AutoSizeText(group.name, style = MaterialTheme.typography.titleMedium, minSize = 13.sp)
                            Text("${group.memberIds.size} members · Private", style = MaterialTheme.typography.bodySmall,
                                color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Message bar: a frosted pill with the text field, and a frosted round send button.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomInset)
                    .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                chat.error?.let { error ->
                    FrostedGlassSurface(RoundedCornerShape(20.dp), Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::clearChatError) { Text("OK") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    FrostedGlassSurface(RoundedCornerShape(TopBarHeight / 2), Modifier.weight(1f).heightIn(min = TopBarHeight), tinted = true) {
                        val ink = LocalContentColor.current
                        BasicTextField(
                            value = draft,
                            onValueChange = { if (it.length <= CHAT_MAX_CHARS) draft = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                                .semantics { contentDescription = "Message ${group.name}" },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
                            cursorBrush = SolidColor(ink),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                            decorationBox = { inner ->
                                Box {
                                    if (draft.isEmpty()) Text("Message", style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = 0.55f))
                                    inner()
                                }
                            },
                        )
                    }
                    GlassCircleButton(Icons.AutoMirrored.Rounded.Send, "Send", enabled = draft.isNotBlank()) { send(draft) }
                }
                if (draft.length > CHAT_MAX_CHARS - 80) Text("${draft.length}/$CHAT_MAX_CHARS",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            }
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete message?") },
            text = { Text("It will be removed for everyone in ${group.name}.") },
            confirmButton = { TextButton(onClick = { vm.deleteChat(group.id, m.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(m: ChatMessage, mine: Boolean, older: ChatMessage?, newer: ChatMessage?, onLongPress: () -> Unit) {
    val newDay = older == null || !sameDay(older.sentAt, m.sentAt)
    val firstOfRun = newDay || older == null || older.uid != m.uid || m.sentAt - older.sentAt > RUN_GAP_MS
    val lastOfRun = newer == null || newer.uid != m.uid || newer.sentAt - m.sentAt > RUN_GAP_MS || !sameDay(m.sentAt, newer.sentAt)
    val cs = MaterialTheme.colorScheme
    val avatarSize = 34.dp
    Column(Modifier.fillMaxWidth().padding(top = if (firstOfRun) 10.dp else 0.dp)) {
        if (newDay) DayChip(m.sentAt)
        if (!mine && firstOfRun) Text(m.name, style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = avatarSize + 14.dp, bottom = 3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
            if (!mine) {
                // The illustration sits beside the last bubble of each run, like most chat apps.
                Box(Modifier.size(avatarSize)) { if (lastOfRun) ProfileAvatar(m.name, m.avatarId, avatarSize.value.toInt()) }
                Spacer(Modifier.width(8.dp))
            }
            Bubble(m.text, mine, lastOfRun, onLongPress, Modifier.widthIn(max = 290.dp))
        }
        if (lastOfRun) Text(
            timeLabel(m.sentAt) + if (m.pending) " · Sending" else "",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            textAlign = if (mine) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(start = if (mine) 0.dp else avatarSize + 14.dp, end = if (mine) 6.dp else 0.dp, top = 3.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(text: String, mine: Boolean, tail: Boolean, onLongPress: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val r = 20.dp; val t = if (tail) 6.dp else r
    val shape = if (mine) RoundedCornerShape(r, r, t, r) else RoundedCornerShape(r, r, r, t)
    val glass = LocalGlassUi.current
    val fill = when {
        glass -> Modifier.glassPanel(shape, if (mine) Glass.accentWash(strong = true) else null)
        mine -> Modifier.clip(shape).background(cs.primaryContainer)
        else -> Modifier.clip(shape).background(cs.surfaceContainerHigh)
    }
    val ink = when { glass -> cs.onSurface; mine -> cs.onPrimaryContainer; else -> cs.onSurface }
    // Clickable after the fill, so the press ripple stays inside the bubble's shape.
    Box(modifier.then(fill).combinedClickable(onClick = {}, onLongClickLabel = "Delete message", onLongClick = onLongPress).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = ink)
    }
}

@Composable
private fun DayChip(at: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        val shape = CircleShape
        val glass = LocalGlassUi.current
        Text(dayLabel(at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.then(if (glass) Modifier.glassPanel(shape) else Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                .padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/** No messages yet: the members' avatar illustrations gathered round a typing bubble, plus quick starters. */
@Composable
private fun EmptyChat(groupName: String, faces: List<Pair<String, String>>, onStarter: (String) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val glass = LocalGlassUi.current
    val cast = (faces + listOf("" to "owl", "" to "rocket", "" to "coffee")).take(3)
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.width(240.dp).height(150.dp)) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp)) { ProfileAvatar(cast[1].first, cast[1].second, 72) }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp)) { ProfileAvatar(cast[2].first, cast[2].second, 72) }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(CircleShape).background(cs.background).padding(3.dp)) {
                ProfileAvatar(cast[0].first, cast[0].second, 96)
            }
            // A little "typing" bubble.
            val bubble = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
            Row(
                Modifier.align(Alignment.TopEnd).padding(end = 20.dp)
                    .then(if (glass) Modifier.glassPanel(bubble, Glass.accentWash(strong = true)) else Modifier.clip(bubble).background(cs.primaryContainer))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) { repeat(3) { Box(Modifier.size(7.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.8f))) } }
        }
        Spacer(Modifier.height(24.dp))
        Text("Say hi to the group", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Messages stay here for everyone in $groupName. Only members can read them.", style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        STARTERS.forEach { s ->
            val shape = CircleShape
            Text(s, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 5.dp)
                    .then(if (glass) Modifier.glassPanel(shape) else Modifier.clip(shape).border(1.dp, cs.outline, shape))
                    .clickable(onClickLabel = "Send \"$s\"") { onStarter(s) }
                    .padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val z = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(z).toLocalDate() == Instant.ofEpochMilli(b).atZone(z).toLocalDate()
}

private fun dayLabel(at: Long): String {
    val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE, d MMM" else "d MMM yyyy", Locale.getDefault()))
    }
}

private fun timeLabel(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
