package com.straysouth.lectern.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.straysouth.lectern.R
import com.straysouth.lectern.ui.theme.LecternTheme

class PdfReaderFragment : Fragment() {

    private val viewModel: PdfReaderViewModel by viewModels()

    companion object {
        const val ARG_BOOK_ID = "book_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookId = arguments?.getString(ARG_BOOK_ID) ?: return
        viewModel.load(bookId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LecternTheme {
                val state by viewModel.state.collectAsState()
                val bitmap by viewModel.pageBitmap.collectAsState()
                val currentPage by viewModel.currentPage.collectAsState()
                PdfReaderContent(
                    state = state,
                    bitmap = bitmap,
                    currentPage = currentPage,
                    onBack = { activity?.onBackPressedDispatcher?.onBackPressed() },
                    onNextPage = viewModel::nextPage,
                    onPrevPage = viewModel::prevPage,
                )
            }
        }
    }
}

@Composable
private fun PdfReaderContent(
    state: PdfReaderViewModel.State,
    bitmap: android.graphics.Bitmap?,
    currentPage: Int,
    onBack: () -> Unit,
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
) {
    when (state) {
        PdfReaderViewModel.State.Loading -> PdfLoadingOverlay()
        is PdfReaderViewModel.State.Error -> PdfErrorOverlay(message = state.message, onBack = onBack)
        is PdfReaderViewModel.State.Ready -> PdfPageView(
            bitmap = bitmap,
            currentPage = currentPage,
            pageCount = state.pageCount,
            onNextPage = onNextPage,
            onPrevPage = onPrevPage,
        )
    }
}

@Composable
private fun PdfLoadingOverlay() {
    val cd = stringResource(R.string.cd_pdf_loading)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PdfErrorOverlay(message: String, onBack: () -> Unit) {
    val cd = stringResource(R.string.cd_pdf_error)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.btn_back_to_library))
            }
        }
    }
}

@Composable
private fun PdfPageView(
    bitmap: android.graphics.Bitmap?,
    currentPage: Int,
    pageCount: Int,
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
) {
    // Swipe left → next page, swipe right → previous page.
    // Drag threshold of 50px prevents accidental triggers on scroll intent.
    var dragTotal by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { _, delta -> dragTotal += delta },
                    onDragEnd = {
                        if (dragTotal < -50f) onNextPage()
                        else if (dragTotal > 50f) onPrevPage()
                        dragTotal = 0f
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.label_page_of, currentPage + 1, pageCount),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
            )
        }
        // Page indicator — bottom centre, unobtrusive.
        Text(
            text = stringResource(R.string.label_page_of, currentPage + 1, pageCount),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}
