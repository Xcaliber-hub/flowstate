package com.flowstate.focus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.launch

/** One choice in a screen's three-line menu. */
data class TopBarMenuItem(
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * What a screen shows in the shared frosted top bar. [progress] draws a small ring in the pill;
 * without it the pill shows [icon]. An empty [menu] hides the three-line button.
 */
data class TopBarSpec(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector? = null,
    val progress: Float? = null,
    val menuTitle: String = "",
    val menu: List<TopBarMenuItem> = emptyList(),
)

@Stable
class TopBarState {
    var spec by mutableStateOf<TopBarSpec?>(null)
    var menuOpen by mutableStateOf(false)
}

val LocalTopBarState = staticCompositionLocalOf { TopBarState() }

/** Height of the bar row; screens add this to their top padding (see [screenContentPadding]). */
val TopBarHeight: Dp = 56.dp

/** A screen declares its bar with this; the app draws it above the content so it can be frosted. */
@Composable
fun ScreenTopBar(spec: TopBarSpec) {
    val state = LocalTopBarState.current
    SideEffect { state.spec = spec }
    DisposableEffect(spec.key) {
        onDispose { if (state.spec?.key == spec.key) { state.spec = null; state.menuOpen = false } }
    }
}

/**
 * Floating frosted top bar, like a reader app's controls: a round back button, a pill with the
 * screen's title, and (when the screen has options) a round three-line button that opens a
 * frosted menu.
 */
@Composable
fun FrostedTopBar(state: TopBarState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val spec = state.spec ?: return
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().height(TopBarHeight),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onBack()
        }
        FrostedGlassSurface(CircleShape, Modifier.weight(1f).height(TopBarHeight)) {
            Row(
                Modifier.fillMaxSize().padding(start = 12.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    if (spec.progress != null) ProgressRing(spec.progress)
                    else spec.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    AutoSizeText(spec.title, style = MaterialTheme.typography.titleLarge, minSize = 14.sp)
                    if (spec.subtitle.isNotEmpty()) Text(
                        spec.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (spec.menu.isNotEmpty()) {
            GlassCircleButton(Icons.Rounded.Tune, if (state.menuOpen) "Close options" else "Options") {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); state.menuOpen = !state.menuOpen
            }
        }
    }
}

@Composable
private fun GlassCircleButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(0.4f, Spring.StiffnessMedium), label = "glassPress")
    FrostedGlassSurface(CircleShape, Modifier.size(TopBarHeight).scale(scale)) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(); pressed = true
                        val up = waitForUpOrCancellation()
                        pressed = false
                        if (up != null) onClick()
                    }
                }
                .semantics { contentDescription = label; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null) }
    }
}

