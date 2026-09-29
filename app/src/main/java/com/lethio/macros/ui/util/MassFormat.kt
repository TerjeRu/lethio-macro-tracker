package com.lethio.macros.ui.util

import androidx.compose.runtime.compositionLocalOf
import com.lethio.macros.R
import com.lethio.macros.domain.model.MassUnit

val LocalMassUnit = compositionLocalOf { MassUnit.METRIC }

val MassUnit.labelRes: Int
    get() = when (this) {
        MassUnit.METRIC -> R.string.mass_unit_metric
        MassUnit.IMPERIAL_US -> R.string.mass_unit_imperial_us
        MassUnit.IMPERIAL_UK -> R.string.mass_unit_imperial_uk
    }

val MassUnit.weightLabelRes: Int
    get() = when (this) {
        MassUnit.METRIC -> R.string.weight_kg
        else -> R.string.weight_lb
    }

val MassUnit.rateLabelRes: Int
    get() = when (this) {
        MassUnit.METRIC -> R.string.rate_kg_per_week
        else -> R.string.rate_lb_per_week
    }
