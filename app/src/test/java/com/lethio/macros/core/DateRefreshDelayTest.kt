package com.lethio.macros.core

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone
import org.junit.Test

class DateRefreshDelayTest {
    @Test
    fun aRunningProviderReadsTheNewSystemTimeZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-12:00"))
            val provider = SystemTimeProvider()
            val westDate = provider.today()
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"))
            assertThat(provider.today()).isEqualTo(LocalDate.now())
            assertThat(provider.today()).isGreaterThan(westDate)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun refreshIsScheduledAtMidnightAndNeverBusyLoops() {
        val time =
            object : TimeProvider {
                var wallTime = LocalTime.of(23, 59, 59, 500_000_000)

                override fun currentTime() = wallTime

                override fun today() = LocalDate.of(2026, 10, 4)

                override fun now() = Instant.EPOCH
            }
        assertThat(time.dateRefreshDelayMillis()).isEqualTo(500L)
        time.wallTime = LocalTime.MAX
        assertThat(time.dateRefreshDelayMillis()).isEqualTo(1L)
        time.wallTime = LocalTime.NOON
        assertThat(time.dateRefreshDelayMillis()).isEqualTo(60_000L)
    }
}
