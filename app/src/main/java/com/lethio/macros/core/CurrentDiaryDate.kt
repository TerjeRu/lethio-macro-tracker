package com.lethio.macros.core

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentDiaryDate @Inject constructor(timeProvider: TimeProvider) {
    var value: LocalDate = timeProvider.today()
        private set

    fun set(date: LocalDate) {
        value = date
    }
}
