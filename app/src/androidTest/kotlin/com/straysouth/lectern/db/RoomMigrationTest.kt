package com.straysouth.lectern.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.straysouth.lectern.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C.1 — room_migration_v1v2_noDataLoss
 *
 * Creates a v1 database, inserts a book and a reading-progress row, runs MIGRATION_1_2,
 * then asserts:
 * - the book row is still present
 * - format defaulted to 'EPUB' (NOT NULL column with DEFAULT)
 * - the reading_progress row is untouched
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration1to2_existingBookGetsEpubDefault() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO books (id, title, filePath, coverPath, addedAt, lastOpenedAt) " +
                    "VALUES ('book-1', 'My Book', 'content://x', null, 1000, null)"
            )
            db.execSQL(
                "INSERT INTO reading_progress (id, bookId, locatorJson, totalProgression, updatedAt) " +
                    "VALUES ('prog-1', 'book-1', '{}', 0.25, 2000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        // Book row: format must be 'EPUB' (default applied by ALTER TABLE)
        db.query("SELECT format FROM books WHERE id = 'book-1'").use { cursor ->
            assertTrue("No book row after migration", cursor.moveToFirst())
            assertEquals("EPUB", cursor.getString(0))
        }

        // ReadingProgress row must survive intact — no data loss on unrelated table
        db.query("SELECT totalProgression FROM reading_progress WHERE id = 'prog-1'").use { cursor ->
            assertTrue("No progress row after migration", cursor.moveToFirst())
            assertEquals(0.25, cursor.getDouble(0), 1e-9)
        }

        db.close()
    }

    @Test
    fun migration1to2_multipleBooks_allSurvive() {
        helper.createDatabase(TEST_DB + "_multi", 1).use { db ->
            for (i in 1..3) {
                db.execSQL(
                    "INSERT INTO books (id, title, filePath, coverPath, addedAt, lastOpenedAt) " +
                        "VALUES ('book-$i', 'Book $i', 'content://$i', null, ${i * 1000L}, null)"
                )
            }
        }

        val db = helper.runMigrationsAndValidate(TEST_DB + "_multi", 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM books").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Expected 3 books", 3, cursor.getInt(0))
        }
        db.close()
    }

    private companion object {
        private const val TEST_DB = "migration-test"
    }
}
