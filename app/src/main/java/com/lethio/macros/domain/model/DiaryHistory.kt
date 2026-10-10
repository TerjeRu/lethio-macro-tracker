package com.lethio.macros.domain.model

import java.time.LocalDate

enum class DiaryHistoryPeriod(val days: Int) { SEVEN_DAYS(7), TWENTY_EIGHT_DAYS(28) }

/** Trailing calendar dates, inclusive; neither locale week boundaries nor UTC instants. */
data class DiaryHistoryWindow(val end: LocalDate, val period: DiaryHistoryPeriod) {
    val start: LocalDate get() = end.minusDays(period.days.toLong() - 1)

    companion object {
        fun endingOn(selected: LocalDate, today: LocalDate, period: DiaryHistoryPeriod) =
            DiaryHistoryWindow(minOf(selected, today), period)
    }
}

/** Presence is not completeness. Null intake means no entries, not zero calories. */
data class DiaryHistoryDay(
    val date: LocalDate,
    val entryCount: Int,
    val loggedIntake: Macros?,
    /** Only a genuinely persisted, historically effective goal; never a default. */
    val savedGoal: Goals?,
    /** Datasets the day's saved entries came from; totals drop origins, so credits read this. */
    val datasets: Set<DataSource> = emptySet(),
)
