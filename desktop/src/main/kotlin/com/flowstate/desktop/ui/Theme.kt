package com.flowstate.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.ConcurrentHashMap

/** The Slate palette: black-and-white base with five blue-grey steps. */
object Slate {
    val Ink = Color(0xFF06141B)
    val Deep = Color(0xFF11212D)
    val Steel = Color(0xFF253745)
    val Slate = Color(0xFF4A5C6A)
    val Mist = Color(0xFF9BA8AB)
    val Fog = Color(0xFFCCD0CF)
    val Paper = Color(0xFFF2F4F4)
    val Window = Color(0xFF070D11)
    val CardBottom = Color(0xFF0B161E)
}

val FlowstateColors = darkColorScheme(
    primary = Slate.Paper, onPrimary = Slate.Ink,
    secondary = Slate.Fog, onSecondary = Slate.Ink,
    background = Slate.Window, onBackground = Slate.Paper,
    surface = Slate.Deep, onSurface = Slate.Paper,
    surfaceVariant = Slate.Steel, onSurfaceVariant = Slate.Mist,
    outline = Slate.Slate, error = Color(0xFFE6A5A5),
)

@Composable
fun FlowstateTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = FlowstateColors, content = content)

/** Frosted glass: a light-to-dark translucent wash, a hairline border and a bright top edge. */
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(Slate.Mist.copy(alpha = if (strong) 0.24f else 0.20f), Slate.Slate.copy(alpha = if (strong) 0.20f else 0.14f))
        )
    )
    .border(1.dp, Slate.Fog.copy(alpha = 0.22f), shape)
    .drawWithContent {
        drawContent()
        drawLine(Color.White.copy(alpha = 0.10f), Offset(size.height / 2, 1f), Offset(size.width - size.height / 2, 1f), 1.5f)
    }

/** The solid content cards: deep slate fading darker, hairline border. */
fun Modifier.card(radius: Dp = 24.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(Slate.Deep, Slate.CardBottom)))
        .border(1.dp, Slate.Fog.copy(alpha = 0.10f), shape)
}

@Composable
fun T(
    text: String,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Slate.Paper,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = Text(
    text, modifier, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines,
    overflow = TextOverflow.Ellipsis, softWrap = maxLines > 1, textAlign = align, letterSpacing = letterSpacing,
    lineHeight = lineHeight, style = TextStyle(),
)

@Composable
fun Chip(modifier: Modifier = Modifier, height: Dp = 36.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier.height(height).clip(shape).background(Slate.Deep.copy(alpha = 0.8f)).border(1.dp, Slate.Fog.copy(alpha = 0.2f), shape)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
fun LiveDot(size: Dp = 8.dp) = Box(
    Modifier.size(size + 8.dp).clip(CircleShape).background(Slate.Paper.copy(alpha = 0.12f)),
    contentAlignment = Alignment.Center,
) { Box(Modifier.size(size).clip(CircleShape).background(Slate.Paper)) }

enum class BtnStyle { Primary, Secondary, Dim }

@Composable
fun PillButton(
    label: String,
    icon: ImageVector? = null,
    style: BtnStyle = BtnStyle.Secondary,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(height / 2)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val (bg, fg, border) = when (style) {
        BtnStyle.Primary -> Triple(if (hovered) Color.White else Slate.Paper, Slate.Ink, Slate.Paper)
        BtnStyle.Secondary -> Triple(Slate.Steel.copy(alpha = if (hovered) 0.75f else 0.55f), Slate.Paper, Slate.Fog.copy(alpha = 0.25f))
        BtnStyle.Dim -> Triple(Slate.Steel.copy(alpha = if (hovered) 0.55f else 0.35f), Slate.Mist, Slate.Fog.copy(alpha = 0.18f))
    }
    Row(
        modifier.height(height).clip(shape).background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.4f))
            .border(BorderStroke(1.dp, border), shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) fg else fg.copy(alpha = 0.45f)
        if (icon != null) { Icon(icon, null, tint = c, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        T(label, 15.sp, FontWeight.SemiBold, c)
    }
}

@Composable
fun CircleIconButton(icon: ImageVector, size: Dp = 42.dp, filled: Boolean = false, enabled: Boolean = true, contentDescription: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (filled) Slate.Paper else Slate.Ink.copy(alpha = 0.5f))
            .border(1.dp, if (filled) Slate.Paper else Slate.Fog.copy(alpha = 0.2f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = if (filled) Slate.Ink else Slate.Fog, modifier = Modifier.size(size * 0.45f)) }
}

/** Segmented control: the selected option is a white pill. */
@Composable
fun Segmented(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(Modifier.glass(shape).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) Slate.Paper else Color.Transparent)
                    .clickable(enabled = enabled && !on) { onSelect(i) }.padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { T(label, 14.sp, if (on) FontWeight.Bold else FontWeight.Medium, if (on) Slate.Ink else if (enabled) Slate.Mist else Slate.Mist.copy(alpha = 0.5f)) }
        }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(22.dp), content: @Composable () -> Unit) {
    Column(modifier.card().padding(padding)) { content() }
}

@Composable
fun MiniTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Slate.Steel.copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        T(value, 16.sp, FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        T(label, 11.5.sp, color = Slate.Mist)
    }
}

/** Horizontal progress: slate to fog gradient on a faint track. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Slate.Fog.copy(alpha = 0.1f))) {
        if (fraction > 0f) Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(height).clip(RoundedCornerShape(height / 2))
                .background(Brush.horizontalGradient(listOf(Slate.Slate, Slate.Fog)))
        )
    }
}

// ---- Avatars ----

private val avatarCache = ConcurrentHashMap<String, ImageBitmap>()

fun avatarBitmap(id: String): ImageBitmap? {
    if (id.isBlank()) return null
    avatarCache[id]?.let { return it }
    val bytes = Thread.currentThread().contextClassLoader?.getResourceAsStream("avatars/avatar_$id.webp")?.use { it.readBytes() } ?: return null
    return runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()?.also { avatarCache[id] = it }
}

@Composable
fun Avatar(name: String, avatarId: String, size: Dp, ring: Boolean = true, modifier: Modifier = Modifier) {
    val bmp = remember(avatarId) { avatarBitmap(avatarId) }
    Box(
        modifier.size(size).clip(CircleShape).background(Slate.Steel)
            .then(if (ring) Modifier.border(2.dp, Slate.Fog.copy(alpha = 0.35f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else T(initials(name), (size.value * 0.38f).sp, FontWeight.Bold, Slate.Fog)
    }
}

fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

@Composable
fun BoxScope.Centered(content: @Composable () -> Unit) = Box(Modifier.align(Alignment.Center)) { content() }

// ---- Formatting ----

fun formatDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    val h = minutes / 60
    return if (h > 0) "${h}h ${"%02d".format(minutes % 60)}m" else "${minutes}m"
}

fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatElapsed(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
