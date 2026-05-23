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

    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * V2.3 — load a fresh queue. Called on screen entry.
     *
     * V2.3 fix (adversarial finding #2): re-entering the screen re-fires
     * LaunchedEffect(Unit) and calls load() again. Cancel-and-replace the
     * in-flight job so the later call wins deterministically, and snap
     * state back to Loading so the user sees the refresh.
     */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = ReviewUiState.Loading
            val queue = reviewRepository.nextQueue()
            _state.value = if (queue.isEmpty()) {
                ReviewUiState.Empty
            } else {
                ReviewUiState.Ready(queue = queue, currentIndex = 0)
            }
        }
    }

    /**
     * V2.3 — mark current as reviewed and advance.
     *
     * V2.3 fix (adversarial finding #3): a rapid second tap before the DB
     * write returns would advance twice + write twice. Advance state
     * synchronously here so the second tap's `as? Ready` cast either fires
     * on the post-advance state (Ready with currentIndex+1, fine — that's
     * just advancing the new card) or on Done (no-op). Then launch the
     * DB write asynchronously.
     */
    fun markReviewedAndAdvance() {
        val current = _state.value as? ReviewUiState.Ready ?: return
        val ann = current.currentAnnotation ?: return
        advance(current)
        viewModelScope.launch {
            reviewRepository.markReviewed(ann.id)
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
