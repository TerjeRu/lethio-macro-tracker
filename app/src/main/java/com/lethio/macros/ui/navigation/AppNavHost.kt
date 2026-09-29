package com.lethio.macros.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lethio.macros.ui.diary.DiaryScreen
import com.lethio.macros.ui.goals.GoalsScreen
import com.lethio.macros.ui.log.LogEntryScreen
import com.lethio.macros.ui.log.QuickAddScreen
import com.lethio.macros.ui.product.AddProductScreen
import com.lethio.macros.ui.scanner.ScannerScreen
import com.lethio.macros.ui.search.SearchScreen
import com.lethio.macros.ui.settings.AttributionScreen
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
            )
        }

        composable(Screen.Goals.route) {
            GoalsScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAttribution = { navController.navigate(Screen.Attribution.route) },
            )
        }

        composable(Screen.Attribution.route) {
            AttributionScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onFoodSelected = { foodId, source ->
                    navController.navigate(Screen.LogEntry.createRoute(foodId, source))
                },
                onAddManually = { query ->
                    navController.navigate(Screen.QuickAdd.createRoute(query))
                },
            )
        }

        composable(Screen.Scanner.route) {
            ScannerScreen(
                onFoodFound = { foodId, source ->
                    navController.navigate(Screen.LogEntry.createRoute(foodId, source)) {
                        popUpTo(Screen.Scanner.route) { inclusive = true }
                    }
                },

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
