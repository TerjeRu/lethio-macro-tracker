package com.lethio.macros.data.user

import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.toDomain
import com.lethio.macros.data.toEntity
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.WeightEntry
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

    override fun observeGoalsFor(date: LocalDate): Flow<Goals> =
        goalsDao.observeEffectiveOn(date.toString())
            .map { it?.toDomain() ?: Goals.default(date) }

    override suspend fun goalsFor(date: LocalDate): Goals =
        goalsDao.effectiveOn(date.toString())?.toDomain() ?: Goals.default(date)

    override suspend fun setGoals(goals: Goals) =
        goalsDao.saveForDate(goals.toEntity(timeProvider.now()))

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

    override suspend fun upsert(entry: WeightEntry) {
        val existingId = weightDao.findByDate(entry.measuredOn.toString())?.id
        weightDao.upsert(entry.toEntity().let { row ->
            if (entry.id == WeightEntry.NEW && existingId != null) row.copy(id = existingId) else row
        })
    }

    override suspend fun delete(id: Long) = weightDao.deleteById(id)
}
