package com.flowstate.focus.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SessionEntity::class, TaskEntity::class, ExamEntity::class, SyllabusProgressEntity::class], version = 4, exportSchema = true)
@TypeConverters(DateConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun taskDao(): TaskDao
    abstract fun examDao(): ExamDao
    abstract fun syllabusDao(): SyllabusDao

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

        /** 1.7.0: exam countdowns. A new table only; sessions and tasks are untouched. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exams` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `date` INTEGER NOT NULL, " +
                        "`emoji` TEXT NOT NULL, `tag` INTEGER NOT NULL, `showOnFocus` INTEGER NOT NULL, " +
                        "`pinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** 1.8.0: syllabus tracker progress. A new table only; nothing else changes. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `syllabus_progress` (" +
                        "`chapterKey` TEXT NOT NULL, `doneAt` INTEGER, `r1At` INTEGER, `r2At` INTEGER, `r3At` INTEGER, " +
                        "`r4At` INTEGER, `pyqAt` INTEGER, `mockAt` INTEGER, `pyqSets` INTEGER NOT NULL, `pyqTarget` INTEGER NOT NULL, " +
                        "`mocks` INTEGER NOT NULL, `note` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chapterKey`))"
                )
            }
        }
    }
}

/** Stores a [LocalDate] as its epoch day, so dates never shift with the phone's time zone. */
class DateConverters {
    @TypeConverter fun fromEpochDay(day: Long?): LocalDate? = day?.let(LocalDate::ofEpochDay)
    @TypeConverter fun toEpochDay(date: LocalDate?): Long? = date?.toEpochDay()
}
