package com.lethio.macros.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val LightColors = lightColorScheme(
    primary = Color(0xFF1F6B4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F2C8),
    onPrimaryContainer = Color(0xFF00210F),
    inversePrimary = Color(0xFF8BD6AC),

    secondary = Color(0xFF4E6355),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8D6),
    onSecondaryContainer = Color(0xFF0B1F14),

    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF261A00),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFBFDF7),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFFBFDF7),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF414942),
    surfaceTint = Color(0xFF1F6B4A),
    inverseSurface = Color(0xFF2E312E),
    inverseOnSurface = Color(0xFFF0F2EC),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7F1),
    surfaceContainer = Color(0xFFEFF2EC),
    surfaceContainerHigh = Color(0xFFE9ECE6),
    surfaceContainerHighest = Color(0xFFE4E7E0),

    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC0C9C0),
    scrim = Color(0xFF000000),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6AC),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA8F2C8),
    inversePrimary = Color(0xFF1F6B4A),

    secondary = Color(0xFFB4CCBB),
    onSecondary = Color(0xFF203528),
    secondaryContainer = Color(0xFF364B3E),
    onSecondaryContainer = Color(0xFFD0E8D6),

    tertiary = Color(0xFFEBC16C),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDF9B),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF111411),
    onBackground = Color(0xFFE1E4DE),
    surface = Color(0xFF111411),
    onSurface = Color(0xFFE1E4DE),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC0C9C0),
    surfaceTint = Color(0xFF8BD6AC),
    inverseSurface = Color(0xFFE1E4DE),
    inverseOnSurface = Color(0xFF2E312E),

    surfaceContainerLowest = Color(0xFF0C0F0C),
    surfaceContainerLow = Color(0xFF191C19),
    surfaceContainer = Color(0xFF1D201D),
    surfaceContainerHigh = Color(0xFF272B27),
    surfaceContainerHighest = Color(0xFF323532),

    outline = Color(0xFF8B938B),
    outlineVariant = Color(0xFF414942),
    scrim = Color(0xFF000000),
)

data class MacroColors(
    val protein: Color,
    val onProtein: Color,
    val proteinContainer: Color,
    val fat: Color,
    val onFat: Color,
    val fatContainer: Color,
    val carbs: Color,
    val onCarbs: Color,
    val carbsContainer: Color,
)

internal val LightMacroColors = MacroColors(
    protein = Color(0xFF2C5AA0),
    onProtein = Color(0xFFFFFFFF),
    proteinContainer = Color(0xFFD7E3F7),
    fat = Color(0xFF7A4B9E),
    onFat = Color(0xFFFFFFFF),
    fatContainer = Color(0xFFEBDCF7),
    carbs = Color(0xFF9C5D00),
    onCarbs = Color(0xFFFFFFFF),
    carbsContainer = Color(0xFFF8E2B7),
)

internal val DarkMacroColors = MacroColors(
    protein = Color(0xFFA8C7F5),
    onProtein = Color(0xFF10305F),
    proteinContainer = Color(0xFF1E3A5F),
    fat = Color(0xFFD3B0F0),
    onFat = Color(0xFF3B1D57),
    fatContainer = Color(0xFF3F2B54),
    carbs = Color(0xFFF0BA6B),
    onCarbs = Color(0xFF452B00),
    carbsContainer = Color(0xFF4A3712),
)
