package com.straysouth.lectern.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * V2.2 — DAO for [Annotation].
 *
 * Single-device persistence per ADR-AND-T. Multi-device sync is gated on
 * V2.1 cloud sync (see ADR-AND-S, when filed); the annotation surface ships
 * local-first.
 *
 * `observeForBook` is the read path for rendering annotations as Readium
 * decorations in the open book; suspend functions are write paths (Room
 * 2.1+ dispatches suspend DAO calls off the main thread automatically per
 * RULES.md §Threading, so callers do not need an explicit Dispatcher).
 */
@Dao
interface AnnotationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(annotation: Annotation)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM annotations WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY createdAt ASC")
    fun observeForBook(bookId: String): Flow<List<Annotation>>

    @Query("SELECT COUNT(*) FROM annotations WHERE bookId = :bookId")
    suspend fun countForBook(bookId: String): Int

    /**
     * V2.3 — recency-jitter review queue.
     *
     * Pulls annotations whose `lastReviewedAt` is older than [cutoffMillis]
     * OR has never been reviewed (NULL). RANDOM() ordering is the "jitter"
     * so the same set doesn't reappear in the same sequence (no clumping +
     * variety across re-opens). [limit] caps session size.
     */
    @Query(
        "SELECT * FROM annotations " +
            "WHERE lastReviewedAt IS NULL OR lastReviewedAt < :cutoffMillis " +
            "ORDER BY RANDOM() LIMIT :limit"
    )
    suspend fun reviewQueue(cutoffMillis: Long, limit: Int): List<Annotation>

    /** V2.3 — mark an annotation as reviewed now; increments reviewCount. */
    @Query(
        "UPDATE annotations SET lastReviewedAt = :now, reviewCount = reviewCount + 1 " +
            "WHERE id = :id"
    )
    suspend fun markReviewed(id: String, now: Long)
}
