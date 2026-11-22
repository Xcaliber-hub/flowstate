package com.flowstate.focus.ui.components

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
 * Pressing the expanded pill pops the highlight out as a glass lens (liquid glass, Android 13+) that follows
 * the finger; letting go selects the tab under it and the lens springs back into the bar. Taps pop it too.
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
    // Liquid glass: the tab highlight is a lens (see LiquidGlass.kt).
    val lens = remember { LiquidLens() }
    // Android 13+: the highlight pops out of the bar as a real glass bubble that bends the bar and its icons.
    // Older phones keep the earlier in-bar lens and outline bubble.
    val popOut = liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val barLayer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize()) {
        GlassBarSurface(
            tinted = true,
            blurRadius = DockBlur,
            lens = if (popOut) null else lens,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (popOut) Modifier.drawWithContent {
                        // A recording of the finished bar (glass, highlight and icons) for the pop-out bubble to bend.
                        barLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(barLayer)
                    } else Modifier,
                ),
        ) {
            // The full tab row keeps its expanded width and is clipped by the shrinking pill while it fades.
            Box(
                Modifier
                    .fillMaxHeight()
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width(fullWidth)
                    .graphicsLayer { alpha = (1f - progress() * 1.8f).coerceIn(0f, 1f) },
            ) {
                // Stays composed while collapsed (invisible and covered by the expand target) so the highlight keeps its place.
                TabRow(tabs, selectedRoute, liquid, popOut, lens, enabled = !collapsed, onSelect = onSelect)
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
        // Drawn after the bar so it can spill past its rim (GlassBarSurface clips its own content).
        if (popOut) {
            LiquidPopBubble(lens, barLayer, visible = { (1f - progress() * 1.8f).coerceIn(0f, 1f) }, modifier = Modifier.matchParentSize())
        } else if (liquid) {
            LiquidTabBubble(lens, Modifier.matchParentSize())
        }
    }
}

/**
 * Android 13+: the pop-out glass lens, after iOS 26 and the reference. While a finger is on the bar (a tap or a
 * drag) and while the highlight travels to a new tab, the resting droplet swells into a clear stadium-shaped
 * lens about 1.3x the bar's height that rises past its top and bottom rim. It refracts everything under it (the
 * screen behind, the bar's glass and the icons), magnifies a little in the middle, bends hard and fringes at the
 * rim, stretches sideways with its speed, and springs back into the droplet when it settles.
 */
