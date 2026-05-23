package com.straysouth.lectern.data.repository

data class RsvpPrefs(
    val wpm: Int = DEFAULT_WPM,
    val pauseOnPunctuation: Boolean = true,
) {
    companion object {
        const val MIN_WPM = 100
        const val MAX_WPM = 800
        const val DEFAULT_WPM = 300
        const val COMMA_MULTIPLIER = 1.5
        const val SENTENCE_MULTIPLIER = 2.0
        const val PARAGRAPH_MULTIPLIER = 3.0
    }
}
