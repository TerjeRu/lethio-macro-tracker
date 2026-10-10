package com.lethio.macros.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's source of "now", injected so date behaviour such as midnight rollover is testable. Uses
 * the device's zone: a diary's "today" is the user's local day.
 */
interface TimeProvider {
    fun today(): LocalDate
    fun now(): Instant

    /** The local wall-clock time, used only to pick a reasonable default meal on entry. */
    fun currentTime(): LocalTime
}

/** No default constructor argument: Kotlin would emit two constructors, which Dagger rejects. */
@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    // Read the current default zone on each call: a running app can cross time zones.
    override fun today(): LocalDate = LocalDate.now()
    override fun now(): Instant = Instant.now()
    override fun currentTime(): LocalTime = LocalTime.now()
}

/** Check at midnight, bounded to one minute to notice foreground clock/zone changes too. */
internal fun TimeProvider.dateRefreshDelayMillis(): Long =
    ((86_400_000_000_000L - currentTime().toNanoOfDay() + 999_999L) / 1_000_000L)
        .coerceIn(1L, 60_000L)
