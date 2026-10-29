package com.flowstate.focus.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One line of text that shrinks its font until it fits, down to [minSize], instead of wrapping
 * ("1h" / "43m") or breaking a word in two. Used where narrow columns (side navigation, small
 * phones) would otherwise squeeze big numbers and labels.
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    minSize: TextUnit = 11.sp,
) {
    var size by remember(text, style) { mutableStateOf(style.fontSize) }
    var ready by remember(text, style) { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        style = style.copy(fontSize = size),
        color = color,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { r ->
            if (r.hasVisualOverflow && size.value > minSize.value) size = (size.value * 0.92f).sp else ready = true
        },
    )
}
