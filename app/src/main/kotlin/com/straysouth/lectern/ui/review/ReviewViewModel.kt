package com.straysouth.lectern.ui.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.repository.ReviewRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * V2.3 — review queue view-model.
 *
 * Loads a recency-jitter queue on entry, walks the user through it one
 * annotation at a time. Two actions:
 *
 *   - "Got it" → markReviewed(now) → advance.
 *   - "Review later" → advance without marking; the annotation stays in
 *     the eligible pool for next session.
 *
 * No streak, no daily goal, no notifications — AuDHD copy rules per
 * RULES.md §AuDHD copy + check_banned_strings.sh.
 */
class ReviewViewModel(application: Application) : AndroidViewModel(application) {

    private val reviewRepository = ReviewRepository(
        AppDatabase.getInstance(application).annotationDao(),
    )

    private val _state = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val state: StateFlow<ReviewUiState> = _state

    /** Loads a fresh queue. Called on screen entry. */
    fun load() {
        viewModelScope.launch {
            val queue = reviewRepository.nextQueue()
            _state.value = if (queue.isEmpty()) {
                ReviewUiState.Empty
            } else {
                ReviewUiState.Ready(queue = queue, currentIndex = 0)
            }
        }
    }

    /** Mark the current annotation as reviewed and advance the queue. */
    fun markReviewedAndAdvance() {
        val current = _state.value as? ReviewUiState.Ready ?: return
        val ann = current.currentAnnotation ?: return
        viewModelScope.launch {
            reviewRepository.markReviewed(ann.id)
            advance(current)
        }
    }

    /** Skip the current annotation without marking; it remains in the pool. */
    fun skipCurrent() {
        val current = _state.value as? ReviewUiState.Ready ?: return
        advance(current)
    }

    private fun advance(current: ReviewUiState.Ready) {
        val next = current.currentIndex + 1
        _state.value = if (next >= current.queue.size) {
            ReviewUiState.Done
        } else {
            current.copy(currentIndex = next)
        }
    }
}
