package com.flowstate.focus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class NavTab(val route: String, val label: String, val icon: ImageVector)

/** Height of the bottom nav pill; the collapsed nav is a circle of the same size. */
val NavBarHeight = 64.dp

/** The light blur used by the bottom nav and the timer bar (kept small for mid-range phones). */
private val DockBlur = 16.dp

/**
 * 1.8.2: the bottom navigation dock. The nav pill never hides any more. While [collapsed] (the user
 * scrolled down) it shrinks into a circle on the left showing only the selected tab's icon, and a
 * tap on the circle brings the full pill back.
 *
 * [timerBar], when set, is a floating bar for a running or paused session. Expanded it sits above the
 * nav pill at full width; collapsed it slides down into the nav row, to the right of the circle, and
 * fills the rest of the width, like a music app's mini player. Position and size follow the same
 * spring as the nav morph.
 *
 * Pressing the expanded pill and dragging sideways moves the highlight with the finger; letting go
 * selects the tab under it. Taps still work as before.
 */
@Composable
fun BottomNavDock(
    tabs: List<NavTab>,
    selectedRoute: String?,
    collapsed: Boolean,
    onSelect: (NavTab) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    timerBar: (@Composable () -> Unit)? = null,
) {
    // Liquid glass morphs with a springy overshoot, like a droplet; frosted glass uses a calm critically damped spring.
    val liquid = liquidGlassDrawn()
    val progress = animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = if (liquid) spring(dampingRatio = 0.8f, stiffness = 380f) else spring(dampingRatio = 1f, stiffness = 700f),
        label = "navCollapse",
    )
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        val fullWidth = maxWidth
        val showTimer = timerBar != null
        // Keep the last timer content around so it can fade out after the session ends.
        val lastTimer = remember { arrayOfNulls<@Composable () -> Unit>(1) }
        if (timerBar != null) lastTimer[0] = timerBar
        Layout(
            content = {
                // Child 0: the nav pill / circle.
                NavPill(
                    tabs = tabs,
                    selectedRoute = selectedRoute,
                    collapsed = collapsed,
                    progress = { progress.value },
                    liquid = liquid,
                    fullWidth = fullWidth,
                    onSelect = onSelect,
                    onExpand = onExpand,
                )
                // Child 1: the timer bar slot. The outer Box always exists so the layout has two children.
                Box(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = showTimer,
                        enter = fadeIn() + scaleIn(initialScale = 0.9f),
                        exit = fadeOut() + scaleOut(targetScale = 0.9f),
                    ) {
                        Box(Modifier.fillMaxSize()) { lastTimer[0]?.invoke() }
                    }
                }
            },
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = NavBarHeight.roundToPx()
            val gap = TimerBarGap.roundToPx()
            val th = TimerBarHeight.roundToPx()
            val p = progress.value
            val pc = p.coerceIn(0f, 1f)
            // The pill shrinks from the right into a circle anchored on the left.
            // 1.8.7: coerceIn(h, w) throws when the dock is measured narrower than one nav height (split screen,
            // very large display size, a zero-width first pass); the lower bound now never exceeds the width.
            val minW = minOf(h, w).coerceAtLeast(0)
            val navW = (w + (h - w) * p).roundToInt().coerceIn(minW, w.coerceAtLeast(minW))
            val nav = measurables[0].measure(Constraints.fixed(navW, h))
            // Constant height (room for the timer above the nav) so nothing jumps when a session starts or ends.
            // The empty space has no pointer input, so touches there reach the screen below.
            val total = th + gap + h
            // Timer: full width above the nav when expanded; in the nav row right of the circle when collapsed.
            val tx = ((h + gap) * p).roundToInt().coerceAtLeast(0)
            val tw = (w - tx).coerceIn(minW, w.coerceAtLeast(minW))
            val tH = (th + (h - th) * pc).roundToInt()
            val ty = ((th + gap) * p).roundToInt()
            val timer = measurables[1].measure(Constraints.fixed(tw, tH))
            layout(w, total) {
                nav.place(0, total - h)
                timer.place(tx, ty.coerceIn(0, (total - tH).coerceAtLeast(0)))
            }
        }
    }
}