@Composable
private fun LiquidPopBubble(lens: LiquidLens, bar: GraphicsLayer, visible: () -> Float, modifier: Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val source = LocalBlurSource.current ?: return
    val dark = isDarkCanvas()
    val bg = MaterialTheme.colorScheme.background
    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) BubbleShader() else null }
    val bubbleLayer = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawWithCache {
                val pad = 4.dp.toPx()
                val margin = 16.dp.toPx()
                val refraction = 16.dp.toPx()
                val aberration = 2.6.dp.toPx()
                val rimPx = 1.dp.toPx()
                val outlinePx = 1.4.dp.toPx()
                val restH = size.height - pad * 2f
                val popH = size.height * 1.26f
                onDrawBehind {
                    val s = lens.strength()
                    val fade = visible()
                    if (s < 0.015f || fade < 0.01f) return@onDrawBehind
                    val b = lens.bounds()
                    if (b.width < 1f) return@onDrawBehind
                    val sc = s.coerceIn(0f, 1.15f)
                    val popW = maxOf(b.width * 1.30f, popH * 1.12f)
                    val st = lens.stretch().coerceIn(-1f, 1f)
                    val sa = kotlin.math.abs(st) * sc.coerceAtMost(1f)
                    val w = (b.width + (popW - b.width) * sc) * (1f + 0.26f * sa)
                    val h = (restH + (popH - restH) * sc) * (1f - 0.10f * sa)
                    // The bubble trails its motion a touch, like a drop dragged across glass.
                    val cx = b.center.x - st * 0.06f * w
                    val cy = size.height / 2f
                    val left = cx - w / 2f
                    val top = cy - h / 2f
                    val layerW = kotlin.math.ceil(w + margin * 2f).toInt().coerceAtLeast(1)
                    val layerH = kotlin.math.ceil(h + margin * 2f).toInt().coerceAtLeast(1)
                    if (shader == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@onDrawBehind
                    bubbleLayer.renderEffect = shader.effect(
                        centerX = margin + w / 2f, centerY = margin + h / 2f, halfWidth = w / 2f, halfHeight = h / 2f,
                        refraction = refraction * sc.coerceAtMost(1f), aberration = aberration * sc.coerceAtMost(1f),
                        magnify = 1f + 0.14f * sc.coerceAtMost(1f), strength = sc, dark = dark,
                    )
                    bubbleLayer.alpha = (fade * (s / 0.12f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    // What lies under the bubble: the screen (also past the bar's rim) with the bar and its icons on top.
                    bubbleLayer.record(IntSize(layerW, layerH)) {
                        drawRect(bg)
                        translate(margin - left - origin.x, margin - top - origin.y) { drawLayer(source) }
                        translate(margin - left, margin - top) { drawLayer(bar) }
                    }
                    translate(left - margin, top - margin) { drawLayer(bubbleLayer) }
                    // A faint dark outline and a lit rim so the lens reads on any background.
                    val a = (fade * sc.coerceAtMost(1f)).coerceIn(0f, 1f)
                    val corner = CornerRadius(minOf(w, h) / 2f)
                    drawRoundRect(
                        Color.Black.copy(alpha = if (dark) 0.22f else 0.10f),
                        topLeft = Offset(left - outlinePx / 2f, top - outlinePx / 2f),
                        size = Size(w + outlinePx, h + outlinePx),
                        cornerRadius = CornerRadius(corner.x + outlinePx / 2f),
                        alpha = a,
                        style = Stroke(outlinePx),
                    )
                    drawRoundRect(
                        LiquidColors.rimBrush(dark, Offset(cx, cy), 1.25f),
                        topLeft = Offset(left + rimPx / 2f, top + rimPx / 2f),
                        size = Size(w - rimPx, h - rimPx),
                        cornerRadius = CornerRadius(corner.x - rimPx / 2f),
                        alpha = a,
                        style = Stroke(rimPx),
                    )
                }
            },
    )
}

/**
 * Android 12 / 12L fallback for the pop-out bubble (no AGSL there): while a tab is being pressed the highlight
 * swells past the bar's rim as a clear circle with a thin lit rim that follows the finger, then springs back into
 * the bar when it is let go. It is drawn outside [GlassBarSurface]'s clip, which would otherwise cut it off.
 */
@Composable
private fun LiquidTabBubble(lens: LiquidLens, modifier: Modifier) {
    val dark = isDarkCanvas()
    Box(
        modifier.drawWithCache {
            val mid = Offset(size.width / 2f, size.height / 2f)
            val rim = LiquidColors.rimBrush(dark, mid, 1.1f)
            val fill = if (dark) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.08f) // clear glass
            val shade = Color.Black.copy(alpha = if (dark) 0.10f else 0.04f)
            val rimPx = 1.1.dp.toPx()
            onDrawBehind {
                val s = lens.strength().coerceIn(0f, 1f)
                if (s < 0.02f) return@onDrawBehind
                val b = lens.bounds()
                if (b.width < 1f) return@onDrawBehind
                val r = size.height * (0.5f + 0.34f * s)
                val c = Offset(b.center.x, size.height / 2f)
                drawCircle(fill.copy(alpha = fill.alpha * s), radius = r, center = c)
                drawCircle(shade.copy(alpha = shade.alpha * s), radius = r, center = c, style = Stroke(1.6.dp.toPx()))
                drawCircle(rim, radius = r - rimPx, center = c, alpha = s, style = Stroke(rimPx * (1.1f + 0.5f * s)))
            }
        },
    )
}

