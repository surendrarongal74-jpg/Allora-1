package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.BookingRecordDao
import com.example.data.local.dao.TravelPhotoDao
import com.example.data.local.dao.UserPreferenceDao
import com.example.data.local.entity.BookingRecord
import com.example.data.local.entity.TravelPhotoRecord
import com.example.data.local.entity.UserPreference

@Database(
    entities = [
        BookingRecord::class,
        UserPreference::class,
        TravelPhotoRecord::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AlloraDatabase : RoomDatabase() {
    abstract fun bookingRecordDao(): BookingRecordDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun travelPhotoDao(): TravelPhotoDao

    companion object {
        @Volatile
        private var INSTANCE: AlloraDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop un-scoped v1 cache table so records from prior sessions cannot leak across accounts
                db.execSQL("DROP TABLE IF EXISTS `booking_records`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `booking_records` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `reference_code` TEXT NOT NULL,
                        `booking_type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `provider_source` TEXT NOT NULL,
                        `provider_pnr` TEXT,
                        `origin_or_location` TEXT NOT NULL,
                        `destination` TEXT,
                        `start_timestamp` INTEGER NOT NULL,
                        `end_timestamp` INTEGER,
                        `total_amount_usd` REAL NOT NULL,
                        `currency` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `sha256_audit_hash` TEXT NOT NULL,
                        `metadata_json` TEXT NOT NULL,
                        `is_synced` INTEGER NOT NULL,
                        `last_synced_at` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_booking_records_user_id` ON `booking_records` (`user_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_booking_records_user_id_reference_code` ON `booking_records` (`user_id`, `reference_code`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_booking_records_user_id_status` ON `booking_records` (`user_id`, `status`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `display_name` TEXT NOT NULL DEFAULT 'Traveler'")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `email` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `travel_style` TEXT NOT NULL DEFAULT 'Luxury & Culture'")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `budget_tier` TEXT NOT NULL DEFAULT 'Balanced Premium'")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `dark_glass_theme` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `user_preferences` ADD COLUMN `recent_searches_serialized` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `travel_photos` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `file_path` TEXT NOT NULL,
                        `caption` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `destination_name` TEXT NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL,
                        `captured_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_travel_photos_user_id` ON `travel_photos` (`user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_travel_photos_user_id_captured_at` ON `travel_photos` (`user_id`, `captured_at`)")
            }
        }

        fun getInstance(context: Context): AlloraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlloraDatabase::class.java,
                    "allora_offline_cache.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
