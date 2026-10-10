package com.lethio.macros.ui.util

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/**
 * One line of text that shrinks until it fits, never below [minScale] of its style.
 *
 * The bottom-navigation labels broke mid-word at font scale 2.0 ("Tageb/uch"). Compose's own
 * auto-size arrived after this project's BOM, and hyphenation follows the system locale rather
 * than the app's. [sharedScale] lets sibling labels settle on one size, the smallest any needs,
 * so a row of tabs does not show four different type sizes. Drawn only once settled, so no flash.
 */
@Composable
fun FitText(
    text: String,
    sharedScale: MutableFloatState,
    modifier: Modifier = Modifier,
    minScale: Float = 0.6f,
) {
    val style = LocalTextStyle.current
    var settled by remember(text) { mutableStateOf(false) }
    val scale = sharedScale.floatValue
    Text(
        text = text,
        style = style.copy(fontSize = style.fontSize * scale),
        maxLines = 1,
        softWrap = false,
        modifier = modifier.drawWithContent { if (settled) drawContent() },
        onTextLayout = { result ->
            if (result.didOverflowWidth && scale > minScale) {
                sharedScale.floatValue = (scale * 0.9f).coerceAtLeast(minScale)
            } else {
                settled = true
            }
        },
    )
}
