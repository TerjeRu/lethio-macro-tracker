package com.lethio.macros.data.user

import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.toDomain
import com.lethio.macros.data.toEntity
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.WeightEntry
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.nutrition.GoalTargetLimits
import com.lethio.macros.domain.repository.GoalsRepository
import com.lethio.macros.domain.repository.ProfileRepository
import com.lethio.macros.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalsRepositoryImpl @Inject constructor(
    private val goalsDao: GoalsDao,
    private val timeProvider: TimeProvider,
) : GoalsRepository {

    /** The goals in force on [date], so changing a target never rescores past days. */
    override fun observeGoalsFor(date: LocalDate): Flow<Goals> =
        goalsDao.observeEffectiveOn(date.toString())
            .map { it?.toDomain() ?: Goals.default(date) }

    override suspend fun goalsFor(date: LocalDate): Goals =
        goalsDao.effectiveOn(date.toString())?.toDomain() ?: Goals.default(date)

    override suspend fun setGoals(goals: Goals) {
        require(GoalTargetLimits.accepts(goals.targets)) { "Invalid goal targets" }
        goalsDao.saveForDate(goals.toEntity(timeProvider.now()))
    }

    override suspend fun replaceSavedGoals(expected: Goals, nextDate: LocalDate?, targets: Macros) {
        require(GoalTargetLimits.accepts(targets)) { "Invalid goal targets" }
        val now = timeProvider.now()
        goalsDao.replaceSaved(expected.toEntity(now), nextDate?.toString(),
            expected.copy(targets = targets, source = Goals.Source.MANUAL).toEntity(now))
    }

    override suspend fun deleteSavedGoals(expected: Goals, nextDate: LocalDate?) {
        goalsDao.deleteSaved(expected.toEntity(timeProvider.now()), nextDate?.toString())
    }

    override fun observeHistory(): Flow<List<Goals>> =
        goalsDao.observeHistory().map { rows -> rows.map { it.toDomain() } }
}

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val profileDao: ProfileDao,
) : ProfileRepository {

    override fun observeProfile(): Flow<Profile> =
        profileDao.observeProfile().map { it?.toDomain() ?: Profile() }

    override suspend fun getProfile(): Profile = profileDao.getProfile()?.toDomain() ?: Profile()

    override suspend fun saveProfile(profile: Profile) = profileDao.upsert(profile.toEntity())
}

@Singleton
class WeightRepositoryImpl @Inject constructor(
    private val weightDao: WeightDao,
) : WeightRepository {

    override fun observeEntries(): Flow<List<WeightEntry>> =
        weightDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun latest(): WeightEntry? = weightDao.latest()?.toDomain()

    /**
     * One weight per day; a second weigh-in corrects it. Looks up the day's id first, because Room's
     * `@Upsert` resolves conflicts on the primary key, not the unique date index.
     */
    override suspend fun upsert(entry: WeightEntry) {
        val existingId = weightDao.findByDate(entry.measuredOn.toString())?.id
        weightDao.upsert(entry.toEntity().let { row ->
            if (entry.id == WeightEntry.NEW && existingId != null) row.copy(id = existingId) else row
        })
    }

    override suspend fun delete(id: Long) = weightDao.deleteById(id)
}
