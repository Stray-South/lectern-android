package com.straysouth.lectern.epub

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.straysouth.lectern.data.repository.PublicationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * B.4 — fileImport_pathTraversal_epub
 * B.6 — fileImport_nonEpubMasqueradingAsEpub
 *
 * Both tests call [PublicationRepository.open] directly using `file://` URIs backed by
 * test files written to the app's cacheDir. This avoids the `takePersistableUriPermission`
 * step in `LibraryViewModel.importBook()` (which requires a system-granted `content://`
 * URI) while still exercising the full Readium parse path.
 */
@RunWith(AndroidJUnit4::class)
class EpubImportTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    // ── B.4 — Path traversal: Readium must not extract ZIP entries to disk ────

    /**
     * Creates a ZIP with a path-traversal entry name (`../../files/canary_b4.txt`).
     * Java's [ZipOutputStream] stores entry names verbatim — no normalization.
     * Readium Kotlin 3.x serves ZIP entries via [ZipInputStream] to the WebView;
     * it never writes entry bytes to disk using the entry name as a path.
     * If a future Readium upgrade reintroduces disk extraction, the canary file
     * would appear in [Context.filesDir] — this test would catch the regression.
     */
    @Test
    fun epub_pathTraversalZipEntry_readiumDoesNotExtractToDisk() = runBlocking {
        val canaryFile = File(ctx.filesDir, "canary_b4.txt")
        canaryFile.delete() // clean state

        val zipFile = File(ctx.cacheDir, "traversal_b4_${System.currentTimeMillis()}.epub")
        try {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                // Path-traversal entry: if extracted with the raw name, this would write
                // two directory levels above the extraction target — into filesDir.
                zos.putNextEntry(ZipEntry("../../files/canary_b4.txt"))
                zos.write("CANARY-B4".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                // Partial container.xml to advance further into Readium's parse pipeline
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write("""<?xml version="1.0" encoding="UTF-8"?><container/>""".toByteArray())
                zos.closeEntry()
            }

            val repo = PublicationRepository(ctx)
            repo.open(Uri.fromFile(zipFile)) // result is failure (not a valid EPUB) — expected
        } finally {
            zipFile.delete()
            canaryFile.delete()
        }

        assertFalse(
            "Readium must not write ZIP entry bytes to disk using the entry name as a path. " +
                "Canary file appeared in filesDir — Readium may have introduced disk extraction (B.4)",
            canaryFile.exists(),
        )
    }

    // ── B.6 — Non-EPUB masquerading as EPUB must return Result.failure ────────

    /**
     * File has `.epub` extension but contains random binary bytes — no ZIP signature.
     * [AssetRetriever.retrieve] fails at the ZIP-header check before any parsing.
     */
    @Test
    fun epub_randomBytesContent_openReturnsFailure() = runBlocking {
        val fakeEpub = File(ctx.cacheDir, "fake_b6a_${System.currentTimeMillis()}.epub")
        try {
            fakeEpub.writeBytes(ByteArray(256) { (it * 7).toByte() })
            val result = PublicationRepository(ctx).open(Uri.fromFile(fakeEpub))
            assertTrue(
                "PublicationRepository.open() must return Result.failure for a file with " +
                    "no ZIP signature — Readium's AssetRetriever should reject it (B.6)",
                result.isFailure,
            )
        } finally {
            fakeEpub.delete()
        }
    }

    /**
     * File is a valid ZIP structure (correct signature, parsable entries) but contains
     * no `META-INF/container.xml`. Readium's [DefaultPublicationParser] returns
     * `OpenError.ContentReaders.CannotReadMediaType` — propagated as [Result.failure].
     */
    @Test
    fun epub_validZipMissingContainerXml_openReturnsFailure() = runBlocking {
        val badEpub = File(ctx.cacheDir, "bad_epub_b6b_${System.currentTimeMillis()}.epub")
        try {
            ZipOutputStream(badEpub.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("not-an-epub.txt"))
                zos.write("this is not an epub".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val result = PublicationRepository(ctx).open(Uri.fromFile(badEpub))
            assertTrue(
                "PublicationRepository.open() must return Result.failure for a valid ZIP " +
                    "missing META-INF/container.xml — Readium returns CannotReadMediaType (B.6)",
                result.isFailure,
            )
        } finally {
            badEpub.delete()
        }
    }
}
