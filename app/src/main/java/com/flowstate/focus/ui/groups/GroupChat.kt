package com.flowstate.focus.ui.groups

import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import com.flowstate.focus.data.groups.TypingMember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.LifecycleStartEffect
import com.flowstate.focus.data.groups.ChatReply
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
internal fun ChatPill(avatars: List<GroupMember>, unread: Boolean, unreadCount: Int, preview: String?, onClick: () -> Unit) {
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
                Text(if (unread && unreadCount > 0) "Group chat · $unreadCount new" else "Group chat",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(preview ?: "Say hi to the group", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = content.copy(alpha = 0.75f),
                    fontWeight = if (unread) FontWeight.SemiBold else null)
            }
            when {
                unread && unreadCount > 0 -> UnreadBadge(unreadCount)
                unread -> Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary).semantics { contentDescription = "Unread messages" })
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** The count of new messages in a pill, like a notification badge. 100 or more shows as "99+". */
@Composable
private fun UnreadBadge(count: Int) {
    val cs = MaterialTheme.colorScheme
    val label = if (count > 99) "99+" else "$count"
    Box(
        Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(CircleShape).background(cs.primary)
            .padding(horizontal = 7.dp)
            .semantics { contentDescription = "$count new messages" },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = cs.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1) }
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
 * Swipe a message right (or long-press it) to reply; your newest message says who has seen it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(onBack: () -> Unit, vm: GroupsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val group = state.selectedGroup
    if (group == null || state.user == null) { LaunchedEffect(Unit) { onBack() }; return }
    DisposableEffect(group.id) { vm.openChat(group.id); onDispose { vm.closeChat() } }
    // Only count messages as seen while the chat is actually on screen.
    LifecycleStartEffect(group.id) { vm.setChatVisible(true); onStopOrDispose { vm.setChatVisible(false) } }

    val me = state.user?.uid
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val chatLayer = rememberGraphicsLayer()
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var seenSheetFor by remember { mutableStateOf<ChatMessage?>(null) }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    var composerHeight by remember { mutableStateOf(TopBarHeight + 24.dp) }
    val focus = remember { FocusRequester() }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
    val others = state.members.filter { it.uid != me }
    val faces = (others.ifEmpty { state.members }).take(3).map { it.name to it.avatarId }
    // Tell the others I'm typing while the draft changes; the repository throttles the writes.
    LaunchedEffect(draft.isNotBlank(), draft, editing) { vm.setTyping(group.id, draft.isNotBlank() && editing == null) }
    val typingRow = chat.typing.isNotEmpty()
    val send: (String) -> Unit = { text ->
        val target = editing
        if (target != null) {
            // Editing: an unchanged text just closes the editor.
            if (text.trim() == target.text || vm.editChat(group.id, target.id, text)) {
                draft = ""; editing = null
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } else if (vm.sendChat(group.id, text, replyingTo)) {
            draft = ""; replyingTo = null
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val startReply: (ChatMessage) -> Unit = { m ->
        if (editing != null) { editing = null; draft = "" }
        replyingTo = m
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        runCatching { focus.requestFocus() }
    }
    val jumpTo: (ChatReply) -> Unit = { q ->
        val found = chat.messages.indexOfFirst { it.id == q.id }
        // The typing bubble, when shown, is the list's first item.
        val index = if (found < 0) -1 else found + if (typingRow) 1 else 0
        if (index < 0) Toast.makeText(context, "That message is further back. Scroll up to load it.", Toast.LENGTH_SHORT).show()
        else scope.launch {
            listState.animateScrollToItem(index)
            highlighted = q.id; delay(1400); if (highlighted == q.id) highlighted = null
        }
    }
    // "Seen by" goes under your newest message only, like Instagram.
    val myNewest = chat.messages.firstOrNull { it.uid == me && !it.pending }
    val seenByMyNewest = myNewest?.let { m -> others.filter { (chat.seenBy[it.uid] ?: 0L) >= m.sentAt } }.orEmpty()

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
                    chat.messages.isEmpty() -> {
                        EmptyChat(group.name, faces, onStarter = send,
                            modifier = Modifier.fillMaxSize().padding(top = topPad, bottom = bottomPad))
                        if (typingRow) TypingRow(chat.typing, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = bottomPad))
                    }
                    else -> LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topPad, bottom = bottomPad),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (typingRow) item(key = "typing") {
                            TypingRow(chat.typing, Modifier.animateItem())
                        }
                        itemsIndexed(chat.messages, key = { _, m -> m.id }) { i, m ->
                            MessageRow(
                                m = m, mine = m.uid == me, me = me,
                                older = chat.messages.getOrNull(i + 1), newer = chat.messages.getOrNull(i - 1),
                                highlighted = highlighted == m.id,
                                seen = if (m.id == myNewest?.id) seenByMyNewest else null,
                                othersCount = others.size,
                                onReply = { startReply(m) },
                                onQuoteClick = jumpTo,
                                onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); actionsFor = m },
                                onSeenClick = { seenSheetFor = m },
                            )
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
                        Column(Modifier.animateContentSize()) {
                            // Replying: the quoted message rides on top of the text field until you send or cancel.
                            // Editing: a bar naming what you are changing, with a cross that puts the draft back.
                            editing?.let { e ->
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    QuoteBlock(name = "Editing message", text = e.text, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { editing = null; draft = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Cancel edit") }
                                }
                            }
                            replyingTo?.let { r ->
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    QuoteBlock(
                                        name = if (r.uid == me) "yourself" else r.name, text = r.text, prefix = "Replying to ",
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { replyingTo = null }) { Icon(Icons.Rounded.Close, contentDescription = "Cancel reply") }
                                }
                            }
                            BasicTextField(
                                value = draft,
                                onValueChange = { if (it.length <= CHAT_MAX_CHARS) draft = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                                    .focusRequester(focus)
                                    .semantics { contentDescription = "Message ${group.name}" },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
                                cursorBrush = SolidColor(ink),
                                maxLines = 5,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                                decorationBox = { inner ->
                                    Box {
                                        if (draft.isEmpty()) Text(if (editing != null) "Edit message" else if (replyingTo != null) "Reply" else "Message", style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = 0.55f))
                                        inner()
                                    }
                                },
                            )
                        }
                    }
                    GlassCircleButton(if (editing != null) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Send, if (editing != null) "Save edit" else "Send", enabled = draft.isNotBlank()) { send(draft) }
                }
                if (draft.length > CHAT_MAX_CHARS - 80) Text("${draft.length}/$CHAT_MAX_CHARS",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            }
        }
    }

    // Long-press actions.
    actionsFor?.let { m ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SheetAction(Icons.AutoMirrored.Rounded.Reply, "Reply") { actionsFor = null; startReply(m) }
                SheetAction(Icons.Rounded.ContentCopy, "Copy text") { clipboard.setText(AnnotatedString(m.text)); actionsFor = null }
                if (m.uid == me && !m.pending) {
                    SheetAction(Icons.Rounded.Edit, "Edit") {
                        actionsFor = null; replyingTo = null; editing = m; draft = m.text
                        runCatching { focus.requestFocus() }
                    }
                    SheetAction(Icons.Rounded.Visibility, "Seen by") { actionsFor = null; seenSheetFor = m }
                    SheetAction(Icons.Rounded.DeleteOutline, "Delete for everyone", danger = true) { actionsFor = null; deleting = m }
                }
            }
        }
    }

    // Who has seen one of your messages, and who hasn't yet.
    seenSheetFor?.let { m ->
        val seen = others.filter { (chat.seenBy[it.uid] ?: 0L) >= m.sentAt }.sortedBy { chat.seenBy[it.uid] }
        val notYet = others.filter { it !in seen }
        ModalBottomSheet(onDismissRequest = { seenSheetFor = null }) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                item { Text("Seen by", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp)) }
                if (seen.isEmpty()) item { Text("Nobody yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
                items(seen.size) { i -> val p = seen[i]; ReaderRow(p, chat.seenBy[p.uid]?.let { "Seen ${timeLabel(it)}" }, faded = false) }
                if (notYet.isNotEmpty()) {
                    item { Text("Not seen yet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
                    items(notYet.size) { i -> ReaderRow(notYet[i], null, faded = true) }
                }
            }
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete message?") },
            text = { Text("It will be removed for everyone in ${group.name}.") },
            confirmButton = { TextButton(onClick = { vm.deleteChat(group.id, m.id); if (replyingTo?.id == m.id) replyingTo = null; if (editing?.id == m.id) { editing = null; draft = "" }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun ReaderRow(member: GroupMember, detail: String?, faded: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).graphicsLayer { alpha = if (faded) 0.5f else 1f }, verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(member.name, member.avatarId, 40)
        Spacer(Modifier.width(12.dp))
        Text(member.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** "Seen by Shreyansh", "Seen by A and B", "Seen by A, B and 3 others", or "Seen by everyone". */
private fun seenLabel(seen: List<GroupMember>, othersCount: Int): String {
    val names = seen.map { it.name.trim().substringBefore(' ').ifBlank { it.name } }
    return when {
        names.isEmpty() -> ""
        othersCount > 1 && names.size >= othersCount -> "Seen by everyone"
        names.size == 1 -> "Seen by ${names[0]}"
        names.size == 2 -> "Seen by ${names[0]} and ${names[1]}"
        names.size == 3 -> "Seen by ${names[0]}, ${names[1]} and ${names[2]}"
        else -> "Seen by ${names[0]}, ${names[1]} and ${names.size - 2} others"
    }
}

@Composable
private fun MessageRow(
    m: ChatMessage, mine: Boolean, me: String?, older: ChatMessage?, newer: ChatMessage?, highlighted: Boolean,
    seen: List<GroupMember>?, othersCount: Int,
    onReply: () -> Unit, onQuoteClick: (ChatReply) -> Unit, onLongPress: () -> Unit, onSeenClick: () -> Unit,
) {
    val newDay = older == null || !sameDay(older.sentAt, m.sentAt)
    val firstOfRun = newDay || older == null || older.uid != m.uid || m.sentAt - older.sentAt > RUN_GAP_MS || m.replyTo != null
    val lastOfRun = newer == null || newer.uid != m.uid || newer.sentAt - m.sentAt > RUN_GAP_MS || !sameDay(m.sentAt, newer.sentAt) || newer.replyTo != null
    val cs = MaterialTheme.colorScheme
    val avatarSize = 34.dp
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }
    val drag = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    val latestReply by rememberUpdatedState(onReply)
    val flash by animateFloatAsState(if (highlighted) 0.18f else 0f, label = "replyJumpFlash")

    Column(Modifier.fillMaxWidth().padding(top = if (firstOfRun) 10.dp else 0.dp)) {
        if (newDay) DayChip(m.sentAt)
        if (!mine && firstOfRun) Text(m.name, style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = avatarSize + 14.dp, bottom = 3.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(cs.primary.copy(alpha = flash))
                // Swipe right to reply. A tick at the threshold, and the reply arrow fades in behind.
                .pointerInput(m.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { armed = false },
                        onDragEnd = { if (armed) latestReply(); armed = false; scope.launch { drag.animateTo(0f, spring(0.6f, 500f)) } },
                        onDragCancel = { armed = false; scope.launch { drag.animateTo(0f, spring(0.6f, 500f)) } },
                    ) { change, dx ->
                        val next = (drag.value + dx * 0.7f).coerceIn(0f, trigger * 1.5f)
                        if (next > 0f) change.consume()
                        scope.launch { drag.snapTo(next) }
                        if (!armed && next >= trigger) { armed = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        else if (armed && next < trigger) armed = false
                    }
                },
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Reply, contentDescription = null, tint = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(22.dp)
                    .graphicsLayer { alpha = (drag.value / trigger).coerceIn(0f, 1f); val sc = 0.6f + 0.4f * alpha; scaleX = sc; scaleY = sc },
            )
            Row(
                Modifier.fillMaxWidth().offset { IntOffset(drag.value.roundToInt(), 0) },
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom,
            ) {
                if (!mine) {
                    // The illustration sits beside the last bubble of each run, like most chat apps.
                    Box(Modifier.size(avatarSize)) { if (lastOfRun) ProfileAvatar(m.name, m.avatarId, avatarSize.value.toInt()) }
                    Spacer(Modifier.width(8.dp))
                }
                Bubble(m, mine, me, lastOfRun, onLongPress, onQuoteClick, Modifier.widthIn(max = 290.dp))
            }
        }
        if (lastOfRun) Text(
            timeLabel(m.sentAt) + (if (m.edited) " · Edited" else "") + if (m.pending) " · Sending" else "",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            textAlign = if (mine) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(start = if (mine) 0.dp else avatarSize + 14.dp, end = if (mine) 6.dp else 0.dp, top = 3.dp),
        )
        if (!seen.isNullOrEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clip(CircleShape).clickable(onClickLabel = "See who has seen this", onClick = onSeenClick)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarStack(seen.take(3).map { it.name to it.avatarId }, 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(seenLabel(seen, othersCount), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The quoted message inside a reply: an accent bar, who said it, and the first lines of what they said. */
@Composable
private fun QuoteBlock(name: String, text: String, modifier: Modifier = Modifier, prefix: String = "", onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val ink = LocalContentColor.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(shape).background(ink.copy(alpha = 0.08f))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Go to the original message", onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(prefix + name, style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: ChatMessage, mine: Boolean, me: String?, tail: Boolean, onLongPress: () -> Unit, onQuoteClick: (ChatReply) -> Unit, modifier: Modifier) {
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
    Box(modifier.then(fill).combinedClickable(onClick = {}, onLongClickLabel = "Message options", onLongClick = onLongPress)
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            Column(Modifier.width(IntrinsicSize.Max)) {
                m.replyTo?.let { q ->
                    QuoteBlock(
                        name = if (q.uid == me) "You" else q.name, text = q.text,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), onClick = { onQuoteClick(q) },
                    )
                }
                Text(m.text, style = MaterialTheme.typography.bodyLarge, color = ink, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp))
            }
        }
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


/**
 * Who is typing: their avatars beside a bubble of three dots that rise and fall in turn, with
 * "Riya is typing" under it. Shown at the bottom of the chat while anyone else has a draft going.
 */
@Composable
private fun TypingRow(typing: List<TypingMember>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val glass = LocalGlassUi.current
    val avatarSize = 34.dp
    val shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val fill = if (glass) Modifier.glassPanel(shape, null) else Modifier.clip(shape).background(cs.surfaceContainerHigh)
    val label = when (typing.size) {
        1 -> "${typing[0].name} is typing"
        2 -> "${typing[0].name} and ${typing[1].name} are typing"
        else -> "${typing[0].name} and ${typing.size - 1} others are typing"
    }
    Column(modifier.fillMaxWidth().padding(top = 10.dp).semantics(mergeDescendants = true) { contentDescription = label }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(avatarSize + if (typing.size > 1) 10.dp else 0.dp), contentAlignment = Alignment.BottomStart) {
                if (typing.size == 1) ProfileAvatar(typing[0].name, typing[0].avatarId, avatarSize.value.toInt())
                else AvatarStack(typing.take(2).map { it.name to it.avatarId }, 28.dp)
            }
            Spacer(Modifier.width(8.dp))
            Box(fill.padding(horizontal = 16.dp, vertical = 14.dp)) { TypingDots(cs.onSurfaceVariant) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = avatarSize + 14.dp, top = 3.dp))
    }
}

/** Three dots that each lift and brighten in turn, on one shared 1.2 s loop. */
@Composable
private fun TypingDots(color: Color) {
    val loop = rememberInfiniteTransition(label = "typingDots")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "typingPhase")
    val lift = with(LocalDensity.current) { 4.dp.toPx() }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            // Each dot gets a bump in its own third of the loop, so the wave runs left to right.
            val local = ((t - i * 0.18f + 1f) % 1f)
            val bump = if (local < 0.4f) kotlin.math.sin(local / 0.4f * Math.PI.toFloat()) else 0f
            Box(
                Modifier.size(8.dp)
                    .graphicsLayer { translationY = -lift * bump }
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.45f + 0.55f * bump)),
            )
        }
    }
}
