package com.lethio.macros.ui.goals

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.repository.GoalsRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedGoalsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun cleanup() = Dispatchers.resetMain()

    private val date = LocalDate.of(2026, 9, 30)
    private val bad = Goals(date, Macros(9000000.0, 150.0, 65.0, 250.0), Goals.Source.MANUAL)
    private val later =
        Goals(date.plusDays(3), Macros(2300.0, 150.0, 65.0, 250.0), Goals.Source.MANUAL)

    private fun vm(repo: Fake, requested: String = "all") =
        SavedGoalsViewModel(repo, SavedStateHandle(mapOf("goalsDate" to requested)))

    @Test
    fun selectedDiaryDateOpensEffectiveSavedRangeAndRejectsBlankInheritance() =
        runTest(dispatcher) {
            val repo = Fake(listOf(bad, later))
            val vm = vm(repo, date.plusDays(1).toString())
            runCurrent()
            val edit = vm.state.value.editing!!
            assertThat(edit.period.goals).isEqualTo(bad)
            assertThat(edit.period.nextDate).isEqualTo(later.effectiveFrom)
            assertThat(edit.fields.canSave).isFalse()
            vm.change(GoalField.CALORIES, "")
            assertThat(vm.state.value.editing!!.fields.canSave).isFalse()
            vm.change(GoalField.CALORIES, "2000")
            assertThat(vm.state.value.editing!!.fields.canSave).isTrue()
            vm.save()
            vm.save()
            runCurrent()
            assertThat(repo.writes).isEqualTo(1)
            assertThat(repo.rows.value.single { it.effectiveFrom == later.effectiveFrom })
                .isEqualTo(later)
            assertThat(vm.state.value.editing).isNull()
        }

    @Test
    fun failureRetainsDraftAndRangeAndRetrySucceedsWithoutDuplicateSave() =
        runTest(dispatcher) {
            val repo = Fake(listOf(bad, later))
            val vm = vm(repo, date.toString())
            runCurrent()
            vm.change(GoalField.CALORIES, "2100")
            repo.failWrite = true
            vm.save()
            runCurrent()
            assertThat(vm.state.value.editing!!.fields.calories).isEqualTo("2100")
            assertThat(vm.state.value.editing!!.fields.failed).isTrue()
            assertThat(vm.state.value.editing!!.fields.saving).isFalse()
            repo.failWrite = false
            vm.save()
            runCurrent()
            assertThat(vm.state.value.editing).isNull()
            assertThat(repo.rows.value.first().targets.calories).isEqualTo(2100.0)
        }

    @Test
    fun changedGoalDoesNotSilentlyRefreshDraftAndStaleSaveIsRefused() =
        runTest(dispatcher) {
            val repo = Fake(listOf(bad, later))
            val vm = vm(repo, date.toString())
            runCurrent()
            vm.change(GoalField.CALORIES, "2000")
            repo.rows.value = listOf(bad.copy(targets = later.targets), later)
            runCurrent()
            assertThat(vm.state.value.editing!!.period.goals).isEqualTo(bad)
            vm.save()
            runCurrent()
            assertThat(vm.state.value.editing!!.fields.failed).isTrue()
            assertThat(repo.rows.value.first().targets).isEqualTo(later.targets)
        }

    @Test
    fun deleteRequiresConfirmationSnapshotAndFailureRetainsIt() =
        runTest(dispatcher) {
            val repo = Fake(listOf(bad, later))
            val vm = vm(repo)
            runCurrent()
            vm.delete()
            runCurrent()
            assertThat(repo.rows.value).hasSize(2)
            vm.requestDelete(vm.state.value.periods.last())
            repo.failWrite = true
            vm.delete()
            runCurrent()
            assertThat(vm.state.value.deleting!!.goals).isEqualTo(bad)
            assertThat(vm.state.value.deleteFailed).isTrue()
            repo.failWrite = false
            vm.delete()
            vm.delete()
            runCurrent()
            assertThat(repo.rows.value).containsExactly(later)
            assertThat(vm.state.value.deleting).isNull()
        }

    @Test
    fun noSavedGoalNeverOpensEditorForInventedDefaultAndCancelDoesNotWrite() =
        runTest(dispatcher) {
            val repo = Fake(listOf(later))
            val vm = vm(repo, date.toString())
            runCurrent()
            assertThat(vm.state.value.editing).isNull()
            vm.edit(vm.state.value.periods.single())
            vm.change(GoalField.CALORIES, "2200")
            vm.dismissEdit()
            runCurrent()
            assertThat(repo.writes).isEqualTo(0)
            assertThat(repo.rows.value).containsExactly(later)
        }

    private class Fake(initial: List<Goals>) : GoalsRepository {
        val rows = MutableStateFlow(initial)
        var failWrite = false
        var writes = 0

        override fun observeHistory(): Flow<List<Goals>> = rows

        override fun observeGoalsFor(date: LocalDate): Flow<Goals> = error("not used")

        override suspend fun goalsFor(date: LocalDate): Goals = error("not used")

        override suspend fun setGoals(goals: Goals) = error("not used")

        override suspend fun replaceSavedGoals(
            expected: Goals,
            nextDate: LocalDate?,
            targets: Macros,
        ) {
            if (failWrite) error("Simulated failure")
            check(expected in rows.value)
            writes++
            rows.value = rows.value.map { if (it == expected) it.copy(targets = targets) else it }
        }

        override suspend fun deleteSavedGoals(expected: Goals, nextDate: LocalDate?) {
            if (failWrite) error("Simulated failure")
            check(expected in rows.value)
            rows.value = rows.value - expected
        }
    }
}
