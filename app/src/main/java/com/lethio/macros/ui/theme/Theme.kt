package com.lethio.macros.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Macro colours as `MaterialTheme.macroColors`; a composition local, so they follow light and dark. */
internal val LocalMacroColors = staticCompositionLocalOf { LightMacroColors }

val MaterialTheme.macroColors: MacroColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMacroColors.current

/**
 * The app theme. [dynamicColor] is off by default so the brand palette shows; macro colours always
 * come from the palette, since wallpaper hues would lose their separation.
 */
@Composable
fun LethioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    val macros = if (darkTheme) DarkMacroColors else LightMacroColors

    CompositionLocalProvider(LocalMacroColors provides macros) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LethioTypography,
            shapes = LethioShapes,
            content = content,
        )
    }
}
