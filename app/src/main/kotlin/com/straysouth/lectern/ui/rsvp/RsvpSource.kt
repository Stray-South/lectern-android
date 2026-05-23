package com.straysouth.lectern.ui.rsvp

import android.net.Uri

/**
 * V2.4 — RSVP content source. The three entry points produce one of three
 * variants:
 *
 *   - [Book] — open EPUB by id; text comes from the Readium publication.
 *   - [TxtUri] — SAF-picked .txt URI (cleartext path forbidden — must be a
 *     content:// URI, never file://). Per ADR-AND-X §Storage.
 *   - [Clipboard] — primary clip text snapshotted at entry time. Held only
 *     in memory; never written to disk, logs, or analytics (ADR-AND-X
 *     §Privacy).
 */
sealed class RsvpSource {
    data class Book(val bookId: String) : RsvpSource()
    data class TxtUri(val uri: Uri) : RsvpSource()
    data class Clipboard(val text: String) : RsvpSource()
}
