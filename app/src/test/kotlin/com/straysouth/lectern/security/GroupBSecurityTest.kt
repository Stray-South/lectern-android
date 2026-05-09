package com.straysouth.lectern.security

import com.straysouth.lectern.ui.library.LibraryViewModel
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

/**
 * Security regression tests for Group B (file import from untrusted sources).
 *
 * Covers JVM-testable properties only:
 *   B.3 — zip4j getInputStream() is read-only (no disk extraction on traversal entries)
 *   B.5 — bookCacheId() is keyed on URI string, not DISPLAY_NAME metadata column
 *   B.7 — bookCacheId() determinism guarantees correct upsert semantics on duplicate import
 *
 * Deferred (require Android context — instrumented test sprint):
 *   B.4 — Readium streamer never extracts EPUB to disk (needs PublicationRepository + Context)
 *   B.6 — Invalid EPUB returns importError, no Room write (needs Context + Room)
 *   B.7 Room upsert — single row after duplicate import (needs Context + Room)
 *
 * See docs/security/RED-TEAM.md §B for full attack descriptions and pass criteria.
 */
class GroupBSecurityTest {

    // ── B.3 — Path traversal in CBZ ───────────────────────────────────────────

    /**
     * Regression guard: zip4j [Zip4jFile.getInputStream] must be purely read-only,
     * and zip4j must surface traversal entry names unchanged (not normalise them).
     *
     * Attack: A CBZ whose entry names contain `../../` components targets app data
     * outside the cache directory. Safety rests on two properties:
     *  1. [getInputStream] creates zero filesystem artifacts — the ViewModel only
     *     calls [getInputStream], never [Zip4jFile.extractAll] or [extractFile].
     *  2. zip4j surfaces the raw entry name (including `../`), not a normalised path.
     *     The ViewModel uses [FileHeader.fileName] as a read-only lookup key —
     *     confirmed never passed to [File] constructors.
     *
     * Both properties are asserted here. If zip4j were to silently normalise entry
     * names, the second assertion catches it, signalling the ViewModel safety argument
     * needs re-evaluation.
     */
    @Test
    fun cbz_pathTraversalEntry_getInputStreamDoesNotExtract() {
        val tempDir = createTempDirectory("b3_test").toFile()
        try {
            val zipFile = createTraversalCbz(tempDir)
            val filesBefore = tempDir.walkTopDown().map { it.absolutePath }.toSortedSet()
            // Open with zip4j and call getInputStream on every header — mirrors
            // the ViewModel's renderZipPage() path exactly.
            val entryNames = readAllZipEntries(zipFile)
            val filesAfter = tempDir.walkTopDown().map { it.absolutePath }.toSortedSet()
            assertEquals(
                "getInputStream() must not create new filesystem entries " +
                    "(path traversal regression guard — B.3)",
                filesBefore,
                filesAfter,
            )
            // zip4j must NOT normalise traversal sequences — the ViewModel relies on
            // entry names being opaque read-only keys, not filesystem-resolved paths.
            assertTrue(
                "zip4j must surface raw traversal entry names unchanged (B.3) — " +
                    "got: $entryNames",
                entryNames.any { it.contains("../") },
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // Build a ZIP (JDK library — zip4j can read JDK-created ZIPs) with a
    // traversal-path entry whose image extension passes isImageFile() in the ViewModel.
    private fun createTraversalCbz(dir: File): File {
        val zipFile = File(dir, "test.cbz")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../../traversal_target.png"))
            zos.write(byteArrayOf(1, 2, 3, 4, 5))
            zos.closeEntry()
            // Legitimate entry — confirms normal reads still work.
            zos.putNextEntry(ZipEntry("page001.jpg"))
            zos.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
            zos.closeEntry()
        }
        return zipFile
    }

    // Open with zip4j and call getInputStream on every header — same path as
    // ComicsReaderViewModel.renderZipPage() but without bitmap decode.
    // Returns the raw FileHeader.fileName values so callers can assert normalisation behaviour.
    private fun readAllZipEntries(zipFile: File): List<String> =
        Zip4jFile(zipFile).use { zip ->
            zip.fileHeaders.map { header ->
                zip.getInputStream(header).use { it.readBytes() }
                header.fileName
            }
        }

    // ── B.5 — content:// DISPLAY_NAME path traversal ──────────────────────────

    /**
     * [LibraryViewModel.bookCacheId] derives its output from the full URI string,
     * not from any ContentResolver metadata column such as DISPLAY_NAME.
     * A malicious provider returning `DISPLAY_NAME = "../../databases/lectern.db"`
     * cannot affect the cache key because [LibraryViewModel] never calls
     * [ContentResolver.query] with [android.provider.OpenableColumns.DISPLAY_NAME].
     *
     * Cross-session stability: the expected UUID is the deterministic MD5-derived
     * UUID v3 of the UTF-8 bytes of the URI string (Java [UUID.nameUUIDFromBytes]).
     * If the hashing algorithm or encoding ever changes, this test catches it across
     * separate JVM sessions — unlike a same-call equality check.
     */
    @Test
    fun bookCacheId_knownUri_matchesExpectedUuid() {
        val uri = "content://com.android.externalstorage/document/primary%3ADownloads%2Fbook.epub"
        assertEquals(
            "bookCacheId must produce a stable, deterministic UUID across JVM sessions",
            "00a4f86e-a2c5-39bb-a313-5ed48abb9580",
            LibraryViewModel.bookCacheId(uri),
        )
    }

    /** Output matches the canonical UUID format (8-4-4-4-12 lowercase hex). */
    @Test
    fun bookCacheId_outputIsUuidFormat() {
        val id = LibraryViewModel.bookCacheId("content://host/path/book.epub")
        assertTrue(
            "bookCacheId output must be a UUID string, got: $id",
            id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
        )
    }

    /**
     * The ID is keyed on the full URI string, not on the filename path segment.
     * Two URIs that share the same filename but differ in authority or path must
     * produce different IDs — otherwise a rogue provider at a different authority
     * could collide with a legitimately imported book.
     */
    @Test
    fun bookCacheId_isKeyedOnFullUri_notFilenameSegment() {
        val uriA = "content://com.example.provider/document/1/great_expectations.epub"
        val uriB = "content://com.other.provider/document/1/great_expectations.epub"
        assertNotEquals(
            "Different URI authorities with the same filename must not collide",
            LibraryViewModel.bookCacheId(uriA),
            LibraryViewModel.bookCacheId(uriB),
        )
    }

    /**
     * A provider returning `DISPLAY_NAME = "../../databases/lectern.db"` cannot
     * influence the file path because the ID is derived from the URI itself.
     * Constructing a URI that embeds the traversal string produces a different ID
     * from the legitimate URI — the traversal path has no effect on the real key.
     */
    @Test
    fun bookCacheId_traversalInDisplayName_hasNoEffect() {
        val legitimateUri = "content://com.test.provider/files/42"
        val traversalAttemptUri = "content://com.test.provider/files/42/../../databases/lectern.db"
        assertNotEquals(
            "A traversal string appended to the URI must not collide with the legitimate URI",
            LibraryViewModel.bookCacheId(legitimateUri),
            LibraryViewModel.bookCacheId(traversalAttemptUri),
        )
    }

    /** Non-ASCII URI characters are handled stably (explicit UTF-8 encoding). */
    @Test
    fun bookCacheId_nonAsciiUri_stableAcrossCalls() {
        val uri = "content://host/files/読書.epub"
        assertEquals(
            "Non-ASCII URI must produce a stable UUID across calls",
            LibraryViewModel.bookCacheId(uri),
            LibraryViewModel.bookCacheId(uri),
        )
    }

    // ── B.7 — Duplicate import determinism ────────────────────────────────────

    /**
     * Importing the same URI twice must produce identical IDs so that
     * [BookDao.upsert] (OnConflictStrategy.REPLACE) overwrites rather than
     * duplicates the row. This is the JVM-testable half of the B.7 guarantee;
     * the Room upsert behaviour itself is covered by the deferred instrumented test.
     */
    @Test
    fun bookCacheId_idempotent_onDuplicateImport() {
        val uri = "content://media.documents/document/epub%3A42"
        assertEquals(
            "Duplicate import of the same URI must produce the same book ID",
            LibraryViewModel.bookCacheId(uri),
            LibraryViewModel.bookCacheId(uri),
        )
    }

    /**
     * Two distinct URIs pointing to files with identical content but different
     * paths must get different IDs (treated as separate library entries, not merged).
     * This is correct and expected behaviour — not a bug.
     */
    @Test
    fun bookCacheId_differentUri_differentId_evenIfSameContent() {
        val uri1 = "content://com.example.provider/doc/1/book.epub"
        val uri2 = "content://com.example.provider/doc/2/book.epub"
        assertNotEquals(
            "Different URIs must not share a book ID even if the filename is identical",
            LibraryViewModel.bookCacheId(uri1),
            LibraryViewModel.bookCacheId(uri2),
        )
    }
}
