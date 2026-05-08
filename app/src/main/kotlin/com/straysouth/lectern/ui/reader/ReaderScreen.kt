package com.straysouth.lectern.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.os.bundleOf
import androidx.fragment.compose.AndroidFragment
import com.straysouth.lectern.R

@Composable
fun ReaderScreen(
    bookId: String,
    format: String,
    modifier: Modifier = Modifier,
) {
    val cdReader = stringResource(R.string.cd_reader)
    Box(
        modifier = modifier
            .fillMaxSize()
            // mergeDescendants = false (default) — TalkBack must traverse into the
            // Fragment's WebView / Compose accessibility tree.
            .semantics(mergeDescendants = false) { contentDescription = cdReader },
    ) {
        if (format == "PDF") {
            // fillMaxSize is mandatory — zero-size container causes blank rendering.
            AndroidFragment<PdfReaderFragment>(
                modifier = Modifier.fillMaxSize(),
                arguments = bundleOf(PdfReaderFragment.ARG_BOOK_ID to bookId),
            )
        } else {
            // fillMaxSize is mandatory — zero-size container causes blank WebView (Discussion #513).
            // AndroidFragment removal handled by fragment-compose DisposableEffect.
            AndroidFragment<EpubReaderFragment>(
                modifier = Modifier.fillMaxSize(),
                arguments = bundleOf(EpubReaderFragment.ARG_BOOK_ID to bookId),
            )
        }
    }
}
