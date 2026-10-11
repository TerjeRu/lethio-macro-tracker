package com.lethio.macros.domain.model

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class DiaryHistoryWindowTest {
    @Test
    fun trailingCalendarWindowCrossesYearAndLeapDayAndClampsOnlyFutureEnd() {
        val today = LocalDate.of(2026, 1, 3)
        val week =
            DiaryHistoryWindow.endingOn(today.plusDays(10), today, DiaryHistoryPeriod.SEVEN_DAYS)
        assertThat(week.end).isEqualTo(today)
        assertThat(week.start).isEqualTo(LocalDate.of(2025, 12, 28))
        val past =
            DiaryHistoryWindow.endingOn(
                LocalDate.of(2024, 3, 1),
                today,
                DiaryHistoryPeriod.TWENTY_EIGHT_DAYS,
            )
        assertThat(past.end).isEqualTo(LocalDate.of(2024, 3, 1))
        assertThat(past.start).isEqualTo(LocalDate.of(2024, 2, 3))
    }

    @Test
    fun aNewTodayDoesNotReassignASelectedHistoricalCalendarDate() {
        val selected = LocalDate.of(2026, 10, 3)
        val before =
            DiaryHistoryWindow.endingOn(
                selected,
                LocalDate.of(2026, 10, 4),
                DiaryHistoryPeriod.SEVEN_DAYS,
            )
        val after =
            DiaryHistoryWindow.endingOn(
                selected,
                LocalDate.of(2026, 10, 5),
                DiaryHistoryPeriod.SEVEN_DAYS,
            )
        assertThat(after).isEqualTo(before)
    }
}
