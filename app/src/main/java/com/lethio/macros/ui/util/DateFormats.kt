package com.lethio.macros.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A date formatter in the app's chosen language. `ofLocalizedDate` alone uses the JVM default,
 * which is the phone's system language, not the in-app choice.
 */
@Composable
fun appDateFormatter(style: FormatStyle = FormatStyle.MEDIUM): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(style).withLocale(LocalConfiguration.current.locales[0])
