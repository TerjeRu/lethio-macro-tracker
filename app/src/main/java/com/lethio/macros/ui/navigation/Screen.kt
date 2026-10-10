package com.lethio.macros.ui.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector
import com.lethio.macros.R

/** App routes. Settings is reached from Diary's top bar, not the bottom navigation. */
sealed class Screen(val route: String) {
    /** What the bottom bar navigates to: the route without optional arguments' placeholders. */
    open val tabRoute: String get() = route
    data object Diary : Screen("diary")
    data object History : Screen("diary_history/{historyDate}") {
        fun createRoute(date: java.time.LocalDate) = "diary_history/$date"
    }
    data object HistoryDay : Screen("diary_history_day/{date}") {
        fun createRoute(date: java.time.LocalDate) = "diary_history_day/$date"
    }
    data object Goals : Screen("goals")
    data object SavedGoals : Screen("saved_goals/{goalsDate}") {
        fun createRoute(date: java.time.LocalDate? = null) = "saved_goals/${date ?: "all"}"
    }
    data object Settings : Screen("settings")
    data object Attribution : Screen("attribution")
    data object SoftwareLicenses : Screen("software_licenses")
    /** [meal] is set when opened from a meal's add button, and carried on to logging. */
    data object Search : Screen("search?meal={meal}") {
        override val tabRoute = "search"
        fun createRoute(meal: com.lethio.macros.domain.model.MealType? = null) =
            if (meal == null) "search" else "search?meal=${meal.name}"
    }
    data object Scanner : Screen("scanner")
    data object QuickAdd : Screen("quick_add?name={name}&meal={meal}") {
        /** [name] prefills Quick Add from a failed search. */
        fun createRoute(name: String? = null, meal: com.lethio.macros.domain.model.MealType? = null): String =
            listOfNotNull(name?.takeIf { it.isNotBlank() }?.let { "name=${Uri.encode(it)}" }, meal?.let { "meal=${it.name}" })
                .let { if (it.isEmpty()) "quick_add" else "quick_add?" + it.joinToString("&") }
    }
    /**
     * Entering a product for a barcode that missed. [offMissing] says Open Food Facts answered "not
     * found", which gates the contribution offer; it travels in the route so the screen never asks
     * again.
     */
    data object AddProduct : Screen("add_product/{barcode}?offMissing={offMissing}") {
        fun createRoute(barcode: String, offMissing: Boolean = false): String =
            "add_product/${Uri.encode(barcode)}?offMissing=$offMissing"
    }
    data object LogEntry : Screen("log_entry/{foodId}/{foodSource}?meal={meal}") {
        fun createRoute(foodId: Long, foodSource: String, meal: com.lethio.macros.domain.model.MealType? = null) =
            "log_entry/$foodId/$foodSource" + (meal?.let { "?meal=${it.name}" } ?: "")
    }
}

data class BottomNavItem(
    val screen: Screen,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Diary, R.string.nav_diary, Icons.Default.Today),
    BottomNavItem(Screen.Search, R.string.nav_search, Icons.Default.Search),
    BottomNavItem(Screen.Scanner, R.string.nav_scan, Icons.Default.CameraAlt),
    BottomNavItem(Screen.Goals, R.string.nav_goals, Icons.Default.FitnessCenter),
)
