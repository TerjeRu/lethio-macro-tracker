package com.lethio.macros.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A date formatter in the app's chosen language.
 *
 * `DateTimeFormatter.ofLocalizedDate` alone uses the JVM default locale, which is the phone's
 * system language, so a Dutch app on an English phone wrote "8 Oct 2026". The Compose
 * configuration carries the in-app language choice; History already read it this way.
 */
@Composable
fun appDateFormatter(style: FormatStyle = FormatStyle.MEDIUM): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(style).withLocale(LocalConfiguration.current.locales[0])
