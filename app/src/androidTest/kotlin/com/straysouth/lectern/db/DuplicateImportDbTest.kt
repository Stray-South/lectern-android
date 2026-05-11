package com.straysouth.lectern.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.Book
import com.straysouth.lectern.data.db.ReadingProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B.7 DB — fileImport_duplicateBook_noDataCorruption (DB behavior half)
 *
 * `BookDao.upsert()` uses `OnConflictStrategy.REPLACE`. SQLite's INSERT OR REPLACE
 * internally DELETEs the conflicting row then INSERTs a new one. Because there is no
 * foreign-key constraint between `reading_progress.bookId` and `books.id` (bare
 * nullable TEXT column — confirmed in schema v2), the DELETE does NOT cascade.
 * Reading progress accumulated by the user must survive a re-import of the same book.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateImportDbTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun reimportSameBook_readingProgressSurvives() = runBlocking {
        // Simulate first import + user reading progress
        db.bookDao().upsert(Book("b1", "My EPUB", "content://x", null, 1000L, null, "EPUB"))
        db.readingProgressDao().upsert(ReadingProgress("p1", "b1", "{}", 0.42, 2000L))

        // Simulate re-import of the same URI → same deterministic ID → REPLACE upsert
        db.bookDao().upsert(Book("b1", "My EPUB", "content://x", null, 3000L, null, "EPUB"))

        val progress = db.readingProgressDao().observeAll().first()
        assertTrue(
            "ReadingProgress must survive BookDao.upsert(REPLACE) — SQLite REPLACE deletes " +
                "the conflicting books row but there is no FK cascade to reading_progress (B.7)",
            progress.any { it.bookId == "b1" },
        )
    }

    @Test
    fun reimportSameBook_libraryShowsOneEntry() = runBlocking {
        db.bookDao().upsert(Book("b1", "My EPUB", "content://x", null, 1000L, null, "EPUB"))
        db.bookDao().upsert(Book("b1", "My EPUB", "content://x", null, 2000L, null, "EPUB"))

        val books = db.bookDao().observeAll().first()
        assertEquals(
            "Re-importing the same book (same UUID) must produce exactly one library entry (B.7)",
            1,
            books.size,
        )
    }

    @Test
    fun reimportSameBook_progressTotalProgressionPreserved() = runBlocking {
        db.bookDao().upsert(Book("b1", "My EPUB", "content://x", null, 1000L, null, "EPUB"))
        db.readingProgressDao().upsert(ReadingProgress("p1", "b1", "{}", 0.75, 2000L))

        // Re-import (metadata update only — progress is unaffected)
        db.bookDao().upsert(Book("b1", "My EPUB — 2nd edition", "content://x", null, 3000L, null, "EPUB"))

        val progress = db.readingProgressDao().observeAll().first()
            .firstOrNull { it.bookId == "b1" }
        assertNotNull(
            "ReadingProgress row for bookId b1 must still exist after re-import (B.7)",
            progress,
        )
        assertEquals(
            "totalProgression must be unchanged after book re-import (B.7)",
            0.75,
            progress!!.totalProgression!!,
            1e-9,
        )
    }
}
