package com.lethio.macros.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

interface TimeProvider {
    fun today(): LocalDate
    fun now(): Instant

    fun currentTime(): LocalTime
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    private val clock: Clock = Clock.systemDefaultZone()
    override fun today(): LocalDate = LocalDate.now(clock)
    override fun now(): Instant = clock.instant()
    override fun currentTime(): LocalTime = LocalTime.now(clock)
}
