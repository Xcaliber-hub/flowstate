package com.flowstate.focus.ui.groups

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Metal for the top three: highlight, body, shadow, and the ink for the number. */
private data class Metal(val name: String, val light: Color, val mid: Color, val dark: Color, val ink: Color)

private val Gold = Metal("Gold", Color(0xFFFFF1B8), Color(0xFFFFC233), Color(0xFFB07A00), Color(0xFF4A3300))
private val Silver = Metal("Silver", Color(0xFFFFFFFF), Color(0xFFC7D0D6), Color(0xFF7C8A94), Color(0xFF26313A))
private val Bronze = Metal("Bronze", Color(0xFFFFD2A6), Color(0xFFCD7F32), Color(0xFF7A4318), Color(0xFF3A1E08))

/** The metal for a leaderboard rank, or null below third. */
internal fun rankMetalName(rank: Int): String? = metalFor(rank)?.name
private fun metalFor(rank: Int): Metal? = when (rank) { 1 -> Gold; 2 -> Silver; 3 -> Bronze; else -> null }

/**
 * A small heraldic shield in gold, silver or bronze with the rank on it: flat top, sides that curve
 * to a point, a bright bevel along the top and a darker rim.
 */
@Composable
internal fun RankShield(rank: Int, size: Dp = 30.dp, modifier: Modifier = Modifier) {
    val metal = metalFor(rank) ?: return
    Box(
        modifier.size(width = size, height = size * 1.15f).semantics { contentDescription = "Rank $rank, ${metal.name.lowercase()} shield" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = this.size.width; val h = this.size.height
            fun shield(inset: Float) = Path().apply {
                val l = inset; val r = w - inset; val t = inset; val b = h - inset
                val shoulder = t + (b - t) * 0.12f
                moveTo(l, shoulder)
                quadraticBezierTo(w / 2f, t - (b - t) * 0.04f, r, shoulder) // gently domed top
                lineTo(r, t + (b - t) * 0.48f)
                cubicTo(r, t + (b - t) * 0.78f, w / 2f + (r - l) * 0.18f, b - (b - t) * 0.06f, w / 2f, b)
                cubicTo(w / 2f - (r - l) * 0.18f, b - (b - t) * 0.06f, l, t + (b - t) * 0.78f, l, t + (b - t) * 0.48f)
                close()
            }
            val outer = shield(1.dp.toPx())
            drawPath(outer, Brush.linearGradient(listOf(metal.light, metal.mid, metal.dark), start = Offset(0f, 0f), end = Offset(w, h)))
            // Inner bevel: a lighter face inset from the rim.
            val inner = shield(w * 0.14f)
            drawPath(inner, Brush.verticalGradient(listOf(metal.light.copy(alpha = 0.95f), metal.mid), startY = 0f, endY = h))
            drawPath(inner, metal.dark.copy(alpha = 0.55f), style = Stroke(width = 1.dp.toPx()))
            drawPath(outer, metal.dark, style = Stroke(width = 1.2.dp.toPx()))
        }
        Text(
            "$rank",
            color = metal.ink,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.46f).sp,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
