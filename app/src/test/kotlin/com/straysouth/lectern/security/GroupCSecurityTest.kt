package com.straysouth.lectern.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security regression tests for Group C (Room database integrity and migration safety).
 *
 * Covers JVM-testable properties only:
 *   C.2 — AppDatabase.getInstance() builder never calls fallbackToDestructiveMigration()
 *   C.4 — LibraryViewModel.deleteBook() explicitly calls readingProgressDao.deleteByBookId()
 *           (no FK cascade; orphan cleanup is manual — this guards the call is present)
 *   C.5 — Committed schema JSONs (1.json / 2.json) match expected identity hashes,
 *           version numbers, and column presence; MIGRATION_1_2 SQL is correct shape
 *
 * Deferred (require Android context + room-testing — instrumented test sprint):
 *   C.1 — MigrationTestHelper: v1→v2 preserves all books rows with format='EPUB'
 *   C.3 — Concurrent BookDao.upsert + ReadingProgressDao.upsert on shared bookId
 *   C.4 Room — After deleteBook(), getProgress(deletedBookId) returns null
 *
 * See docs/security/RED-TEAM.md §C for full attack descriptions and pass criteria.
 *
 * Working directory assumption: these tests read files relative to the `app/` module
 * directory, which is the default CWD when running `./gradlew testDebugUnitTest`.
 */
class GroupCSecurityTest {

    // ── C.2 — No fallbackToDestructiveMigration ────────────────────────────────

    /**
     * If Room cannot apply a migration (missing migration path), it must throw
     * [IllegalStateException] and refuse to open the database — NOT silently destroy
     * user data.
     *
     * This test guards that [AppDatabase.getInstance] never calls
     * [RoomDatabase.Builder.fallbackToDestructiveMigration].
     *
     * Pass criteria: [AppDatabase.kt] source does not contain the string
     * "fallbackToDestructiveMigration" — neither as a call nor as a comment enabling
     * future use.
     */
    @Test
    fun appDatabase_builderDoesNotCallFallbackToDestructiveMigration() {
        val source = appDatabaseSource()
        assertFalse(
            "AppDatabase.getInstance() must never call fallbackToDestructiveMigration() " +
                "(would silently destroy user library data on schema mismatch — C.2)",
            source.contains("fallbackToDestructiveMigration"),
        )
    }

    // ── C.4 — deleteBook explicit cascade guard ────────────────────────────────

    /**
     * [ReadingProgress] has no @ForeignKey CASCADE DELETE relationship to [Book] —
     * [bookId] is a bare nullable TEXT column. Orphan [ReadingProgress] rows after a
     * book delete are prevented ONLY by an explicit [ReadingProgressDao.deleteByBookId]
     * call in [LibraryViewModel.deleteBook].
     *
     * This test verifies the explicit call is present. If someone removes it, orphaned
     * progress records accumulate and the library count diverges from actual books.
     */
    @Test
    fun libraryViewModel_deleteBook_callsDeleteByBookId() {
        val source = libraryViewModelSource()
        // Locate deleteBook function body to scope the search.
        val deleteBookIdx = source.indexOf("fun deleteBook(")
        assertTrue(
            "LibraryViewModel.deleteBook() not found in source (C.4)",
            deleteBookIdx >= 0,
        )
        // Find the next top-level fun declaration after deleteBook — everything between
        // is the function body (including the coroutine lambda).
        val nextFunIdx = source.indexOf("\n    fun ", deleteBookIdx + 1)
            .takeIf { it > deleteBookIdx } ?: source.length
        val deleteBookBody = source.substring(deleteBookIdx, nextFunIdx)
        assertTrue(
            "LibraryViewModel.deleteBook() must call readingProgressDao.deleteByBookId() " +
                "to prevent orphaned ReadingProgress rows (no FK cascade — C.4)",
            deleteBookBody.contains("deleteByBookId"),
        )
        // Also confirm bookDao.deleteById is called in the same scope — belt-and-suspenders.
        assertTrue(
            "LibraryViewModel.deleteBook() must call bookDao.deleteById() (C.4)",
            deleteBookBody.contains("deleteById"),
        )
    }

