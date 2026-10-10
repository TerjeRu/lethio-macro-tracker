package com.lethio.macros.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lethio.macros.ui.diary.DiaryScreen
import com.lethio.macros.ui.diary.DiaryViewModel
import com.lethio.macros.ui.diary.DiaryHistoryScreen
import com.lethio.macros.ui.goals.GoalsScreen
import com.lethio.macros.ui.goals.GoalsViewModel
import com.lethio.macros.ui.goals.SavedGoalsScreen
import com.lethio.macros.ui.log.LogEntryScreen
import com.lethio.macros.ui.log.QuickAddScreen
import com.lethio.macros.ui.product.AddProductScreen
import com.lethio.macros.ui.scanner.ScannerScreen
import com.lethio.macros.ui.search.SearchScreen
import com.lethio.macros.ui.settings.AttributionScreen
import com.lethio.macros.ui.settings.SoftwareLicensesScreen
import com.lethio.macros.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Diary.route,
        modifier = modifier,
    ) {
        composable(Screen.Diary.route) {
            DiaryScreen(
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.createRoute(it)) },
                onEditGoals = { navController.navigate(Screen.SavedGoals.createRoute(it)) },
                onAddToMeal = { navController.navigate(Screen.Search.createRoute(it)) },
            )
        }

        composable(Screen.History.route, arguments = listOf(navArgument("historyDate") {
            type = NavType.StringType
        })) { entry ->
            val diaryEntry = remember(entry) { navController.getBackStackEntry(Screen.Diary.route) }
            val diary: DiaryViewModel = hiltViewModel(diaryEntry)
            DiaryHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenDate = { date ->
                    diary.goToDate(date)
                    navController.navigate(Screen.HistoryDay.createRoute(date))
                },
            )
        }

        composable(Screen.HistoryDay.route, arguments = listOf(navArgument("date") {
            type = NavType.StringType
        })) { entry ->
            val diaryEntry = remember(entry) { navController.getBackStackEntry(Screen.Diary.route) }
            val diary: DiaryViewModel = hiltViewModel(diaryEntry)
            // The shared Diary owns its saved date; rotation must not reapply the original row.
            DiaryScreen(
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                viewModel = diary,
                onNavigateToHistory = { navController.popBackStack() },
                onEditGoals = { navController.navigate(Screen.SavedGoals.createRoute(it)) },
                onAddToMeal = { navController.navigate(Screen.Search.createRoute(it)) },
            )
        }

        composable(Screen.Goals.route) {
            GoalsScreen(onSavedGoals = { navController.navigate(Screen.SavedGoals.createRoute()) })
        }

        composable(Screen.SavedGoals.route, arguments = listOf(navArgument("goalsDate") {
            type = NavType.StringType
        })) {
            val parent = remember { navController.previousBackStackEntry }
            val currentGoals: GoalsViewModel? = if (parent?.destination?.route == Screen.Goals.route) hiltViewModel(parent) else null
            SavedGoalsScreen(onNavigateBack = {
                currentGoals?.refreshAfterCorrections()
                navController.popBackStack()
            })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAttribution = { navController.navigate(Screen.Attribution.route) },
            )
        }

        composable(Screen.Attribution.route) {
            AttributionScreen(
                onNavigateBack = { navController.popBackStack() },
                onSoftwareLicenses = { navController.navigate(Screen.SoftwareLicenses.route) },
            )
        }

        composable(Screen.SoftwareLicenses.route) {
            SoftwareLicensesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Search.route, arguments = listOf(navArgument("meal") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })) { entry ->
            val meal = entry.arguments?.getString("meal")?.let(::mealOrNull)
            SearchScreen(
                onFoodSelected = { foodId, source ->
                    navController.navigate(Screen.LogEntry.createRoute(foodId, source, meal))
                },
                onAddManually = { query ->
                    navController.navigate(Screen.QuickAdd.createRoute(query, meal))
                },
                addingTo = meal,
            )
        }

        composable(Screen.Scanner.route) {
            ScannerScreen(
                onFoodFound = { foodId, source ->
                    navController.navigate(Screen.LogEntry.createRoute(foodId, source)) {
                        popUpTo(Screen.Scanner.route) { inclusive = true }
                    }
                },
                // Carries the barcode, so the product is found by scanning it again. offMissing
                // means Open Food Facts answered "not found", which gates contributing it.
                onNotFound = { barcode, offMissing ->
                    navController.navigate(Screen.AddProduct.createRoute(barcode, offMissing)) {
                        popUpTo(Screen.Scanner.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Screen.AddProduct.route,
            arguments = listOf(
                navArgument("barcode") { type = NavType.StringType },
                navArgument("offMissing") {
                    type = NavType.StringType
                    defaultValue = "false"
                },
            ),
        ) {
            AddProductScreen(
                // Straight on to logging it, replacing this screen in the stack -- someone got
                // here by scanning something in order to eat it, and Back from the log entry
                // should not return to a form for a product that is already saved.
                onSaved = { foodId, source ->
                    navController.navigate(Screen.LogEntry.createRoute(foodId, source)) {
                        popUpTo(Screen.AddProduct.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.QuickAdd.route,
            arguments = listOf(
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("meal") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            QuickAddScreen(
                onSaved = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.LogEntry.route,
            arguments = listOf(
                navArgument("foodId") { type = NavType.LongType },
                navArgument("foodSource") { type = NavType.StringType },
                navArgument("meal") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            LogEntryScreen(
                onSaved = {
                    navController.popBackStack(Screen.Diary.route, inclusive = false)
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}

/** A meal name from a route argument, or null for an absent or unknown one. */
internal fun mealOrNull(name: String): com.lethio.macros.domain.model.MealType? =
    com.lethio.macros.domain.model.MealType.entries.firstOrNull { it.name == name }
