package com.straysouth.lectern.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Book::class, ReadingProgress::class, Annotation::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun annotationDao(): AnnotationDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // V2.2 — annotations table. SQL deliberately mirrors the Room-generated
                // createSql in app/schemas/.../3.json so MigrationTestHelper's schema
                // comparison passes byte-for-byte: backtick identifiers, table-level
                // PRIMARY KEY (NOT inline on the id column), FK at the table tail.
                // See ADR-AND-T for the full design.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `annotations` (" +
                        "`id` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`locatorJson` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`body` TEXT, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotations_bookId` " +
                        "ON `annotations` (`bookId`)"
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
            }
    }
}
