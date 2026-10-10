package com.lethio.macros.data.user

import com.lethio.macros.data.toDomain
import com.lethio.macros.domain.model.DiaryHistoryDay
import com.lethio.macros.domain.model.DiaryHistoryWindow
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.datasetsIn
import com.lethio.macros.domain.repository.DiaryHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiaryHistoryRepositoryImpl @Inject constructor(
    private val logs: DailyLogDao,
    private val goals: GoalsDao,
) : DiaryHistoryRepository {
    override fun observeWindow(window: DiaryHistoryWindow): Flow<List<DiaryHistoryDay>> = combine(
        logs.observeEntriesInRange(window.start.toString(), window.end.toString()),
        goals.observeHistoryInRange(window.start.toString(), window.end.toString()),
    ) { totals, savedGoals ->
        val byDate = totals.groupBy { it.logDate }
        val goalHistory = savedGoals.map { it.toDomain() }
        List(window.period.days) { index ->
            val date = window.start.plusDays(index.toLong())
            val logged = byDate[date.toString()]?.map { entry -> entry.toDomain().macros }
            DiaryHistoryDay(date, logged?.size ?: 0,
                logged?.let { Macros.sum(it) },
                goalHistory.lastOrNull { it.effectiveFrom <= date },
                logged?.let(::datasetsIn).orEmpty())
        }
    }
}
