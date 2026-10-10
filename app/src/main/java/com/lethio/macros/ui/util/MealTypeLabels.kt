package com.lethio.macros.ui.util

import androidx.annotation.StringRes
import com.lethio.macros.R
import com.lethio.macros.domain.model.MealType

/**
 * Display names for meals.
 *
 * Lives in the UI layer, not on the enum. Holding an `@StringRes` id on [MealType] pulled
 * `com.lethio.macros.R` into the domain package, which made the whole package impossible to unit
 * test on the JVM without Robolectric — for the sake of four strings.
 */
@get:StringRes
val MealType.labelRes: Int
    get() = when (this) {
        MealType.BREAKFAST -> R.string.breakfast
        MealType.LUNCH -> R.string.lunch
        MealType.DINNER -> R.string.dinner
        MealType.SNACK -> R.string.snacks
    }
