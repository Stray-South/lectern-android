package com.straysouth.lectern.ui.review

import com.straysouth.lectern.data.db.Annotation

sealed class ReviewUiState {
    object Loading : ReviewUiState()
    data class Ready(
        val queue: List<Annotation>,
        val currentIndex: Int,
    ) : ReviewUiState() {
        val currentAnnotation: Annotation? get() = queue.getOrNull(currentIndex)
        val totalRemaining: Int get() = (queue.size - currentIndex).coerceAtLeast(0)
    }
    object Empty : ReviewUiState()
    object Done : ReviewUiState()
}
