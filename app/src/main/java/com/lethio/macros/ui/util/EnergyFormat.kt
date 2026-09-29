package com.lethio.macros.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import com.lethio.macros.R
import com.lethio.macros.domain.model.EnergyUnit
import kotlin.math.roundToInt

val LocalEnergyUnit = compositionLocalOf { EnergyUnit.KCAL }

object EnergyFormat {

    @Composable
    @ReadOnlyComposable
    fun format(kcal: Double): String = "${value(kcal)} ${label()}"

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
