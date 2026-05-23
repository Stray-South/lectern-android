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
        // Bound deleteBook's body by the next class member (any modifier — incl. private
        // suspend fun, val, var, companion object, annotation). The naive
        // `indexOf("\n    fun ")` falls back to source.length when the next sibling is
        // anything other than a bare `fun`, allowing later-function tokens to pass the
        // assertion vacuously. Use the harmonized helper that mirrors GroupIJ's pattern.
        val nextFunIdx = nextClassMemberIndex(source, deleteBookIdx)
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

    @Test
    fun schemaV3_identityHash_isStable() {
        val v3 = schemaJson(3)
        assertTrue(
            "schemas/3.json identity hash must be bc4fbc00e389fb0e485fb29f7ad4ce3a — " +
                "hash change means entity changed without a migration (C.5; ADR-AND-T)",
            v3.contains("\"identityHash\": \"bc4fbc00e389fb0e485fb29f7ad4ce3a\""),
        )
    }

    @Test
    fun schemaV4_identityHash_isStable() {
        val v4 = schemaJson(4)
        assertTrue(
            "schemas/4.json identity hash must be cccc4aec1c09b4f2f149583c5e381830 — " +
                "hash change means annotation entity changed without a migration (V2.3)",
            v4.contains("\"identityHash\": \"cccc4aec1c09b4f2f149583c5e381830\""),
        )
    }

    /** V2.3 — v4 schema must have the review columns on annotations. */
    @Test
    fun schemaV4_annotationsTable_hasReviewColumns() {
        val v4 = schemaJson(4)
        assertTrue(
            "annotations table createSql must include lastReviewedAt INTEGER (nullable)",
            v4.contains("`lastReviewedAt` INTEGER"),
        )
        assertTrue(
            "annotations table createSql must include reviewCount INTEGER NOT NULL " +
                "(Room normalizes the DEFAULT 0 out of the createSql; the migration path " +
                "carries the DEFAULT for existing rows, fresh installs get it from the data class)",
            v4.contains("`reviewCount` INTEGER NOT NULL"),
        )
    }

    /** v3 schema must register the `annotations` table from ADR-AND-T. */
    @Test
    fun schemaV3_annotationsTable_isRegistered() {
        val v3 = schemaJson(3)
        assertTrue(
            "schemas/3.json must register the annotations table (ADR-AND-T)",
            v3.contains("\"tableName\": \"annotations\""),
        )
        // Foreign-key on books with CASCADE delete + index on bookId.
        assertTrue(
            "annotations table must FK-cascade-delete on books (ADR-AND-T storage spec)",
            v3.contains("\"onDelete\": \"CASCADE\""),
        )
        assertTrue(
            "annotations table must have an index on bookId for the per-book observe() " +
                "query (ADR-AND-T)",
            v3.contains("index_annotations_bookId"),
        )
    }

    /** v2 schema must contain a `format TEXT NOT NULL` column in the `books` table. */
    @Test
    fun schemaV2_booksTable_hasFormatColumn_notNull() {
        val v2 = schemaJson(2)
        // Assert against the books table's createSql — this is table-scoped (reading_progress
        // has no format column) and encodes both column name and NOT NULL in one substring.
        // Using createSql rather than the column-object JSON avoids the false-positive risk
        // of a file-wide "notNull": true check matching the id column of either table.
        assertTrue(
            "schemas/2.json books createSql must contain `format` TEXT NOT NULL (C.5)",
            v2.contains("`format` TEXT NOT NULL"),
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
        // Extract a 2000-char window around the migration declaration — large enough to
        // accommodate any KDoc block added before the execSQL call in the future.
        val migrationBlock = source.substring(migrationIdx, (migrationIdx + 2000).coerceAtMost(source.length))
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

    /**
     * Mirrors `GroupIJSecurityTest.nextClassMemberIndex` — comprehensive pattern list
     * including all `suspend fun` variants, vals, vars, and annotations so a body window
     * doesn't silently overrun the target function when a sibling has a non-`fun`
     * modifier (e.g. `private suspend fun`).
     */
    private fun nextClassMemberIndex(source: String, afterIdx: Int): Int =
        listOf(
            "\n    fun ", "\n    private fun ", "\n    override fun ", "\n    internal fun ",
            "\n    override suspend fun ", "\n    private suspend fun ", "\n    suspend fun ",
            "\n    internal suspend fun ", "\n    protected suspend fun ",
            "\n    val ", "\n    var ", "\n    private val ", "\n    private var ",
            "\n    companion object", "\n    @",
        )
            .mapNotNull { pattern ->
                source.indexOf(pattern, afterIdx + 1).takeIf { it > afterIdx }
            }
            .minOrNull() ?: source.length
}
