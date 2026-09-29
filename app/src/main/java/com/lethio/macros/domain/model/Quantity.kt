package com.lethio.macros.domain.model

data class Quantity(
    val amount: Double,
    val unit: MeasureUnit,
    val grams: Double,
) {
    companion object {
        fun ofGrams(grams: Double) = Quantity(grams, MeasureUnit.Grams, grams)
    }
}
