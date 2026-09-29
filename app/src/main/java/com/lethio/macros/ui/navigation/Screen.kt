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

sealed class Screen(val route: String) {
    data object Diary : Screen("diary")
    data object Goals : Screen("goals")
    data object Settings : Screen("settings")
    data object Attribution : Screen("attribution")
    data object Search : Screen("search")
    data object Scanner : Screen("scanner")
    data object QuickAdd : Screen("quick_add?name={name}") {

        fun createRoute(name: String? = null): String =
            if (name.isNullOrBlank()) "quick_add" else "quick_add?name=${Uri.encode(name)}"
    }

    data object AddProduct : Screen("add_product/{barcode}?offMissing={offMissing}") {
        fun createRoute(barcode: String, offMissing: Boolean = false): String =
            "add_product/${Uri.encode(barcode)}?offMissing=$offMissing"
    }
    data object LogEntry : Screen("log_entry/{foodId}/{foodSource}") {
        fun createRoute(foodId: Long, foodSource: String) = "log_entry/$foodId/$foodSource"
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
