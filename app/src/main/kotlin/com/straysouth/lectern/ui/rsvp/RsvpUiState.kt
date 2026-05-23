package com.straysouth.lectern.ui.rsvp

sealed class RsvpUiState {
    object Loading : RsvpUiState()
    data class Ready(
        val words: List<String>,
        val currentIndex: Int,
        val isPlaying: Boolean,
    ) : RsvpUiState() {
        val currentWord: String? get() = words.getOrNull(currentIndex)
        val totalWords: Int get() = words.size
    }
    data class Error(val message: String) : RsvpUiState()
    object Done : RsvpUiState()
}
