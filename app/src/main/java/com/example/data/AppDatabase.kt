package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FolderEntity::class,
        LargeIconEntity::class,
        FolderAppEntity::class,
        WidgetBindingEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun largeIconDao(): LargeIconDao
    abstract fun folderAppDao(): FolderAppDao
    abstract fun widgetBindingDao(): WidgetBindingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `large_icons` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `appName` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `activityName` TEXT NOT NULL,
                        `orientation` TEXT NOT NULL,
                        `bgStyle` TEXT NOT NULL,
                        `cornerRadiusDp` INTEGER NOT NULL,
                        `iconPaddingDp` INTEGER NOT NULL,
                        `showLabel` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "ALTER TABLE `widget_bindings` ADD COLUMN `itemType` TEXT NOT NULL DEFAULT 'FOLDER'"
                )
                db.execSQL(
                    "ALTER TABLE `widget_bindings` ADD COLUMN `largeIconId` INTEGER NOT NULL DEFAULT -1"
                )
                db.execSQL(
                    "UPDATE `widget_bindings` SET `itemType` = 'FOLDER' WHERE `itemType` IS NULL OR `itemType` = ''"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "folder_widgets_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
