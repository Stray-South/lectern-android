package com.straysouth.lectern.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.Book
import com.straysouth.lectern.data.db.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C.3 — room_concurrentWrite_noConflict
 *
 * Room suspend DAOs dispatch to Room's internal IO-pool and wrap each operation in a
 * transaction. Two concurrent launches writing to different tables (books, reading_progress)
 * must both complete without SQLiteConstraintException or IllegalStateException.
 */
@RunWith(AndroidJUnit4::class)
class RoomConcurrencyTest {

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
    fun concurrentBookAndProgressWrite_bothComplete() = runBlocking {
        val book = Book("b1", "Concurrent Book", "content://x", null, 1000L, null, "EPUB")
        // Pre-insert the book so progress FK integrity is satisfied (bare column, not enforced,
        // but mirrors production write order in LibraryViewModel + EpubReaderViewModel).
        db.bookDao().upsert(book)

        val j1 = launch(Dispatchers.IO) {
            db.bookDao().upsert(book.copy(title = "Concurrent Book — updated"))
        }
        val j2 = launch(Dispatchers.IO) {
            db.readingProgressDao().upsert(ReadingProgress("p1", "b1", "{}", 0.42, 2000L))
        }
        joinAll(j1, j2)

        assertNotNull("Book must be present after concurrent write", db.bookDao().getById("b1"))
    }

    @Test
    fun concurrentUpserts_sameBook_lastWriteWins() = runBlocking {
        val base = Book("b1", "Title A", "content://x", null, 1000L, null, "EPUB")
        db.bookDao().upsert(base)

        // Both coroutines upsert the same row — REPLACE strategy, no conflict error expected.
        val j1 = launch(Dispatchers.IO) { db.bookDao().upsert(base.copy(title = "Title B")) }
        val j2 = launch(Dispatchers.IO) { db.bookDao().upsert(base.copy(title = "Title C")) }
        joinAll(j1, j2)

        // One winner — doesn't matter which; the row must exist with no crash.
        assertNotNull(db.bookDao().getById("b1"))
    }
}
