package com.flowstate.focus.ui.focus

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowstate.focus.domain.SubjectChoices
import com.flowstate.focus.ui.components.glassPanel

/** Longest custom chip before its text is cut with an ellipsis. */
private val CustomChipMaxWidth = 200.dp
private val ChipShape = RoundedCornerShape(50)

/**
 * 1.8.4: the session subject on Focus, as one scrollable row of pills (Physics, Chemistry, Maths,
 * Biology, the remembered custom subjects, then "+ Custom"). Single select; tapping the selected pill
 * clears it, so a session can still have no subject. A [subject] that is not one of the four (typed,
 * or prefilled by the syllabus "Focus on this") shows as a selected custom pill.
 *
 * The selected pill is filled with the theme's secondary colour (light grey on Slate); the rest are
 * outlined, on frosted glass when [glass] is on and flat otherwise. Android-free so the desktop
 * renderer can draw it.
 */
@Composable
fun SubjectPicker(
    subject: String,
    recents: List<String>,
    glass: Boolean,
    onSubject: (String) -> Unit,
    onCustomSubject: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLength: Int = 60,
    /** Opens with the "+ Custom" dialog showing; for previews. */
    startEditing: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    val current = subject.trim()
    val preset = SubjectChoices.presetFor(current)
    val customs = SubjectChoices.customChips(current, recents)
    Column(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("Subject", style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text("optional", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline())
        }
        val scroll = rememberScrollState()
        // A selected pill past the edge (a prefilled chapter, a remembered subject) is scrolled into
        // view. Done on the row's own scroll, so the screen around it never moves.
        val edge = with(LocalDensity.current) { 40.dp.roundToPx() } // clear of the faded edge
        var selectedSpan by remember { mutableStateOf<IntRange?>(null) }
        LaunchedEffect(current, selectedSpan, scroll.viewportSize) {
            if (current.isEmpty()) return@LaunchedEffect
            val span = selectedSpan ?: return@LaunchedEffect
            val view = scroll.viewportSize
            if (view <= 0) return@LaunchedEffect
            when {
                span.last > scroll.value + view -> scroll.animateScrollTo(span.last - view + edge)
                span.first < scroll.value -> scroll.animateScrollTo((span.first - edge).coerceAtLeast(0))
            }
        }
        val track = { on: Boolean ->
            if (on) Modifier.onPlaced { val x = it.positionInParent().x.toInt(); selectedSpan = x..(x + it.size.width) } else Modifier
        }
        Row(
            Modifier.fillMaxWidth().fadeEdges(scroll).horizontalScroll(scroll).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubjectChoices.Presets.forEach { p ->
                val on = preset == p
                SubjectChip(p, on, glass, enabled, track(on)) { onSubject(if (on) "" else p) }
            }
            customs.forEach { c ->
                val on = preset == null && c.equals(current, ignoreCase = true)
                SubjectChip(c, on, glass, enabled, Modifier.widthIn(max = CustomChipMaxWidth).then(track(on))) { onSubject(if (on) "" else c) }
            }
            SubjectChip("Custom", false, glass, enabled, icon = Icons.Rounded.Add) { editing = true }
        }
    }
    if (editing && enabled) {
        CustomSubjectDialog(
            initial = if (preset == null) current else "",
            maxLength = maxLength,
            onDismiss = { editing = false },
            onDone = { editing = false; onCustomSubject(it) },
        )
    }
}

/** Fades whichever end of the row has more pills past it, so the row reads as scrollable. */
private fun Modifier.fadeEdges(scroll: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val w = 24.dp.toPx()
        if (scroll.canScrollBackward) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w), size = size.copy(width = w), blendMode = BlendMode.DstIn)
        }
        if (scroll.canScrollForward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = size.copy(width = w), blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun SubjectChip(
    label: String,
    selected: Boolean,
    glass: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val surface = when {
        selected -> Modifier.background(cs.secondary, ChipShape)
        glass -> Modifier.glassPanel(ChipShape)
        else -> Modifier.border(1.dp, cs.outlineVariant, ChipShape)
    }
    val content = if (selected) cs.onSecondary else cs.onSurface
    Row(
        modifier
            .height(36.dp)
            .then(surface)
            .selectable(selected = selected, enabled = enabled, role = if (icon == null) Role.RadioButton else Role.Button, onClick = onClick)
            .padding(start = if (selected || icon != null) 10.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lead = if (selected) Icons.Rounded.Check else icon
        if (lead != null) {
            Icon(lead, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "+ Custom": type a subject of your own. */
@Composable
fun CustomSubjectDialog(initial: String, maxLength: Int, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val ok = SubjectChoices.clean(text).isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom subject") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace('\n', ' ').take(maxLength) },
                placeholder = { Text("e.g. Organic chemistry", maxLines = 1) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onDone(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = ok) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
