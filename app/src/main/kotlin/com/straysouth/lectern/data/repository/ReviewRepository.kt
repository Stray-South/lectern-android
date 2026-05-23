package com.straysouth.lectern.data.repository

import com.straysouth.lectern.data.db.Annotation
import com.straysouth.lectern.data.db.AnnotationDao

/**
 * V2.3 — recency-jitter review repository.
 *
 * Strategy: pull annotations that haven't been reviewed in the last
 * [DEFAULT_RECENCY_WINDOW_MS] OR have never been reviewed. RANDOM() at the
 * SQL level provides the jitter so the same set doesn't appear in the same
 * order. This is the simplest non-locked-in baseline; FSRS / SM-2 can swap
 * in later by replacing the query shape without schema migration (the
 * `lastReviewedAt` + `reviewCount` columns are forward-compatible with
 * either algorithm).
 *
 * Per v2-scope.md §V2.3: no notifications, no PendingIntent — the
 * "noPendingIntent_inMainSources" fail-closed gate stays armed.
 */
class ReviewRepository(private val dao: AnnotationDao) {

    /**
     * Returns the next review queue, capped at [limit]. Annotations with
     * NULL `lastReviewedAt` (never reviewed) are eligible immediately;
     * already-reviewed rows must be older than [recencyWindowMs] before
     * re-surfacing.
     */
    suspend fun nextQueue(
        limit: Int = DEFAULT_QUEUE_LIMIT,
        recencyWindowMs: Long = DEFAULT_RECENCY_WINDOW_MS,
        now: Long = System.currentTimeMillis(),
    ): List<Annotation> {
        val cutoff = now - recencyWindowMs
        return dao.reviewQueue(cutoffMillis = cutoff, limit = limit)
    }

    /** Mark [annotationId] as reviewed at [now]. Default to current wall clock. */
    suspend fun markReviewed(annotationId: String, now: Long = System.currentTimeMillis()) {
        dao.markReviewed(annotationId, now)
    }

    companion object {
        const val DEFAULT_QUEUE_LIMIT = 10
        // 24 hours: annotations resurface no sooner than one day after the last
        // review. Calibratable later via a settings surface (V2.3.1 candidate).
        const val DEFAULT_RECENCY_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}
