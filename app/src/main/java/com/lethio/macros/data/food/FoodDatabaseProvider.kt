package com.lethio.macros.data.food

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the food database file and hands out its DAO.
 *
 * The seed is installed by hand because Room's `createFromAsset` cannot be combined with the
 * bundled SQLite driver, which is required for FTS5. The asset is compressed in the APK and
 * hundreds of megabytes inflated, so installation runs on [Dispatchers.IO] behind a [Mutex].
 */
@Singleton
open class FoodDatabaseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Opens the packaged seed. Open only so a test can supply an unreadable asset, which a real
     * `AssetManager` cannot produce.
     */
    internal open fun openSeedAsset(): InputStream = context.assets.open(FoodDatabase.SEED_ASSET)

    private val mutex = Mutex()

    @Volatile
    private var database: FoodDatabase? = null

    suspend fun dao(): FoodDao = database().foodDao()

    /** Double-checked so the common case is a volatile read, not a lock acquisition. */
    private suspend fun database(): FoodDatabase =
        database ?: mutex.withLock {
            database ?: withContext(Dispatchers.IO) {
                installSeedIfStale()
                FoodDatabase.builder(context).build()
            }.also { database = it }
        }

    /**
     * Installs the bundled seed when the installed file is missing, unreadable, from another
     * schema, or an older build.
     *
     * Schema: `fallbackToDestructiveMigration` would otherwise empty a mismatched file. Build: a
     * new seed usually keeps the schema and only changes rows. The build test is `<`, not `!=`, so
     * a newer installed catalogue is never overwritten by the seed.
     *
     * Both numbers come from the SQLite header, so the asset need not be extracted to be checked.
     * The copy goes through a staging file and a rename, so an interrupted copy never leaves a
     * half-written database. The diary lives in a separate file and is never touched.
     */
    private fun installSeedIfStale() {
        val target = context.getDatabasePath(FoodDatabase.NAME)
        val packaged = openSeedAsset().use(::readStamp)

        val installed = if (target.exists() && target.length() > 0) readStamp(target) else null

        // An unreadable asset never replaces anything, including a larger catalogue installed by
        // [replaceWith].
        if (packaged == null) return

        // Only a positively current installed file skips the copy.
        if (installed != null && !installed.isOlderThan(packaged)) return

        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "${FoodDatabase.NAME}.staging")
        staging.delete()

        try {
            openSeedAsset().use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            if (!staging.renameTo(target)) {
                error("Could not move the seed database into place at ${target.absolutePath}")
            }
            // Remove the outgoing database's companions before Room opens the replacement.
            listOf("-wal", "-shm").forEach { suffix ->
                File(target.parentFile, "${FoodDatabase.NAME}$suffix").delete()
            }
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }
    }

    /**
     * What a database file says it is.
     *
     * @param schemaVersion `PRAGMA user_version`, the Room schema.
     * @param buildVersion `PRAGMA application_id`, the pipeline's build number; 0 when unset.
     */
    private data class Stamp(val schemaVersion: Int, val buildVersion: Int) {
        fun isOlderThan(other: Stamp): Boolean =
            schemaVersion != other.schemaVersion || buildVersion < other.buildVersion
    }

    private fun readStamp(file: File): Stamp? =
        runCatching { file.inputStream().use(::readStamp) }.getOrNull()

    /**
     * Reads `user_version` (offset 60) and `application_id` (offset 68) from the SQLite header, or
     * null if the input is not a SQLite file.
     */
    private fun readStamp(input: InputStream): Stamp? {
        val header = ByteArray(HEADER_BYTES)
        var filled = 0
        while (filled < HEADER_BYTES) {
            val read = input.read(header, filled, HEADER_BYTES - filled)
            if (read < 0) return null
            filled += read
        }
        if (!header.copyOf(SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) return null
        return Stamp(
            schemaVersion = ByteBuffer.wrap(header, USER_VERSION_OFFSET, Int.SIZE_BYTES).int,
            buildVersion = ByteBuffer.wrap(header, APPLICATION_ID_OFFSET, Int.SIZE_BYTES).int,
        )
    }

    private companion object {
        /** The SQLite magic string. It ends in NUL, not a space. */
        val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        const val USER_VERSION_OFFSET = 60
        const val APPLICATION_ID_OFFSET = 68
        const val HEADER_BYTES = APPLICATION_ID_OFFSET + Int.SIZE_BYTES
    }

    /**
     * Replaces the database with an already-validated [replacement], closing the open handle first.
     * This method never decides whether a file is trustworthy. Instrumented tests use it to
     * install a full catalogue; the app has no download flow.
     */
    suspend fun replaceWith(replacement: File) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = context.getDatabasePath(FoodDatabase.NAME)
            database?.close()
            database = null
            // A same-filesystem rename is atomic. Across filesystems, stage and rename instead,
            // so an interrupted copy leaves the previous database intact.
            if (!replacement.renameTo(target)) {
                val staging = File(target.parentFile, "${FoodDatabase.NAME}.staging")
                staging.delete()
                try {
                    replacement.copyTo(staging, overwrite = true)
                    if (!staging.renameTo(target)) {
                        error(
                            "Could not move the replacement database into place at " +
                                target.absolutePath
                        )
                    }
                } catch (t: Throwable) {
                    staging.delete()
                    throw t
                }
                replacement.delete()
            }
            // Stale -wal/-shm beside a new file would corrupt it; removed only once it is in place.
            listOf("-wal", "-shm").forEach { suffix ->
                File(target.parentFile, "${FoodDatabase.NAME}$suffix").delete()
            }
        }
    }
}
