package com.lethio.macros.data.food

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.data.user.UserDatabase
import com.lethio.macros.data.user.entity.DailyLogEntity
import com.lethio.macros.data.user.entity.FavoriteEntity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the seed-installation path in [FoodDatabaseProvider].
 *
 * [FoodDatabase] uses `fallbackToDestructiveMigration`, so an out-of-date database left on disk is
 * not ignored but emptied: Room drops every table and search silently returns nothing. A fresh
 * install cannot reproduce that, so these tests stage an old file first.
 */
@RunWith(AndroidJUnit4::class)
class FoodDatabaseSeedInstallTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val databaseFile: File
        get() = context.getDatabasePath(FoodDatabase.NAME)

    @Before fun setUp() = clearDatabase()

    @After fun tearDown() = clearDatabase()

    private fun clearDatabase() {
        context.deleteDatabase(FoodDatabase.NAME)
        context.deleteDatabase(UserDatabase.NAME)
    }

    /** The count that proves the real seed is in place rather than an empty Room-created shell. */
    private fun seededRowCount(): Int = runBlocking { FoodDatabaseProvider(context).dao().count() }

    /**
     * Writes a valid SQLite database carrying [version] and **no `foods` table**.
     *
     * Not a copy of the seed with a patched version: that would still contain every row, so the
     * test would pass whether or not the provider reinstalled anything. Standing in for an older
     * schema is what makes the assertion mean something — if the stale file is left in place, Room
     * drops its tables and the count is zero.
     */
    private fun installDatabaseFromAnOlderSchema(version: Int) {
        databaseFile.parentFile?.mkdirs()
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.execSQL("CREATE TABLE a_table_from_a_previous_release (id INTEGER)")
            connection.execSQL("PRAGMA user_version = $version")
        }
    }

    @Test
    fun aDatabaseFromAnOlderSchemaIsReplacedRatherThanEmptied() {
        installDatabaseFromAnOlderSchema(version = 1_000)

        // Before the fix this was 0: the stale file was kept, Room dropped its tables, and the
        // shipped seed was never read.
        assertThat(seededRowCount()).isGreaterThan(50_000)
    }

    @Test
    fun aCorruptDatabaseIsReplacedRatherThanTrusted() {
        // A truncated copy, a half-finished download, or a file from somewhere else entirely.
        // None of these carry a readable schema version, and all of them should lose to the seed.
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes(ByteArray(4_096) { 0x7A })

        assertThat(seededRowCount()).isGreaterThan(50_000)
    }

    @Test
    fun anEmptyFileIsReplacedRatherThanOpened() {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes(ByteArray(0))

        assertThat(seededRowCount()).isGreaterThan(50_000)
    }

    @Test
    fun aCurrentDatabaseIsLeftAloneOnEveryLaunchAfterTheFirst() {
        assertThat(seededRowCount()).isGreaterThan(50_000)

        // Backdate the installed file. A reinstall recreates it by rename, so the timestamp would
        // jump to now; leaving it alone keeps the marker. Without this the version check could be
        // subtly wrong — comparing against a magic string that never matches, say — and every
        // launch would silently re-copy the whole seed while all the other tests still passed.
        val backdated = 1_600_000_000_000L
        assertThat(databaseFile.setLastModified(backdated)).isTrue()

        assertThat(seededRowCount()).isGreaterThan(50_000)
        assertThat(databaseFile.lastModified()).isEqualTo(backdated)
    }

    // ---------------------------------------------------------------------------------------
    // Build number
    //
    // A reshipped seed usually keeps its schema and changes only rows, so the build number in
    // `PRAGMA application_id` decides whether an installed catalogue is stale.
    // ---------------------------------------------------------------------------------------

    /** SQLite header fields are big-endian ints at fixed offsets, by on-disk format guarantee. */
    private fun headerInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).int

    private fun packagedHeader(): ByteArray =
        context.assets.open(FoodDatabase.SEED_ASSET).use { input ->
            val header = ByteArray(HEADER_BYTES)
            var filled = 0
            while (filled < HEADER_BYTES) {
                val read = input.read(header, filled, HEADER_BYTES - filled)
                check(read >= 0) { "the packaged seed is shorter than a SQLite header" }
                filled += read
            }
            header
        }

    private fun packagedSchemaVersion(): Int = headerInt(packagedHeader(), USER_VERSION_OFFSET)

    private fun packagedBuildVersion(): Int = headerInt(packagedHeader(), APPLICATION_ID_OFFSET)

    private fun installedBuildVersion(): Int =
        RandomAccessFile(databaseFile, "r").use { file ->
            file.seek(APPLICATION_ID_OFFSET.toLong())
            file.readInt()
        }

    /**
     * Writes a valid SQLite database carrying [schemaVersion] and [buildVersion], and **no `foods`
     * table** -- the same trick as [installDatabaseFromAnOlderSchema], for the same reason. A
     * patched copy of the real seed would still hold every row, so the test would pass whether or
     * not anything was reinstalled. Standing in for an older build is what makes the assertion mean
     * something.
     */
    private fun installDatabaseFromAnOlderBuild(schemaVersion: Int, buildVersion: Int) {
        databaseFile.parentFile?.mkdirs()
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.execSQL("CREATE TABLE a_table_from_a_previous_build (id INTEGER)")
            connection.execSQL("PRAGMA user_version = $schemaVersion")
            connection.execSQL("PRAGMA application_id = $buildVersion")
        }
    }

    @Test
    fun aFailedRenamePreservesThePreviousDatabaseAndItsCompanions() = runBlocking {
        installDatabaseFromAnOlderBuild(packagedSchemaVersion(), packagedBuildVersion() - 1)
        val original = databaseFile.readBytes()
        val companions =
            listOf("-wal", "-shm").map { suffix ->
                File(databaseFile.parentFile, "${FoodDatabase.NAME}$suffix").apply {
                    writeText("outgoing companion $suffix")
                }
            }
        var opens = 0
        val provider =
            object : FoodDatabaseProvider(context) {
                override fun openSeedAsset(): InputStream {
                    val input = super.openSeedAsset()
                    if (++opens == 1) return input // stamp read
                    return object : FilterInputStream(input) {
                        override fun close() {
                            super.close()
                            // Remove the completed staging file to make the real rename fail.
                            check(
                                File(databaseFile.parentFile, "${FoodDatabase.NAME}.staging")
                                    .delete()
                            )
                        }
                    }
                }
            }
        var failure: IllegalStateException? = null
        try {
            provider.dao()
        } catch (error: IllegalStateException) {
            failure = error
        }
        assertThat(failure).isNotNull()
        assertThat(failure!!.message).contains("Could not move the seed database")
        assertThat(databaseFile.readBytes()).isEqualTo(original)
        companions.forEach { file ->
            assertThat(file.readText())
                .isEqualTo("outgoing companion ${file.name.removePrefix(FoodDatabase.NAME)}")
        }
        assertThat(File(databaseFile.parentFile, "${FoodDatabase.NAME}.staging").exists()).isFalse()
    }

    @Test
    fun aDatabaseFromAnOlderBuildIsReplacedEvenThoughItsSchemaMatches() {
        // The exact shape of the bug: schema v2 installed, schema v2 packaged, different data.
        val packagedBuild = packagedBuildVersion()
        assertThat(packagedBuild).isGreaterThan(0)

        installDatabaseFromAnOlderBuild(
            schemaVersion = packagedSchemaVersion(),
            buildVersion = packagedBuild - 1,
        )

        assertThat(seededRowCount()).isGreaterThan(50_000)
        assertThat(installedBuildVersion()).isEqualTo(packagedBuild)
    }

    @Test
    fun correctingBarcodeIdentityPreservesSavedFavoritesAndDiarySnapshots() = runBlocking {
        // Reproduce build 16's shared barcode mapping in a real, schema-valid catalogue.
        databaseFile.parentFile?.mkdirs()
        context.assets.open(FoodDatabase.SEED_ASSET).use { input ->
            databaseFile.outputStream().use { input.copyTo(it) }
        }
        val oldId =
            1_000_042_105_220L // Build 16's original Coca-Cola winner; existing user reference.
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { db ->
            db.execSQL("UPDATE food_barcodes SET food_id = $oldId WHERE barcode = '5449000028921'")
            db.execSQL("PRAGMA application_id = ${packagedBuildVersion() - 1}")
        }
        val oldDatabase = FoodDatabase.builder(context).build()
        try {
            assertThat(
                    oldDatabase
                        .foodDao()
                        .findByBarcode("5449000028921", listOf("en"), ",en,")!!
                        .food
                        .id
                )
                .isEqualTo(oldId)
        } finally {
            oldDatabase.close()
        }

        fun openUserDatabase() =
            Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*UserDatabase.ALL_MIGRATIONS)
                .build()

        val snapshot =
            DailyLogEntity(
                logDate = "2026-09-27",
                mealType = 0,
                foodRefKind = "bundled",
                foodRefId = oldId,
                foodName = "Coca-Cola",
                quantity = 250.0,
                unitLabel = "g",
                grams = 250.0,
                calories = 105.0,
                proteinG = 0.0,
                fatG = 0.0,
                carbsG = 26.5,
                createdAt = 1L,
                updatedAt = 1L,
            )
        val userBefore = openUserDatabase()
        val logId: Long
        try {
            logId = userBefore.dailyLogDao().insert(snapshot)
            userBefore
                .favoriteDao()
                .insert(
                    FavoriteEntity(
                        foodRefKind = "bundled",
                        foodRefId = oldId,
                        foodName = "Coca-Cola",
                        createdAt = 1L,
                    )
                )
        } finally {
            userBefore.close()
        }

        // The production install path replaces only the catalogue with the new packaged build.
        val updated = FoodDatabaseProvider(context).dao()
        assertThat(updated.findByBarcode("5449000028921", listOf("en"), ",en,")!!.food.barcode)
            .isEqualTo("5449000028921")
        assertThat(updated.findByBarcode("5449000028921", listOf("en"), ",en,")!!.food.id)
            .isNotEqualTo(oldId)

        val userAfter = openUserDatabase()
        try {
            assertThat(userAfter.dailyLogDao().getById(logId)).isEqualTo(snapshot.copy(id = logId))
            assertThat(userAfter.dailyLogDao().recentFoods(10).single().foodRefId).isEqualTo(oldId)
            val favorite = userAfter.favoriteDao().observeAll().first().single()
            assertThat(favorite.foodRefId).isEqualTo(oldId)
            assertThat(updated.getById(favorite.foodRefId, listOf("en"), ",en,")!!.food.barcode)
                .isEqualTo("42105220")
        } finally {
            userAfter.close()
        }
        assertThat(installedBuildVersion()).isEqualTo(packagedBuildVersion())
    }

    @Test
    fun aDatabaseWithNoBuildNumberAtAllIsReplaced() {
        // Every database built before the builder started writing `application_id` reads back as
        // 0 -- which is every copy installed on a real device today. Those are precisely the
        // files that need replacing, so "absent" has to sort below any real build number rather
        // than being treated as unknown and left alone.
        installDatabaseFromAnOlderBuild(schemaVersion = packagedSchemaVersion(), buildVersion = 0)

        assertThat(seededRowCount()).isGreaterThan(50_000)
        assertThat(installedBuildVersion()).isEqualTo(packagedBuildVersion())
    }

    @Test
    fun aNewerDownloadedDatabaseSurvivesTheSeedInTheApk() {
        // A catalogue installed by replaceWith carries a higher build number than the seed in
        // the APK. If "different" counted as "stale", every launch would overwrite it.
        assertThat(seededRowCount()).isGreaterThan(50_000)

        val downloaded = packagedBuildVersion() + 10
        RandomAccessFile(databaseFile, "rw").use { file ->
            file.seek(APPLICATION_ID_OFFSET.toLong())
            file.writeInt(downloaded)
        }

        assertThat(seededRowCount()).isGreaterThan(50_000)
        // Still the downloaded build: had the seed been copied over it, this would read back as
        // the packaged number.
        assertThat(installedBuildVersion()).isEqualTo(downloaded)
    }

    @Test
    fun replacingTheFoodDatabaseDoesNotTouchTheUserDatabase() {
        // The reason the whole design is safe. `user.db` holds the diary, the favourites and the
        // custom foods, and nothing in this class may write to it -- not the copy, and not the
        // -wal/-shm cleanup that runs beside it, which deletes by exact name and could delete the
        // wrong pair if that ever became a prefix match.
        val userDatabase = context.getDatabasePath(UserDatabase.NAME)
        userDatabase.parentFile?.mkdirs()
        BundledSQLiteDriver().open(userDatabase.absolutePath).use { connection ->
            connection.execSQL("CREATE TABLE daily_logs (id INTEGER, food_name TEXT, grams REAL)")
            connection.execSQL("INSERT INTO daily_logs VALUES (1, 'Porridge', 60.0)")
        }
        // Room leaves these beside an open database; they must survive too.
        val companions =
            listOf("-wal", "-shm").map { suffix ->
                File(userDatabase.parentFile, "${UserDatabase.NAME}$suffix").also {
                    it.writeBytes(byteArrayOf(1, 2, 3))
                }
            }

        val before = userDatabase.readBytes()
        val backdated = 1_600_000_000_000L
        assertThat(userDatabase.setLastModified(backdated)).isTrue()

        // Force the food database to be reinstalled underneath it.
        installDatabaseFromAnOlderBuild(schemaVersion = packagedSchemaVersion(), buildVersion = 0)
        assertThat(seededRowCount()).isGreaterThan(50_000)

        assertThat(userDatabase.exists()).isTrue()
        assertThat(userDatabase.readBytes()).isEqualTo(before)
        assertThat(userDatabase.lastModified()).isEqualTo(backdated)
        companions.forEach { assertThat(it.exists()).isTrue() }
    }

    // ---------------------------------------------------------------------------------------
    // replaceWith()
    //
    // `/storage/emulated` is FUSE, separate from the app's internal storage, so a file under
    // external storage forces File.renameTo() to fail with EXDEV and exercises the staging path.
    // ---------------------------------------------------------------------------------------

    /** A valid, minimal SQLite file carrying [marker] in a table only these tests query. */
    private fun writeMarkedDatabase(file: File, marker: Int) {
        file.parentFile?.mkdirs()
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL("CREATE TABLE marker (value INTEGER)")
            connection.execSQL("INSERT INTO marker VALUES ($marker)")
        }
    }

    private fun readMarker(file: File): Int =
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            var result = -1
            connection.prepare("SELECT value FROM marker").use { statement ->
                check(statement.step()) { "marker table is empty" }
                result = statement.getInt(0)
            }
            result
        }

    @Test
    fun replaceWithUsesTheFastRenamePathWhenPossible() = runBlocking {
        writeMarkedDatabase(databaseFile, marker = 1)
        // Same filesystem as `databaseFile` (both under app-private internal storage), so
        // File.renameTo() succeeds directly -- the fast path.
        val replacement = File(context.filesDir, "replacement.db")
        writeMarkedDatabase(replacement, marker = 2)

        FoodDatabaseProvider(context).replaceWith(replacement)

        assertThat(readMarker(databaseFile)).isEqualTo(2)
        assertThat(replacement.exists()).isFalse() // renameTo() moves it; nothing left to clean up
    }

    @Test
    fun replaceWithFallsBackToStagingAcrossFilesystems() = runBlocking {
        writeMarkedDatabase(databaseFile, marker = 1)
        // A different filesystem (FUSE-backed external storage vs. the internal app
        // directory), so File.renameTo() actually fails here rather than merely
        // being asked to -- this exercises the staging fallback for real, not a mocked failure.
        val replacement = File(context.getExternalFilesDir(null), "replacement.db")
        writeMarkedDatabase(replacement, marker = 2)
        assertThat(replacement.renameTo(databaseFile)).isFalse() // confirms the premise

        FoodDatabaseProvider(context).replaceWith(replacement)

        assertThat(readMarker(databaseFile)).isEqualTo(2)
        assertThat(replacement.exists()).isFalse() // copied, then the source is deleted
        assertThat(File(databaseFile.parentFile, "${FoodDatabase.NAME}.staging").exists()).isFalse()
    }

    @Test
    fun replaceWithLeavesThePreviousDatabaseIntactWhenTheReplacementCannotBeRead() = runBlocking {
        writeMarkedDatabase(databaseFile, marker = 1)
        val before = databaseFile.readBytes()
        // Never written, so both the fast rename and the staging copy fail -- standing in for a
        // copy interrupted partway (full disk, process death), which a JUnit test cannot actually
        // trigger. What matters is the same code path: the catch block must clean up staging and
        // leave the previous database exactly as it was, never a partial file at `target`.
        val missingReplacement = File(context.getExternalFilesDir(null), "does-not-exist.db")

        var threw = false
        try {
            FoodDatabaseProvider(context).replaceWith(missingReplacement)
        } catch (_: Exception) {
            threw = true
        }

        assertThat(threw).isTrue()
        assertThat(databaseFile.readBytes()).isEqualTo(before)
        assertThat(File(databaseFile.parentFile, "${FoodDatabase.NAME}.staging").exists()).isFalse()
    }

    // ---- the guard has two directions, and only one had been thought about ----

    @Test
    fun installSeedLeavesAGoodDatabaseAloneWhenThePackagedAssetCannotBeRead() = runBlocking {
        // The mirror of replaceWithLeavesThePreviousDatabaseIntactWhenTheReplacementCannotBeRead,
        // for the other entry point: an unreadable PACKAGED stamp must not fall through to a copy
        // that deletes nutrition.db and replaces it with the asset that could not be read.
        writeMarkedDatabase(databaseFile, marker = 7)
        val before = databaseFile.readBytes()

        // Ten bytes: opens fine, far short of the 72-byte SQLite header, so readStamp returns
        // null. That is what an interrupted `--stub` write or a truncated copy into assets leaves.
        val provider =
            object : FoodDatabaseProvider(context) {
                override fun openSeedAsset(): InputStream = ByteArrayInputStream(ByteArray(10))
            }
        provider.dao()

        assertThat(databaseFile.readBytes()).isEqualTo(before)
        assertThat(readMarker(databaseFile)).isEqualTo(7)
        assertThat(File(databaseFile.parentFile, "${FoodDatabase.NAME}.staging").exists()).isFalse()
    }

    @Test
    fun anUnreadablePackagedAssetWithNoInstalledDatabaseIsNotACrash() = runBlocking {
        // The other side of returning early: there is nothing to keep and nothing to install. The
        // app is equally broken either way, and this way it is broken without having written a
        // corrupt file to the path it will open next launch.
        assertThat(databaseFile.exists()).isFalse()

        val provider =
            object : FoodDatabaseProvider(context) {
                override fun openSeedAsset(): InputStream = ByteArrayInputStream(ByteArray(10))
            }
        provider.dao()

        assertThat(File(databaseFile.parentFile, "${FoodDatabase.NAME}.staging").exists()).isFalse()
    }

    private companion object {
        const val USER_VERSION_OFFSET = 60
        const val APPLICATION_ID_OFFSET = 68
        const val HEADER_BYTES = APPLICATION_ID_OFFSET + Int.SIZE_BYTES
    }
}