@Composable
private fun NavPill(
    tabs: List<NavTab>,
    selectedRoute: String?,
    collapsed: Boolean,
    progress: () -> Float,
    liquid: Boolean,
    fullWidth: androidx.compose.ui.unit.Dp,
    onSelect: (NavTab) -> Unit,
    onExpand: () -> Unit,
) {
    val selectedTab = tabs.firstOrNull { it.route == selectedRoute }
    // Liquid glass: the tab highlight is a lens the bar's shader bends (see LiquidGlass.kt).
    val lens = remember { LiquidLens() }
    GlassBarSurface(tinted = true, blurRadius = DockBlur, lens = lens, modifier = Modifier.fillMaxSize()) {
        // The full tab row keeps its expanded width and is clipped by the shrinking pill while it fades.
        Box(
            Modifier
                .fillMaxHeight()
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(fullWidth)
                .graphicsLayer { alpha = (1f - progress() * 1.8f).coerceIn(0f, 1f) },
        ) {
            // Stays composed while collapsed (invisible and covered by the expand target) so the highlight keeps its place.
            TabRow(tabs, selectedRoute, liquid, lens, enabled = !collapsed, onSelect = onSelect)
        }
        // Collapsed: the selected tab's icon, centred in the circle. Tapping it expands the bar.
        if (selectedTab != null) {
            Box(
                Modifier
                    .size(NavBarHeight)
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        val a = ((progress() - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        alpha = a
                        scaleX = 0.7f + 0.3f * a
                        scaleY = 0.7f + 0.3f * a
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(selectedTab.icon, contentDescription = null, tint = LocalContentColor.current)
            }
        }
        if (collapsed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Show navigation" + (selectedTab?.let { ", ${it.label} selected" } ?: "") }
                    .clickable(onClick = onExpand),
            )
        }
    }
}

/** Equal slots, icon over label, and one highlight capsule that slides between them (or follows a drag). */
@Composable
private fun TabRow(
    tabs: List<NavTab>,
    selectedRoute: String?,
    liquid: Boolean,
    lens: LiquidLens,
    enabled: Boolean,
    onSelect: (NavTab) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val selectedIndex = tabs.indexOfFirst { it.route == selectedRoute }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selectedIndex)
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
        val slotPx = constraints.maxWidth / tabs.size.coerceAtLeast(1).toFloat()
        val maxX = slotPx * (tabs.size - 1)
        val indicatorX = remember { Animatable(slotPx * selectedIndex.coerceAtLeast(0)) }
        var dragging by remember { mutableStateOf(false) }
        val moveSpec = if (liquid) spring<Float>(0.75f, Spring.StiffnessMediumLow) else spring<Float>(1f, Spring.StiffnessMedium)
        LaunchedEffect(selectedIndex, slotPx) {
            if (!dragging && selectedIndex >= 0) indicatorX.animateTo(slotPx * selectedIndex, moveSpec)
        }
        // The tab under the highlight right now; labels brighten as it passes during a drag.
        val hovered by remember(slotPx) {
            derivedStateOf { ((indicatorX.value + slotPx / 2f) / slotPx).toInt().coerceIn(0, tabs.size - 1) }
        }
        // Liquid glass: 0 = the resting droplet, 1 = the magnifying bubble while dragging. It springs back
        // (with a little wobble) when the finger lifts.
        val lensStrength = animateFloatAsState(
            targetValue = if (dragging) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 420f),
            label = "lensStrength",
        )
        // The lens in the glass surface's pixels: this row sits at its start edge, inset by the 4 dp padding.
        val padPx = with(LocalDensity.current) { 4.dp.toPx() }
        val rowHeightPx = constraints.maxHeight.toFloat()
        lens.bounds = {
            if (selectedIndex < 0) androidx.compose.ui.geometry.Rect.Zero
            else androidx.compose.ui.geometry.Rect(padPx + indicatorX.value, padPx, padPx + indicatorX.value + slotPx, padPx + rowHeightPx)
        }
        lens.strength = { lensStrength.value }
        // Highlight capsule.
        if (selectedIndex >= 0) {
            val slotDp = with(LocalDensity.current) { slotPx.toDp() }
            if (liquid) {
                LiquidDroplet(
                    strength = { lensStrength.value },
                    modifier = Modifier
                        .offset { IntOffset(indicatorX.value.roundToInt(), 0) }
                        .width(slotDp)
                        .fillMaxHeight(),
                )
            } else {
                Box(
                    Modifier
                        .offset { IntOffset(indicatorX.value.roundToInt(), 0) }
                        .width(slotDp)
                        .fillMaxHeight()
                        .graphicsLayer { if (dragging) { scaleX = 1.06f; scaleY = 1.06f } }
                        .background(Glass.selectedFill(), CircleShape)
                        .border(1.dp, Glass.selectedBorder(), CircleShape),
                )
            }
        }
        Row(
            Modifier
                .fillMaxSize()
                .pointerInput(tabs.size, slotPx, enabled) {
                    if (!enabled) return@pointerInput
                    var lastHover = -1
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            dragging = true
                            lastHover = currentSelected
                            scope.launch { indicatorX.animateTo((start.x - slotPx / 2f).coerceIn(0f, maxX), spring(1f, Spring.StiffnessHigh)) }
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            scope.launch { indicatorX.snapTo((change.position.x - slotPx / 2f).coerceIn(0f, maxX)) }
                            val over = (change.position.x / slotPx).toInt().coerceIn(0, tabs.size - 1)
                            if (over != lastHover) {
                                lastHover = over
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            val target = ((indicatorX.value + slotPx / 2f) / slotPx).toInt().coerceIn(0, tabs.size - 1)
                            dragging = false
                            scope.launch { indicatorX.animateTo(slotPx * target, moveSpec) }
                            if (target != currentSelected) currentOnSelect(tabs[target])
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { indicatorX.animateTo(slotPx * currentSelected.coerceAtLeast(0), moveSpec) }
                        },
                    )
                },
            horizontalArrangement = Arrangement.Start,
        ) {
            tabs.forEachIndexed { i, tab ->
                val selected = i == selectedIndex
                val lit = if (dragging) i == hovered else selected
                NavSlot(
                    tab = tab,
                    selected = selected,
                    lit = lit,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    if (!selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(tab)
                    }
                }
            }
        }
    }
}

