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
            // mergeDescendants = false — TalkBack must traverse the Fragment's accessibility tree.
            .semantics(mergeDescendants = false) { contentDescription = cdReader },
    ) {
        when (format) {
            "PDF" -> AndroidFragment<PdfReaderFragment>(
                modifier = Modifier.fillMaxSize(),
                arguments = bundleOf(PdfReaderFragment.ARG_BOOK_ID to bookId),
            )
            "CBZ", "CBR" -> AndroidFragment<ComicsReaderFragment>(
                modifier = Modifier.fillMaxSize(),
                arguments = bundleOf(ComicsReaderFragment.ARG_BOOK_ID to bookId),
            )
            else -> // fillMaxSize mandatory — zero-size causes blank WebView (Discussion #513).
                AndroidFragment<EpubReaderFragment>(
                    modifier = Modifier.fillMaxSize(),
                    arguments = bundleOf(EpubReaderFragment.ARG_BOOK_ID to bookId),
                )
        }
    }
}
