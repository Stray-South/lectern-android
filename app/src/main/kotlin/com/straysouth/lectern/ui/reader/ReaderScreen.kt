package com.straysouth.lectern.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.os.bundleOf
import androidx.fragment.compose.AndroidFragment

@Composable
fun ReaderScreen(
    bookId: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Reader" },
    ) {
        // fillMaxSize is mandatory — zero-size container causes blank WebView (Discussion #513)
        AndroidFragment<EpubReaderFragment>(
            modifier = Modifier.fillMaxSize(),
            arguments = bundleOf(EpubReaderFragment.ARG_BOOK_ID to bookId),
        )
        // TTS controls, Focus Band overlays added here in Sprint 3
    }
}