/**
 * Equal slots, icon only, and one highlight that slides between them. One gesture handles taps and drags:
 * finger down pops the lens and moves it toward the finger, it follows a drag, and lifting selects the tab under
 * the finger while the lens springs onto it (overshooting a little) and settles back into the droplet.
 */
@Composable
private fun TabRow(
    tabs: List<NavTab>,
    selectedRoute: String?,
    liquid: Boolean,
    popOut: Boolean,
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
        val count = tabs.size.coerceAtLeast(1)
        val slotPx = constraints.maxWidth / count.toFloat()
        val maxX = slotPx * (count - 1)
        val indicatorX = remember { Animatable(slotPx * selectedIndex.coerceAtLeast(0)) }
        var pressed by remember { mutableStateOf(false) }
        // Liquid: a jelly spring that overshoots a little before it settles, like the reference.
        val moveSpec = if (liquid) spring<Float>(dampingRatio = 0.62f, stiffness = 380f) else spring<Float>(1f, Spring.StiffnessMedium)
        LaunchedEffect(selectedIndex, slotPx) {
            if (!pressed && selectedIndex >= 0) indicatorX.animateTo(slotPx * selectedIndex, moveSpec)
        }
        // The tab under the highlight right now; icons brighten as it passes.
        val hovered by remember(slotPx) {
            derivedStateOf { ((indicatorX.value + slotPx / 2f) / slotPx).toInt().coerceIn(0, count - 1) }
        }
        // Still travelling to a tab: the lens stays popped out until it has nearly arrived.
        val travelling by remember(slotPx) {
            derivedStateOf { indicatorX.isRunning && abs(indicatorX.targetValue - indicatorX.value) > slotPx * 0.12f }
        }
        // 0 = the resting droplet inside the bar, 1 = the popped-out lens. Springs with a little wobble.
        val lensStrength = animateFloatAsState(
            targetValue = if (liquid && (pressed || travelling)) 1f else 0f,
            animationSpec = if (popOut) spring(dampingRatio = 0.55f, stiffness = 360f) else spring(dampingRatio = 0.5f, stiffness = 420f),
            label = "lensStrength",
        )
        // Sideways stretch from the highlight's speed (signed), smoothed with a soft spring so it wobbles back.
        val stretch = remember { Animatable(0f) }
        LaunchedEffect(slotPx) {
            snapshotFlow {
                indicatorX.value
                if (indicatorX.isRunning) (indicatorX.velocity / (slotPx * 12f)).coerceIn(-1f, 1f) else 0f
            }.collectLatest { target -> stretch.animateTo(target, spring(dampingRatio = 0.5f, stiffness = 500f)) }
        }
        // The lens in the glass surface's pixels: this row sits at its start edge, inset by the 4 dp padding.
        val padPx = with(LocalDensity.current) { 4.dp.toPx() }
        val rowHeightPx = constraints.maxHeight.toFloat()
        lens.bounds = {
            if (selectedIndex < 0) androidx.compose.ui.geometry.Rect.Zero
            else androidx.compose.ui.geometry.Rect(padPx + indicatorX.value, padPx, padPx + indicatorX.value + slotPx, padPx + rowHeightPx)
        }
        lens.strength = { lensStrength.value }
        lens.stretch = { stretch.value }
        // Highlight capsule.
        if (selectedIndex >= 0) {
            val slotDp = with(LocalDensity.current) { slotPx.toDp() }
            if (liquid) {
                LiquidDroplet(
                    strength = { lensStrength.value },
                    popOut = popOut,
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
                        .graphicsLayer { if (pressed) { scaleX = 1.06f; scaleY = 1.06f } }
                        .background(Glass.selectedFill(), CircleShape)
                        .border(1.dp, Glass.selectedBorder(), CircleShape),
                )
            }
        }
        Row(
            Modifier
                .fillMaxSize()
                .pointerInput(count, slotPx, enabled) {
                    if (!enabled) return@pointerInput
                    fun slotAt(x: Float) = (x / slotPx).toInt().coerceIn(0, count - 1)
                    fun follow(x: Float) = (x - slotPx / 2f).coerceIn(0f, maxX)
                    val followSpec = spring<Float>(dampingRatio = 0.82f, stiffness = 900f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        var lastX = down.position.x
                        var lastHover = slotAt(lastX)
                        if (lastHover != currentSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { indicatorX.animateTo(follow(lastX), followSpec) }
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    released = true
                                    lastX = change.position.x
                                    change.consume()
                                    break
                                }
                                if (change.positionChange() != Offset.Zero) {
                                    change.consume()
                                    lastX = change.position.x
                                    scope.launch { indicatorX.animateTo(follow(lastX), followSpec) }
                                    val over = slotAt(lastX)
                                    if (over != lastHover) {
                                        lastHover = over
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        } finally {
                            pressed = false
                            val target = if (released) slotAt(lastX) else currentSelected.coerceAtLeast(0)
                            scope.launch { indicatorX.animateTo(slotPx * target, moveSpec) }
                            if (released && target != currentSelected) currentOnSelect(tabs[target])
                        }
                    }
                },
            horizontalArrangement = Arrangement.Start,
        ) {
            tabs.forEachIndexed { i, tab ->
                val selected = i == selectedIndex
                val lit = if (pressed || travelling) i == hovered else selected
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
 * Liquid glass tab highlight. At rest it is a small glass droplet (a faint fill and a lit rim). When it pops out
 * ([popOut], Android 13+) it fades away entirely and the pop-out bubble takes over from the same size, so the
 * droplet looks like it swells into the lens. Without pop-out it swells into a clear in-bar bubble as before.
 * Everything is read in the draw phase.
 */
@Composable
private fun LiquidDroplet(strength: () -> Float, popOut: Boolean, modifier: Modifier) {
    val dark = isDarkCanvas()
    val fill = LiquidColors.dropletFill()
    Box(
        modifier
            .graphicsLayer {
                val s = strength().coerceAtLeast(0f)
                if (popOut) {
                    alpha = (1f - s / 0.15f).coerceIn(0f, 1f)
                } else {
                    scaleX = 1f + 0.08f * s
                    scaleY = 1f + 0.10f * s
                }
            }
            .drawWithCache {
                val outline = CircleShape.createOutline(size, layoutDirection, this)
                val mid = Offset(size.width / 2f, size.height / 2f)
                val restRim = LiquidColors.rimBrush(dark, mid, 0.7f)
                val bubbleRim = LiquidColors.rimBrush(dark, mid, 1.3f)
                val rimWidth = 1.5.dp.toPx()
                onDrawBehind {
                    val s = if (popOut) 0f else strength().coerceIn(0f, 1f)
                    drawOutline(outline, fill.copy(alpha = fill.alpha * (1f - 0.75f * s)))
                    drawOutline(outline, restRim, alpha = 1f - s, style = Stroke(rimWidth))
                    if (s > 0.01f) drawOutline(outline, bubbleRim, alpha = s, style = Stroke(rimWidth * 1.4f))
                }
            },
    )
}

/**
 * One tab. Touch is handled by the row's gesture (so taps and drags share the pop-out lens); the slot carries
 * the tab semantics so TalkBack can still select it.
 */
@Composable
private fun NavSlot(tab: NavTab, selected: Boolean, lit: Boolean, enabled: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val base = LocalContentColor.current
    val tint = if (lit) base else base.copy(alpha = 0.68f)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                contentDescription = tab.label
                if (enabled) onClick(label = tab.label) { onTap(); true } else disabled()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
    }
}
