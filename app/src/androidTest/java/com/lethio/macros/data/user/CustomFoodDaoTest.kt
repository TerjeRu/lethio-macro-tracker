package com.lethio.macros.data.user

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.data.user.entity.CustomFoodEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `custom_foods` is saved through `@Insert(onConflict = REPLACE)`, which **deletes the row and
 * re-inserts it**. So the repository reads the existing row and passes its `created_at` back in,
 * and this proves REPLACE honours it.
 */
@RunWith(AndroidJUnit4::class)
class CustomFoodDaoTest {

    private lateinit var database: UserDatabase
    private lateinit var dao: CustomFoodDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    UserDatabase::class.java,
                )
                .setDriver(BundledSQLiteDriver())
                .build()
        dao = database.customFoodDao()
    }

    @After fun tearDown() = database.close()

    private fun food(
        id: Long = 0,
        name: String = "My protein shake",
        createdAt: Long,
        updatedAt: Long,
    ) =
        CustomFoodEntity(
            id = id,
            name = name,
            nameFolded = name.lowercase(),
            kcal100g = 180.0,
            protein100g = 30.0,
            fat100g = 2.0,
            carbs100g = 8.0,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    @Test
    fun aReplacingSaveKeepsTheCreatedAtItIsGiven() = runBlocking {
        val id = dao.upsertWithServings(food(createdAt = 1_000, updatedAt = 1_000), emptyList())

        // The edit a day later: same row, corrected macros, original creation time passed back in.
        dao.upsertWithServings(
            food(id = id, name = "My protein shake", createdAt = 1_000, updatedAt = 90_000_000),
            emptyList(),
        )

        val saved = dao.getById(id)
        assertThat(saved).isNotNull()
        assertThat(saved!!.createdAt).isEqualTo(1_000)
        assertThat(saved.updatedAt).isEqualTo(90_000_000)
    }

    @Test
    fun aReplacingSaveWithoutTheOriginalOverwritesIt() = runBlocking {
        // The defect, stated as a test so the fix above is not mistaken for something Room does on
        // its own. REPLACE writes exactly what it is handed; nothing preserves the old value.
        val id = dao.upsertWithServings(food(createdAt = 1_000, updatedAt = 1_000), emptyList())

        dao.upsertWithServings(
            food(id = id, createdAt = 90_000_000, updatedAt = 90_000_000),
            emptyList(),
        )

        assertThat(dao.getById(id)!!.createdAt).isEqualTo(90_000_000)
    }
}
