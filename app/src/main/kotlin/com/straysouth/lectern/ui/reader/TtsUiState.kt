package com.straysouth.lectern.ui.reader

import org.readium.r2.shared.publication.Locator

sealed class TtsUiState {
    object Idle : TtsUiState()
    data class Active(
        val isPlaying: Boolean,
        // Null until the first word-level Location is emitted by TtsNavigator
        val tokenLocator: Locator?,
    ) : TtsUiState()
}
