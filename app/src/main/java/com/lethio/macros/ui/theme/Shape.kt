package com.lethio.macros.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * Small controls stay tight and containers get progressively rounder, so a chip, a card and a
 * sheet can be told apart by shape alone.
 */
internal val LethioShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),    // chips, small badges
    small = RoundedCornerShape(8.dp),         // buttons, text fields
    medium = RoundedCornerShape(12.dp),       // list rows, small cards
    large = RoundedCornerShape(20.dp),        // primary cards, the daily summary
    extraLarge = RoundedCornerShape(28.dp),   // bottom sheets, dialogs
)