@Composable
private fun ProgressRing(progress: Float) {
    val color = LocalContentColor.current
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "barRing")
    Canvas(Modifier.size(28.dp)) {
        val w = 4.dp.toPx()
        drawCircle(color.copy(alpha = 0.3f), style = Stroke(w))
        drawArc(color, -90f, 360f * animated, useCenter = false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/**
 * The frosted options menu under the three-line button. Drag a finger up and down it: a bubble
 * springs to the row under your finger (overshooting a little), the row swells, and you feel a
 * tick on each row. Lift to choose; tapping works too. Tapping outside closes it.
 */
@Composable
fun FrostedTopBarMenu(state: TopBarState, modifier: Modifier = Modifier) {
    val spec = state.spec
    val open = state.menuOpen && spec != null && spec.menu.isNotEmpty()
    if (open) {
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { state.menuOpen = false })
    }
    AnimatedVisibility(
        visible = open,
        modifier = modifier,
        enter = fadeIn() + scaleIn(spring(0.55f, Spring.StiffnessMedium), initialScale = 0.85f,
            transformOrigin = TransformOrigin(1f, 0f)),
        exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
    ) {
        val items = state.spec?.menu.orEmpty()
        if (items.isNotEmpty()) MenuBody(state.spec?.menuTitle.orEmpty(), items) { item ->
            if (item.enabled) item.onClick()
            state.menuOpen = false
        }
    }
}

private val RowHeight = 52.dp
private val RowGap = 4.dp

@Composable
private fun MenuBody(title: String, items: List<TopBarMenuItem>, onChoose: (TopBarMenuItem) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowPx = with(density) { (RowHeight + RowGap).toPx() }
    val selectedIndex = items.indexOfFirst { it.selected }.coerceAtLeast(0)
    // The gesture below outlives recompositions, so it reads the latest rows and callback through these.
    val latestItems by rememberUpdatedState(items)
    val latestChoose by rememberUpdatedState(onChoose)
    val latestSelected by rememberUpdatedState(selectedIndex)
    var hover by remember { mutableIntStateOf(-1) }
    val bubbleIndex = if (hover >= 0) hover else selectedIndex
    // The bubble overshoots and settles, which is what makes dragging across rows feel bouncy.
    val bubbleY = remember { Animatable(selectedIndex * rowPx) }
    val bubbleStretch = remember { Animatable(1f) }
    // Pulling past the first or last row rubber-bands the rows, then they spring back on release.
    val pull = remember { Animatable(0f) }
    // ColorOS-style "jelly" motion: the bubble lags behind the finger, stretches along the way it is
    // travelling (and thins to keep its volume), then wobbles back into shape when it lands.
    fun moveTo(i: Int) {
        val distance = kotlin.math.abs(i * rowPx - bubbleY.targetValue) / rowPx
        scope.launch { bubbleY.animateTo(i * rowPx, spring(dampingRatio = 0.5f, stiffness = 380f)) }
        if (distance > 0f) scope.launch {
            bubbleStretch.animateTo(1f + 0.22f * distance.coerceAtMost(2f), spring(dampingRatio = 1f, stiffness = 1400f))
            bubbleStretch.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 300f))
        }
    }

    FrostedGlassSurface(RoundedCornerShape(28.dp), Modifier.width(240.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            if (title.isNotEmpty()) Text(
                title, style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
            )
            Box(
                Modifier.fillMaxWidth()
                    .height(RowHeight * items.size + RowGap * (items.size - 1))
                    .pointerInput(items.size) {
                        fun indexAt(y: Float) = (y / rowPx).toInt().coerceIn(0, items.lastIndex)
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var current = indexAt(down.position.y)
                            hover = current; moveTo(current)
                            var lastY = down.position.y
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                lastY = change.position.y
                                val over = when {
                                    lastY < 0f -> lastY
                                    lastY > size.height -> lastY - size.height
                                    else -> 0f
                                }
                                scope.launch { pull.snapTo(over * 0.22f) }
                                if (change.position.x < -40f || change.position.x > size.width + 40f) { cancelled = true }
                                val i = indexAt(lastY)
                                if (i != current) {
                                    current = i; hover = i; moveTo(i)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                if (change.positionChange() != Offset.Zero) change.consume()
                            }
                            hover = -1
                            scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 420f)) }
                            if (!cancelled) latestChoose(latestItems[current.coerceAtMost(latestItems.lastIndex)]) else moveTo(latestSelected)
                        }
                    }
                    // After pointerInput, so the finger is tracked in the unmoved coordinates.
                    .graphicsLayer { translationY = pull.value },
            ) {
                val bubbleColor = Glass.selectedFill()
                val bubbleRim = Glass.selectedBorder()
                Box(
                    Modifier.fillMaxWidth().height(RowHeight)
                        .offset { IntOffset(0, bubbleY.value.toInt()) }
                        .graphicsLayer { scaleY = bubbleStretch.value; scaleX = 1f / kotlin.math.sqrt(bubbleStretch.value) }
                        .clip(CircleShape).background(bubbleColor).border(1.dp, bubbleRim, CircleShape),
                )
                items.forEachIndexed { i, item ->
                    val lifted = i == bubbleIndex
                    val rowScale by animateFloatAsState(
                        if (lifted && hover >= 0) 1.06f else 1f,
                        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "rowScale",
                    )
                    Row(
                        Modifier.fillMaxWidth().height(RowHeight)
                            .offset(y = (RowHeight + RowGap) * i)
                            .scale(rowScale)
                            .padding(horizontal = 16.dp)
                            .semantics(mergeDescendants = true) { selected = item.selected; role = Role.RadioButton },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                color = LocalContentColor.current.copy(alpha = if (item.enabled) 1f else 0.45f))
                            item.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    color = LocalContentColor.current.copy(alpha = 0.65f))
                            }
                        }
                        if (item.selected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
