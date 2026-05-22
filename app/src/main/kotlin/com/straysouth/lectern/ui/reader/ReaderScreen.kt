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
import com.straysouth.lectern.ui.window.SecureWindow

@Composable
fun ReaderScreen(
    bookId: String,
    format: String,
    modifier: Modifier = Modifier,
) {
    val cdReader = stringResource(R.string.cd_reader)
    // V2.2 — claim FLAG_SECURE while an EPUB reader is in composition. Reader content
    // includes user-authored highlights (ADR-AND-T / V2.2.1), which fire ADR-AND-R
    // V2 reconsideration trigger 1 (private user annotation distinct from third-
    // party EPUB body text). The reference-counted controller (PR #12) keeps the
    // flag set as long as any sensitive surface is active; navigating BACK to the
    // library releases it via DisposableEffect onDispose.
    //
    // Allowlist (not exclusion list): only formats that actually support
    // annotations opt into FLAG_SECURE. A future DJVU/MOBI/etc. format
    // unknown to this check defaults to NO secure-window — which is the
    // safer side because such a format doesn't have annotation support yet
    // (its annotations don't exist to threaten). Expand here when a new
    // format adds annotation support.
    if (format == "EPUB") {
        SecureWindow()
    }
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
