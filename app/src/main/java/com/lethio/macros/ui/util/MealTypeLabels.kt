package com.lethio.macros.ui.util

import androidx.annotation.StringRes
import com.lethio.macros.R
import com.lethio.macros.domain.model.MealType

@get:StringRes
val MealType.labelRes: Int
    get() = when (this) {
        MealType.BREAKFAST -> R.string.breakfast
        MealType.LUNCH -> R.string.lunch
        MealType.DINNER -> R.string.dinner
        MealType.SNACK -> R.string.snacks
    }
