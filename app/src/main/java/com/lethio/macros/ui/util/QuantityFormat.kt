package com.lethio.macros.ui.util

import kotlin.math.roundToInt

/** Drops a trailing `.0`, so whole amounts read "2" rather than "2.0". */
internal fun Double.formatQuantity(): String =
    if (this % 1.0 == 0.0) roundToInt().toString() else "%.1f".format(this)
