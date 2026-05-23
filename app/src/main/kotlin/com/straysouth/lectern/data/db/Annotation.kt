package com.straysouth.lectern.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * V2.2 — user-authored annotation on EPUB content.
 *
 * MVP scope: highlight only. The `type` column is reserved for future
 * expansion ("note", "underline", etc.) per ADR-AND-T.
 *
 * `locatorJson` stores the Readium [Locator] serialized via [Locator.toJSON]
 * (NOT via string interpolation — that path is banned by ADR-AND-N to
 * prevent injection via crafted publication metadata).
 *
 * `bookId` is a foreign key to [Book.id]; on book delete the cascade
 * removes the user's annotations for that book. The library Delete-Book
 * confirmation dialog should surface this — text TBD per the UI follow-up.
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class Annotation(
    @PrimaryKey val id: String,
    val bookId: String,
    val locatorJson: String,
    val type: String,                // "highlight" only in V2.2 MVP; reserved for "note" etc.
    val createdAt: Long,
    val body: String? = null,        // user note text — null for plain highlights
    val lastReviewedAt: Long? = null,    // V2.3 — null until first review tick
    val reviewCount: Int = 0,            // V2.3 — incremented on each "Got it"
)
