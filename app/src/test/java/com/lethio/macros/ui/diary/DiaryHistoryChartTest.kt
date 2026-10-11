package com.lethio.macros.ui.diary

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.*
import java.time.LocalDate
import java.util.Locale
import org.junit.Test

class DiaryHistoryChartTest {
    private val date = LocalDate.of(2026, 10, 4)

    private fun day(intake: Macros?, goal: Macros? = null) =
        DiaryHistoryDay(
            date,
            if (intake == null) 0 else 1,
            intake,
            goal?.let { Goals(date, it, Goals.Source.MANUAL) },
        )

    @Test
    fun `saved targets and intake both determine the scale`() {
        // Targets are always drawn, so they always count toward the scale.
        val days =
            listOf(
                day(Macros(5000.0, 30.0, 10.0, 100.0), Macros(2000.0, 90.0, 50.0, 200.0)),
                day(null, Macros(6000.0, 150.0, 70.0, 350.0)),
            )
        assertThat(historyChartBounds(days, DiaryHistoryMetric.ENERGY)).isEqualTo(0.0 to 6000.0)
        assertThat(historyChartBounds(days, DiaryHistoryMetric.PROTEIN)).isEqualTo(0.0 to 150.0)
    }

    @Test
    fun `empty and logged zero have usable zero based scales without invented intake`() {
        assertThat(historyChartBounds(listOf(day(null)), DiaryHistoryMetric.ENERGY))
            .isEqualTo(0.0 to 1.0)
        assertThat(historyChartBounds(listOf(day(Macros.ZERO)), DiaryHistoryMetric.ENERGY))
            .isEqualTo(0.0 to 1.0)
        assertThat(historyChartBounds(listOf(day(null, Macros.ZERO)), DiaryHistoryMetric.ENERGY))
            .isEqualTo(0.0 to 1.0)
    }

    @Test
    fun `negative stored values remain inside the plotted scale`() {
        assertThat(
                historyChartBounds(
                    listOf(day(Macros(-2.0, 0.0, 0.0, 0.0))),
                    DiaryHistoryMetric.ENERGY,
                )
            )
            .isEqualTo(-2.0 to 0.0)
    }

    @Test
    fun `summary averages logged days only and names a single shared target`() {
        val goal = Macros(1800.0, 120.0, 60.0, 200.0)
        val days =
            listOf(
                day(Macros(1500.0, 100.0, 50.0, 180.0), goal),
                day(null, goal),
                day(Macros(1700.0, 110.0, 55.0, 190.0), goal),
                day(Macros.ZERO, goal),
            )
        val summary = historySummary(days, DiaryHistoryMetric.ENERGY)!!
        // The unlogged day is left out, not counted as zero; the logged zero counts.
        assertThat(summary.loggedDays).isEqualTo(3)
        assertThat(summary.totalDays).isEqualTo(4)
        assertThat(summary.average).isWithin(1e-9).of((1500.0 + 1700.0 + 0.0) / 3)
        assertThat(summary.target).isEqualTo(1800.0)
    }

    @Test
    fun `summary omits the target when it changed or was missing on a logged day`() {
        val changed =
            listOf(
                day(Macros(1500.0, 0.0, 0.0, 0.0), Macros(1800.0, 0.0, 0.0, 0.0)),
                day(Macros(1700.0, 0.0, 0.0, 0.0), Macros(2000.0, 0.0, 0.0, 0.0)),
            )
        assertThat(historySummary(changed, DiaryHistoryMetric.ENERGY)!!.target).isNull()
        val partly =
            listOf(
                day(Macros(1500.0, 0.0, 0.0, 0.0), Macros(1800.0, 0.0, 0.0, 0.0)),
                day(Macros(1700.0, 0.0, 0.0, 0.0)),
            )
        assertThat(historySummary(partly, DiaryHistoryMetric.ENERGY)!!.target).isNull()
    }

    @Test
    fun `no summary when nothing was logged`() {
        assertThat(
                historySummary(listOf(day(null), day(null, Macros.ZERO)), DiaryHistoryMetric.ENERGY)
            )
            .isNull()
    }

    @Test
    fun `display rounding does not saturate large energy values and respects locale`() {
        assertThat(historyNumber(3_000_000_000.0, DiaryHistoryMetric.ENERGY, Locale.US))
            .isEqualTo("3,000,000,000")
        assertThat(historyNumber(12.34, DiaryHistoryMetric.PROTEIN, Locale.GERMANY))
            .isEqualTo("12,3")
    }
}