/**
 * Liquid glass tab highlight. At rest it is a small glass droplet (a faint fill and a lit rim). While
 * dragging it swells into a clear bubble: the fill fades so the bar's shader lens (magnification and
 * rainbow fringe) shows through, and the rim brightens. Everything is read in the draw phase.
 */
@Composable
private fun LiquidDroplet(strength: () -> Float, modifier: Modifier) {
    val dark = isDarkCanvas()
    val fill = LiquidColors.dropletFill()
    Box(
        modifier
            .graphicsLayer {
                val s = strength()
                scaleX = 1f + 0.08f * s
                scaleY = 1f + 0.10f * s
            }
            .drawWithCache {
                val outline = CircleShape.createOutline(size, layoutDirection, this)
                val mid = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val restRim = LiquidColors.rimBrush(dark, mid, 0.7f)
                val bubbleRim = LiquidColors.rimBrush(dark, mid, 1.3f)
                val rimWidth = 1.5.dp.toPx()
                onDrawBehind {
                    val s = strength().coerceIn(0f, 1f)
                    drawOutline(outline, fill.copy(alpha = fill.alpha * (1f - 0.75f * s)))
                    drawOutline(outline, restRim, alpha = 1f - s, style = Stroke(rimWidth))
                    if (s > 0.01f) drawOutline(outline, bubbleRim, alpha = s, style = Stroke(rimWidth * 1.4f))
                }
            },
    )
}

@Composable
private fun NavSlot(tab: NavTab, selected: Boolean, lit: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val base = LocalContentColor.current
    val tint = if (lit) base else base.copy(alpha = 0.68f)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
    }
}
