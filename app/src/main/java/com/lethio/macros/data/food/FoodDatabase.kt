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

@Database(
    entities = [
        FoodEntity::class,
        FoodServingEntity::class,
        FoodAliasEntity::class,
        FoodBarcodeEntity::class,
        DatabaseMetaEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class FoodDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao

    companion object {

        const val NAME = "nutrition.db"

        const val SEED_ASSET = "seed.db"

        fun builder(
            context: Context,
            name: String = NAME,
        ): RoomDatabase.Builder<FoodDatabase> =
            Room.databaseBuilder(context, FoodDatabase::class.java, name)

                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)

                // This database contains the replaceable catalogue; diary data lives separately.
                .fallbackToDestructiveMigration(dropAllTables = true)
    }
}
