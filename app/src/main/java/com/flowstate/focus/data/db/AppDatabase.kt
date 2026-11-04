package com.flowstate.focus.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SessionEntity::class, TaskEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun taskDao(): TaskDao

    companion object {
        /** 1.6.0: synced sessions. Three nullable columns and a unique index; existing rows are kept as they are. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `cloudId` TEXT")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `ownerUid` TEXT")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `origin` TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_cloudId` ON `sessions` (`cloudId`)")
            }
        }
    }
}
