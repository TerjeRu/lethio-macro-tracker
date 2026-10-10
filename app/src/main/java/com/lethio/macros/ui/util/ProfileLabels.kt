package com.lethio.macros.ui.util

import androidx.annotation.StringRes
import com.lethio.macros.R
import com.lethio.macros.domain.model.ActivityLevel
import com.lethio.macros.domain.model.GoalDirection
import com.lethio.macros.domain.model.Sex

/**
 * Display names for the goal calculator's three enums.
 *
 * In the UI layer for the same reason [MealType.labelRes] is: an `@StringRes` id on the domain
 * enums pulls `com.lethio.macros.R` into a package that unit-tests on the JVM without Robolectric,
 * and `GoalCalculator` is the most heavily unit-tested thing in the app.
 */
@get:StringRes
val Sex.labelRes: Int
    get() = when (this) {
        Sex.FEMALE -> R.string.sex_female
        Sex.MALE -> R.string.sex_male
        Sex.UNSPECIFIED -> R.string.sex_unspecified
    }

@get:StringRes
val ActivityLevel.labelRes: Int
    get() = when (this) {
        ActivityLevel.SEDENTARY -> R.string.activity_sedentary
        ActivityLevel.LIGHT -> R.string.activity_light
        ActivityLevel.MODERATE -> R.string.activity_moderate
        ActivityLevel.ACTIVE -> R.string.activity_active
        ActivityLevel.VERY_ACTIVE -> R.string.activity_very_active
    }

@get:StringRes
val GoalDirection.labelRes: Int
    get() = when (this) {
        GoalDirection.LOSE -> R.string.goal_lose
        GoalDirection.MAINTAIN -> R.string.goal_maintain
        GoalDirection.GAIN -> R.string.goal_gain
    }
