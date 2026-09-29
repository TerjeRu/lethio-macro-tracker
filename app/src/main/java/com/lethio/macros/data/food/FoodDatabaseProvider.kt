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

@Singleton
open class FoodDatabaseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    internal open fun openSeedAsset(): InputStream = context.assets.open(FoodDatabase.SEED_ASSET)

    private val mutex = Mutex()

    @Volatile
    private var database: FoodDatabase? = null

    suspend fun dao(): FoodDao = database().foodDao()

    private suspend fun database(): FoodDatabase =
        database ?: mutex.withLock {
            database ?: withContext(Dispatchers.IO) {
                installSeedIfStale()
                FoodDatabase.builder(context).build()
            }.also { database = it }
        }

    private fun installSeedIfStale() {
        val target = context.getDatabasePath(FoodDatabase.NAME)
        val packaged = openSeedAsset().use(::readStamp)

        val installed = if (target.exists() && target.length() > 0) readStamp(target) else null

        if (packaged == null) return

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

            listOf("-wal", "-shm").forEach { suffix ->
                File(target.parentFile, "${FoodDatabase.NAME}$suffix").delete()
            }
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }
    }

    private data class Stamp(val schemaVersion: Int, val buildVersion: Int) {

        fun isOlderThan(other: Stamp): Boolean =
            schemaVersion != other.schemaVersion || buildVersion < other.buildVersion
    }

    private fun readStamp(file: File): Stamp? =
        runCatching { file.inputStream().use(::readStamp) }.getOrNull()

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

        val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        const val USER_VERSION_OFFSET = 60
        const val APPLICATION_ID_OFFSET = 68
        const val HEADER_BYTES = APPLICATION_ID_OFFSET + Int.SIZE_BYTES
    }

    suspend fun replaceWith(replacement: File) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = context.getDatabasePath(FoodDatabase.NAME)
            database?.close()
            database = null

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

            listOf("-wal", "-shm").forEach { suffix ->
                File(target.parentFile, "${FoodDatabase.NAME}$suffix").delete()
            }
        }
    }
}
