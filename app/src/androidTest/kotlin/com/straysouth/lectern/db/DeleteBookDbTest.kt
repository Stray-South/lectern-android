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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C.4 DB — room_deleteBook_cascadeProgress (DB behavior half)
 *
 * There is no SQLite foreign-key cascade; `LibraryViewModel.deleteBook()` calls
 * `bookDao.deleteById` then `readingProgressDao.deleteByBookId` explicitly.
 * This test verifies the DAO contract: after both deletes, no progress row with
 * that bookId remains observable.
 */
@RunWith(AndroidJUnit4::class)
class DeleteBookDbTest {

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
    fun deleteBook_thenDeleteByBookId_progressIsGone() = runBlocking {
        db.bookDao().upsert(Book("b1", "My Book", "content://x", null, 1000L, null, "EPUB"))
        db.readingProgressDao().upsert(ReadingProgress("p1", "b1", "{}", 0.5, 2000L))

        // Replicate what LibraryViewModel.deleteBook() does
        db.bookDao().deleteById("b1")
        db.readingProgressDao().deleteByBookId("b1")

        val remaining = db.readingProgressDao().observeAll().first()
        assertTrue(
            "Expected no progress rows for deleted book; found: $remaining",
            remaining.none { it.bookId == "b1" },
        )
    }

    @Test
    fun deleteBook_otherProgressUnaffected() = runBlocking {
        db.bookDao().upsert(Book("b1", "Book 1", "content://1", null, 1000L, null, "EPUB"))
        db.bookDao().upsert(Book("b2", "Book 2", "content://2", null, 2000L, null, "EPUB"))
        db.readingProgressDao().upsert(ReadingProgress("p1", "b1", "{}", 0.3, 1000L))
        db.readingProgressDao().upsert(ReadingProgress("p2", "b2", "{}", 0.7, 2000L))

        db.bookDao().deleteById("b1")
        db.readingProgressDao().deleteByBookId("b1")

        val remaining = db.readingProgressDao().observeAll().first()
        assertTrue("b2 progress must survive", remaining.any { it.bookId == "b2" })
        assertTrue("b1 progress must be gone", remaining.none { it.bookId == "b1" })
    }
}
