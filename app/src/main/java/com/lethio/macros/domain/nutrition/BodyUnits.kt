package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.MassUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object BodyUnits {

    const val KG_PER_POUND = 0.45359237

    const val POUNDS_PER_STONE = 14

    const val CM_PER_INCH = 2.54

    const val INCHES_PER_FOOT = 12

    fun kgToPounds(kg: Double): Double = kg / KG_PER_POUND

    fun poundsToKg(pounds: Double): Double = pounds * KG_PER_POUND

    fun kgToStonePounds(kg: Double): StonePounds {
        val totalPounds = kgToPounds(kg)
        var stone = (totalPounds / POUNDS_PER_STONE).toInt()
        var pounds = roundToTenth(totalPounds - stone * POUNDS_PER_STONE)
        if (pounds >= POUNDS_PER_STONE) {
            stone += 1
            pounds -= POUNDS_PER_STONE
        }
        return StonePounds(stone, pounds)
    }

    fun stonePoundsToKg(stone: Int, pounds: Double): Double =
        poundsToKg(stone * POUNDS_PER_STONE + pounds)

    data class StonePounds(val stone: Int, val pounds: Double)

    fun cmToInches(cm: Double): Double = cm / CM_PER_INCH

    fun inchesToCm(inches: Double): Double = inches * CM_PER_INCH

    fun cmToFeetInches(cm: Double): FeetInches {
        val totalInches = cmToInches(cm)
        var feet = (totalInches / INCHES_PER_FOOT).toInt()
        var inches = roundToTenth(totalInches - feet * INCHES_PER_FOOT)
        if (inches >= INCHES_PER_FOOT) {
            feet += 1
            inches -= INCHES_PER_FOOT
        }
        return FeetInches(feet, inches)
    }

    fun feetInchesToCm(feet: Int, inches: Double): Double =
        inchesToCm(feet * INCHES_PER_FOOT + inches)

    data class FeetInches(val feet: Int, val inches: Double)

    fun rateToDisplay(kgPerWeek: Double, unit: MassUnit): Double =
        if (unit == MassUnit.METRIC) kgPerWeek else kgToPounds(kgPerWeek)

    fun rateToKg(value: Double, unit: MassUnit): Double =
        if (unit == MassUnit.METRIC) value else poundsToKg(value)

    fun formatField(value: Double): String {
        val rounded = roundToTenth(value)
        return if (abs(rounded - rounded.roundToInt()) < 1e-9) {
            rounded.roundToInt().toString()
        } else {
            rounded.toString()
        }
    }

    private fun roundToTenth(value: Double): Double = (value * 10).roundToInt() / 10.0
}
