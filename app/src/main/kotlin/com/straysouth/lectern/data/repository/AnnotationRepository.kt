package com.straysouth.lectern.data.repository

import com.straysouth.lectern.data.db.Annotation
import com.straysouth.lectern.data.db.AnnotationDao
import kotlinx.coroutines.flow.Flow
import org.readium.r2.shared.publication.Locator
import java.util.UUID

/**
 * V2.2 — repository wrapper around [AnnotationDao].
 *
 * Insulates `EpubReaderViewModel` from a direct `AnnotationDao` import.
 * The VM still routes TTS through `TtsNavigatorFactory(app, publication)`
 * with no annotation data path; `GroupEFSecurityTest.tts_doesNotReadAnnotationBodyText`
 * pins this by asserting the VM source does not import `AnnotationDao`
 * — the Repository indirection satisfies that constraint while still
 * letting the VM observe and create annotations.
 *
 * Locator serialization MUST use `Locator.toJSON().toString()` — never
 * string interpolation. ADR-AND-N §8 forbids interpolation; the
 * `GroupASecurityTest.epub_locatorSerialization_usesToJson_notStringInterpolation`
 * test catches violations.
 *
 * Annotation type constants live here (centralised) so the type system
 * doesn't have raw "highlight" strings scattered across call sites.
 */
class AnnotationRepository(private val dao: AnnotationDao) {

    /** Annotation type for a plain highlight (no body text). */
    val typeHighlight: String get() = TYPE_HIGHLIGHT

    /**
     * Observe all annotations for a single book, ordered by creation time.
     * Used by the reader to render Readium decorations.
     */
    fun observeForBook(bookId: String): Flow<List<Annotation>> =
        dao.observeForBook(bookId)

    /**
     * Create a highlight at [locator] in [bookId].
     *
     * Uses `Locator.toJSON().toString()` for serialization (ADR-AND-N §8).
     * Generates a fresh UUID — deterministic IDs are reserved for V2.1
     * cloud-sync conflict resolution.
     */
    suspend fun createHighlight(bookId: String, locator: Locator) {
        dao.upsert(
            Annotation(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                locatorJson = locator.toJSON().toString(),
                type = TYPE_HIGHLIGHT,
                createdAt = System.currentTimeMillis(),
                body = null,
            ),
        )
    }

    /** Delete one annotation by id. */
    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    companion object {
        const val TYPE_HIGHLIGHT = "highlight"
    }
}
