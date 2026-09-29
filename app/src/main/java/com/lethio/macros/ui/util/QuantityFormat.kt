package com.lethio.macros.ui.util

import kotlin.math.roundToInt

internal fun Double.formatQuantity(): String =
    if (this % 1.0 == 0.0) roundToInt().toString() else "%.1f".format(this)
