package com.straysouth.lectern.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Book::class, ReadingProgress::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // NOT NULL requires a DEFAULT in ALTER TABLE ADD COLUMN (SQLite constraint).
                // All existing rows receive 'EPUB'; new rows always have format set at import.
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'EPUB'"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lectern.db",
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
            }
    }
}
