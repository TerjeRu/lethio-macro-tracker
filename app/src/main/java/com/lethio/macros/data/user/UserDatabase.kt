package com.lethio.macros.data.user

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lethio.macros.data.food.foldForSearch
import com.lethio.macros.data.user.entity.CustomFoodEntity
import com.lethio.macros.data.user.entity.CustomFoodServingEntity
import com.lethio.macros.data.user.entity.DailyLogEntity
import com.lethio.macros.data.user.entity.FavoriteEntity
import com.lethio.macros.data.user.entity.GoalsEntity
import com.lethio.macros.data.user.entity.ProfileEntity
import com.lethio.macros.data.user.entity.WeightEntryEntity

@Database(
    entities = [
        DailyLogEntity::class,
        GoalsEntity::class,
        ProfileEntity::class,
        WeightEntryEntity::class,
        FavoriteEntity::class,
        CustomFoodEntity::class,
        CustomFoodServingEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {

    abstract fun dailyLogDao(): DailyLogDao
    abstract fun goalsDao(): GoalsDao
    abstract fun profileDao(): ProfileDao
    abstract fun weightDao(): WeightDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun customFoodDao(): CustomFoodDao

    companion object {
        const val NAME = "user.db"

        private fun sqlMigration(
            from: Int,
            to: Int,
            body: ((String) -> Unit) -> Unit,
        ) = object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) = body(db::execSQL)
            override fun migrate(connection: SQLiteConnection) = body { connection.execSQL(it) }
        }

        val MIGRATION_1_2 = sqlMigration(1, 2) { exec ->
            exec("UPDATE daily_logs SET food_source = 'bundled' WHERE food_source = 'db'")
            exec("UPDATE favorites SET food_source = 'bundled' WHERE food_source = 'db'")
        }

        val MIGRATION_2_3 = sqlMigration(2, 3) { exec ->

            exec(
                """
                CREATE TABLE IF NOT EXISTS `daily_logs_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `log_date` TEXT NOT NULL,
                    `meal_type` INTEGER NOT NULL,
                    `food_ref_kind` TEXT NOT NULL,
                    `food_ref_id` INTEGER,
                    `food_name` TEXT NOT NULL,
                    `brand` TEXT,
                    `quantity` REAL NOT NULL,
                    `unit_label` TEXT NOT NULL,
                    `grams` REAL NOT NULL,
                    `calories` REAL NOT NULL,
                    `protein_g` REAL NOT NULL,
                    `fat_g` REAL NOT NULL,
                    `carbs_g` REAL NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                INSERT INTO `daily_logs_new` (
                    id, log_date, meal_type, food_ref_kind, food_ref_id, food_name, brand,
                    quantity, unit_label, grams, calories, protein_g, fat_g, carbs_g,
                    created_at, updated_at
                )
                SELECT
                    id,
                    log_date,
                    CASE WHEN meal_type = 0 THEN 4 ELSE meal_type END,
                    CASE
                        WHEN food_source IN ('bundled', 'custom', 'quick') THEN food_source
                        WHEN food_source = 'db' THEN 'bundled'
                        ELSE 'quick'
                    END,
                    CASE WHEN food_source = 'quick' THEN NULL ELSE food_id END,
                    food_name,
                    NULL,
                    amount,
                    COALESCE(NULLIF(amount_unit, ''), 'g'),
                    amount,
                    calories, protein, fat, carbs,
                    COALESCE(created_at, 0),
                    COALESCE(created_at, 0)
                FROM `daily_logs`
                """.trimIndent()
            )
            exec("DROP TABLE `daily_logs`")
            exec("ALTER TABLE `daily_logs_new` RENAME TO `daily_logs`")
            exec(
                "CREATE INDEX IF NOT EXISTS `index_daily_logs_log_date` " +
                    "ON `daily_logs` (`log_date`)"
            )
            exec(
                "CREATE INDEX IF NOT EXISTS `index_daily_logs_log_date_meal_type` " +
                    "ON `daily_logs` (`log_date`, `meal_type`)"
            )
            exec(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_daily_logs_food_ref_kind_food_ref_id` " +
                    "ON `daily_logs` (`food_ref_kind`, `food_ref_id`)"
            )
            exec(
                "CREATE INDEX IF NOT EXISTS `index_daily_logs_created_at` " +
                    "ON `daily_logs` (`created_at`)"
            )

            exec(
                """
                CREATE TABLE IF NOT EXISTS `goals_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `effective_from` TEXT NOT NULL,
                    `calories_target` REAL NOT NULL,
                    `protein_target_g` REAL NOT NULL,
                    `fat_target_g` REAL NOT NULL,
                    `carbs_target_g` REAL NOT NULL,
                    `source` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            exec(
                """
                INSERT INTO `goals_new` (
                    effective_from, calories_target, protein_target_g, fat_target_g,
                    carbs_target_g, source, created_at
                )
                SELECT
                    COALESCE((SELECT MIN(log_date) FROM `daily_logs`), '1970-01-01'),
                    COALESCE(calories_target, 2000),
                    COALESCE(protein_target, 150),
                    COALESCE(fat_target, 65),
                    COALESCE(carbs_target, 250),
                    'manual',
                    0
                FROM `goals`
                LIMIT 1
                """.trimIndent()
            )
            exec("DROP TABLE `goals`")
            exec("ALTER TABLE `goals_new` RENAME TO `goals`")
            exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_goals_effective_from` " +
                    "ON `goals` (`effective_from`)"
            )

            exec(
                """
                CREATE TABLE IF NOT EXISTS `favorites_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `food_ref_kind` TEXT NOT NULL,
                    `food_ref_id` INTEGER NOT NULL,
                    `food_name` TEXT NOT NULL,
                    `brand` TEXT,
                    `sort_order` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                INSERT OR IGNORE INTO `favorites_new` (
                    food_ref_kind, food_ref_id, food_name, brand, sort_order, created_at
                )
                SELECT
                    CASE
                        WHEN food_source IN ('bundled', 'custom') THEN food_source
                        WHEN food_source = 'db' THEN 'bundled'
                        ELSE 'custom'
                    END,
                    food_id,
                    food_name,
                    NULL,
                    COALESCE(sort_order, 0),
                    COALESCE(created_at, 0)
                FROM `favorites`
                WHERE food_id IS NOT NULL
                """.trimIndent()
            )
            exec("DROP TABLE `favorites`")
            exec("ALTER TABLE `favorites_new` RENAME TO `favorites`")
            exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_favorites_food_ref_kind_food_ref_id` " +
                    "ON `favorites` (`food_ref_kind`, `food_ref_id`)"
            )
            exec(
                "CREATE INDEX IF NOT EXISTS `index_favorites_sort_order` " +
                    "ON `favorites` (`sort_order`)"
            )

            exec(
                """
                CREATE TABLE IF NOT EXISTS `custom_foods_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `brand` TEXT,
                    `barcode` TEXT,
                    `kcal_100g` REAL NOT NULL,
                    `protein_100g` REAL NOT NULL,
                    `fat_100g` REAL NOT NULL,
                    `carbs_100g` REAL NOT NULL,
                    `density_g_per_ml` REAL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                INSERT INTO `custom_foods_new` (
                    id, name, brand, barcode, kcal_100g, protein_100g, fat_100g, carbs_100g,
                    density_g_per_ml, created_at, updated_at
                )
                SELECT
                    id, description, brand_owner, gtin_upc,
                    calories_100g, protein_100g, fat_100g, carbs_100g,
                    NULL,
                    COALESCE(created_at, 0),
                    COALESCE(created_at, 0)
                FROM `custom_foods`
                """.trimIndent()
            )

            exec(
                """
                CREATE TABLE IF NOT EXISTS `custom_food_servings` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `food_id` INTEGER NOT NULL,
                    `label` TEXT NOT NULL,
                    `grams` REAL NOT NULL,
                    `is_default` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    FOREIGN KEY(`food_id`) REFERENCES `custom_foods`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )

            exec(
                """
                INSERT INTO `custom_food_servings` (food_id, label, grams, is_default, sort_order)
                SELECT id, COALESCE(NULLIF(serving_unit, ''), 'serving'), serving_size, 1, 0
                FROM `custom_foods`
                WHERE serving_size IS NOT NULL AND serving_size > 0
                """.trimIndent()
            )

            exec("DROP TABLE `custom_foods`")
            exec("ALTER TABLE `custom_foods_new` RENAME TO `custom_foods`")
            exec(
                "CREATE INDEX IF NOT EXISTS `index_custom_foods_barcode` " +
                    "ON `custom_foods` (`barcode`)"
            )
            exec(
                "CREATE INDEX IF NOT EXISTS `index_custom_food_servings_food_id` " +
                    "ON `custom_food_servings` (`food_id`)"
            )

            exec(
                """
                CREATE TABLE IF NOT EXISTS `profile` (
                    `id` INTEGER NOT NULL,
                    `sex` TEXT,
                    `birth_date` TEXT,
                    `height_cm` REAL,
                    `activity_level` TEXT,
                    `goal_direction` TEXT,
                    `rate_kg_per_week` REAL,
                    `mass_unit` TEXT NOT NULL,
                    `energy_unit` TEXT NOT NULL,
                    `onboarded_at` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE IF NOT EXISTS `weight_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `measured_on` TEXT NOT NULL,
                    `weight_kg` REAL NOT NULL,
                    `note` TEXT,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_weight_entries_measured_on` " +
                    "ON `weight_entries` (`measured_on`)"
            )
        }

        val MIGRATION_3_4 = sqlMigration(3, 4) { exec ->
            exec("ALTER TABLE `profile` ADD COLUMN `body_fat_percent` REAL")
        }

        val MIGRATION_4_5 = sqlMigration(4, 5) { exec ->
            exec("ALTER TABLE `custom_foods` ADD COLUMN `source` TEXT")
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {

            override fun migrate(db: SupportSQLiteDatabase) {
                ADD_FOLDED_COLUMNS.forEach(db::execSQL)
                val rows = mutableListOf<Triple<Long, String, String?>>()
                db.query("SELECT `id`, `name`, `brand` FROM `custom_foods`").use { cursor ->
                    while (cursor.moveToNext()) {
                        rows += Triple(
                            cursor.getLong(0),
                            cursor.getString(1),
                            if (cursor.isNull(2)) null else cursor.getString(2),
                        )
                    }
                }
                rows.forEach { (id, name, brand) ->
                    db.execSQL(
                        BACKFILL_ROW,
                        arrayOf<Any?>(foldForSearch(name), brand?.let(::foldForSearch), id),
                    )
                }
            }

            override fun migrate(connection: SQLiteConnection) {
                ADD_FOLDED_COLUMNS.forEach { connection.execSQL(it) }
                val rows = mutableListOf<Triple<Long, String, String?>>()
                connection.prepare("SELECT `id`, `name`, `brand` FROM `custom_foods`").use { read ->
                    while (read.step()) {
                        rows += Triple(
                            read.getLong(0),
                            read.getText(1),
                            if (read.isNull(2)) null else read.getText(2),
                        )
                    }
                }
                rows.forEach { (id, name, brand) ->
                    connection.prepare(BACKFILL_ROW).use { write ->
                        write.bindText(1, foldForSearch(name))
                        val folded = brand?.let(::foldForSearch)
                        if (folded == null) write.bindNull(2) else write.bindText(2, folded)
                        write.bindLong(3, id)
                        write.step()
                    }
                }
            }
        }

        private val ADD_FOLDED_COLUMNS = listOf(
            "ALTER TABLE `custom_foods` ADD COLUMN `name_folded` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `custom_foods` ADD COLUMN `brand_folded` TEXT",
        )

        private const val BACKFILL_ROW =
            "UPDATE `custom_foods` SET `name_folded` = ?, `brand_folded` = ? WHERE `id` = ?"

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
        )
    }
}
