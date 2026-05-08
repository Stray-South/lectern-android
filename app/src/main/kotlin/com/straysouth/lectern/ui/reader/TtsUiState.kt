package com.straysouth.lectern.ui.reader

import org.readium.r2.shared.publication.Locator

sealed class TtsUiState {
    object Idle : TtsUiState()
    data class Active(
        val isPlaying: Boolean,
        // Null until the first word-level Location is emitted by TtsNavigator
        val tokenLocator: Locator?,
        // Sentence-level locator; drives Focus Band decoration
        val utteranceLocator: Locator?,
    ) : TtsUiState()
    /**
     * No usable TTS engine found (e.g. Samsung One UI 7+, device with no TTS installed).
     * The play button must remain visible but show a message explaining why TTS is unavailable,
     * rather than silently doing nothing.
     */
    object EngineUnavailable : TtsUiState()
}
