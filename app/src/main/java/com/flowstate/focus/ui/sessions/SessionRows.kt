package com.flowstate.focus.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.ui.components.AutoSizeText
import com.flowstate.focus.ui.components.formatDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * The session row and its delete confirmation, shared by the Focus screen's "Today's sessions" card and
 * Settings › This week's sessions (1.7.2), so hold-to-delete looks and works the same in both.
 */

private val SessionTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun sessionTime(s: SessionEntity): String =
    Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault()).format(SessionTimeFormat)

/** Subject if one was given, else the mode. Old sessions recorded in the retired Flow mode still read "Flow". */
fun sessionLabel(s: SessionEntity): String = s.subject.ifBlank { TimerMode.labelFor(s.mode) }

/** One past session: start time, subject and length. Hold it to ask for deletion. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(s: SessionEntity, onHold: (SessionEntity) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold(s)
                },
                onLongClickLabel = "Delete session",
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoSizeText(
            sessionTime(s),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            sessionLabel(s),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(formatDuration(s.durationMs), style = MaterialTheme.typography.bodyLarge)
    }
}

/** "Delete this session?" Confirm calls [onDelete]; both buttons close it through [onDismiss]. */
@Composable
fun SessionDeleteDialog(session: SessionEntity, onDismiss: () -> Unit, onDelete: (SessionEntity) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this session?") },
        text = {
            Text(
                "${formatDuration(session.durationMs)} of ${sessionLabel(session)} at ${sessionTime(session)} will be removed " +
                    "from your history, stats and streak. This can't be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDelete(session)
                onDismiss()
            }) { AutoSizeText("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { AutoSizeText("Cancel") } },
    )
}
