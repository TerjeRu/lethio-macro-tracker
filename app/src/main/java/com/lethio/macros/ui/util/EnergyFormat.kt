package com.lethio.macros.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import com.lethio.macros.R
import com.lethio.macros.domain.model.EnergyUnit
import kotlin.math.roundToInt

/**
 * The user's chosen energy unit, provided once at the app root.
 *
 * A composition local rather than a constructor parameter on six ViewModels: this is purely a
 * display preference, it never affects what is stored or totalled, and threading it through the
 * domain layer would imply otherwise. Energy is always stored in kilocalories.
 */
val LocalEnergyUnit = compositionLocalOf { EnergyUnit.KCAL }

object EnergyFormat {

    /** "200 kcal" or "837 kJ", per the user's choice. */
    @Composable
    @ReadOnlyComposable
    fun format(kcal: Double): String = "${value(kcal)} ${label()}"

    /** The number alone, for places that render the unit separately. */
    @Composable
    @ReadOnlyComposable
    fun value(kcal: Double): Int = (kcal * LocalEnergyUnit.current.perKcal).roundToInt()

    @Composable
    @ReadOnlyComposable
    fun label(): String = when (LocalEnergyUnit.current) {
        EnergyUnit.KCAL -> stringResource(R.string.energy_kcal)
        EnergyUnit.KJ -> stringResource(R.string.energy_kj)
    }
}
