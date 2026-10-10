package com.lethio.macros.data.food

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import com.lethio.macros.data.food.entity.DatabaseMetaEntity
import com.lethio.macros.data.food.entity.FoodAliasEntity
import com.lethio.macros.data.food.entity.FoodBarcodeEntity
import com.lethio.macros.data.food.entity.FoodEntity
import com.lethio.macros.data.food.entity.FoodServingEntity

/**
 * The bundled food library, read-only, in its own file so it can be replaced without touching the
 * diary. Always re-derivable from the asset, so destructive migration is right here and only here.
 */
@Database(
    entities = [
        FoodEntity::class,
        FoodServingEntity::class,
        FoodAliasEntity::class,
        FoodBarcodeEntity::class,
        DatabaseMetaEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class FoodDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao

    companion object {
        /** On-device filename. */
        const val NAME = "nutrition.db"

        /** The asset bundled in the APK and installed by [FoodDatabaseProvider]. */
        const val SEED_ASSET = "seed.db"

        /**
         * The one place this database is configured; Hilt and the instrumented tests both use it, so
         * a test cannot silently miss the bundled driver. No `createFromAsset`: Room rejects it with a
         * driver configured.
         */
        fun builder(
            context: Context,
            name: String = NAME,
        ): RoomDatabase.Builder<FoodDatabase> =
            Room.databaseBuilder(context, FoodDatabase::class.java, name)
                // Android's SQLite lacks FTS5, and its version varies by API level.
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                // No user data here. UserDatabase must never have this.
                .fallbackToDestructiveMigration(dropAllTables = true)
    }
}