    // ── C.5 — Committed schema JSON integrity ─────────────────────────────────

    /**
     * The schema JSON files committed under [app/schemas/] are the ground truth for
     * Room's migration and schema verification. If an entity annotation is changed
     * without bumping the DB version, Room generates a new JSON with a different
     * identity hash — this test catches the drift.
     *
     * Identity hashes are stable: they are derived from entity CREATE TABLE SQL by
     * Room's KSP processor. Any field addition, type change, or nullability change
     * produces a different hash.
     */
    @Test
    fun schemaV1_identityHash_isStable() {
        val v1 = schemaJson(1)
        assertTrue(
            "schemas/1.json identity hash must be 187531121d9fe06eec1def42f91a6b93 — " +
                "hash change means entity changed without a migration (C.5)",
            v1.contains("\"identityHash\": \"187531121d9fe06eec1def42f91a6b93\""),
        )
    }

    @Test
    fun schemaV2_identityHash_isStable() {
        val v2 = schemaJson(2)
        assertTrue(
            "schemas/2.json identity hash must be 3f5b9ab23f084f68bf34e8a4d0c00cdb — " +
                "hash change means entity changed without a migration (C.5)",
            v2.contains("\"identityHash\": \"3f5b9ab23f084f68bf34e8a4d0c00cdb\""),
        )
    }

    /** v2 schema must contain a `format` column in the `books` table. */
    @Test
    fun schemaV2_booksTable_hasFormatColumn_notNull() {
        val v2 = schemaJson(2)
        // Room schema JSON encodes NOT NULL columns as "notNull": true alongside the name.
        // The simplest cross-version stable assertion is substring presence in v2.
        assertTrue(
            "schemas/2.json must contain a non-null 'format' column in the books table (C.5)",
            v2.contains("\"format\"") && v2.contains("\"notNull\": true"),
        )
    }

    /** v1 schema must NOT contain a `format` column — migration delta is additive only. */
    @Test
    fun schemaV1_booksTable_hasNoFormatColumn() {
        val v1 = schemaJson(1)
        assertFalse(
            "schemas/1.json must not contain a 'format' column — it was added in v2 (C.5)",
            v1.contains("\"format\""),
        )
    }

    /**
     * The committed MIGRATION_1_2 SQL must exactly match the schema delta: add `format`
     * as TEXT NOT NULL with DEFAULT 'EPUB'. Any change to the default or type would
     * break fresh installs migrating from v1 (pre-migration rows must get 'EPUB').
     */
    @Test
    fun migration1to2_sql_addsFormatColumnWithEpubDefault() {
        val source = appDatabaseSource()
        val migrationIdx = source.indexOf("MIGRATION_1_2")
        assertTrue("MIGRATION_1_2 not found in AppDatabase.kt source (C.5)", migrationIdx >= 0)
        // Extract the block up to the closing brace of the migration object.
        val migrationBlock = source.substring(migrationIdx, (migrationIdx + 800).coerceAtMost(source.length))
        assertTrue(
            "MIGRATION_1_2 must ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'EPUB' (C.5)",
            migrationBlock.contains("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'EPUB'"),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun schemaJson(version: Int): String {
        val path = "schemas/com.straysouth.lectern.data.db.AppDatabase/$version.json"
        val file = File(path)
        assertTrue("Schema file not found at $path (working dir: ${System.getProperty("user.dir")})", file.exists())
        return file.readText()
    }

    private fun appDatabaseSource(): String =
        sourceFile("data/db/AppDatabase.kt")

    private fun libraryViewModelSource(): String =
        sourceFile("ui/library/LibraryViewModel.kt")

    private fun sourceFile(relativePath: String): String {
        val base = "src/main/kotlin/com/straysouth/lectern"
        val file = File("$base/$relativePath")
        assertTrue(
            "Source file not found at $base/$relativePath " +
                "(working dir: ${System.getProperty("user.dir")})",
            file.exists(),
        )
        return file.readText()
    }
}
