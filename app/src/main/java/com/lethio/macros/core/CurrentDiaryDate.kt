package com.lethio.macros.core

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The date Diary is showing. [com.lethio.macros.ui.diary.DiaryViewModel] writes it; Log Entry and
 * Quick Add read it when saving, so a food found through Search or Scan lands on that day.
 */
@Singleton
class CurrentDiaryDate @Inject constructor(timeProvider: TimeProvider) {
    var value: LocalDate = timeProvider.today()
        private set

    fun set(date: LocalDate) {
        value = date
    }
}
