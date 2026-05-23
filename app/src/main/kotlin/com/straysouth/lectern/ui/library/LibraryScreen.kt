package com.straysouth.lectern.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.straysouth.lectern.R
import com.straysouth.lectern.data.db.Book
import com.straysouth.lectern.ui.library.LibraryViewModel.SortOrder
import java.io.File

private val IMPORT_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "application/pdf",
    "application/vnd.comicbook+zip",
    "application/vnd.comicbook-rar",
)

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBookSelected: (Book) -> Unit,
    onRsvpRequested: (com.straysouth.lectern.ui.rsvp.RsvpSource) -> Unit = {},
    onReviewRequested: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val books by viewModel.books.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val progressByBookId by viewModel.progressByBookId.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(importError) {
        val msg = importError ?: return@LaunchedEffect
        // Indefinite + withDismissAction: error stays visible until the user taps ×.
        // Short (4 s) was insufficient for AuDHD readers who may miss transient messages.
        // finally: clearImportError() runs even if LaunchedEffect is cancelled mid-suspension
        // (e.g. a second import error arrives and restarts the effect). Without finally,
        // cancellation at the showSnackbar suspension point would leave importError non-null,
        // and the new LaunchedEffect would receive only the new error value — the old ViewModel
        // state would be cleared when the new Snackbar is dismissed. Using finally ensures
        // cleanup is always paired with the show attempt regardless of cancellation path.
        try {
            snackbarHostState.showSnackbar(
                message = msg,
                withDismissAction = true,
                duration = androidx.compose.material3.SnackbarDuration.Indefinite,
            )
        } finally {
            viewModel.clearImportError()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBook(it) } }

    val cdLibrary = stringResource(R.string.cd_library)

    // V2.4 — RSVP launchers. SAF .txt picker scopes URIs to content:// (no file://
    // traversal possible per ADR-AND-X §Storage). Clipboard text is read via the
    // standard ClipboardManager and snapshotted into RsvpSource.Clipboard — never
    // logged, never persisted.
    val txtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // V2.4 fix: SAF's OpenDocument is supposed to return content:// only,
        // but malicious document providers and pre-API-26 quirks can leak a
        // file:// URI through this callback. Hard-gate the scheme before
        // constructing RsvpSource.TxtUri (ADR-AND-X §Storage).
        uri?.takeIf { it.scheme == "content" }?.let {
            onRsvpRequested(com.straysouth.lectern.ui.rsvp.RsvpSource.TxtUri(it))
        }
    }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val emptyClipboardMsg = stringResource(R.string.rsvp_clipboard_empty)
    val scope = rememberCoroutineScope()
    val onClipboardRsvp: () -> Unit = {
        val text = clipboardManager.getText()?.text
        if (text.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = emptyClipboardMsg,
                    withDismissAction = true,
                    duration = androidx.compose.material3.SnackbarDuration.Indefinite,
                )
            }
        } else {
            onRsvpRequested(com.straysouth.lectern.ui.rsvp.RsvpSource.Clipboard(text))
        }
    }

    Scaffold(
        modifier = modifier.semantics { contentDescription = cdLibrary },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        floatingActionButton = {
            LibraryFab(isImporting = isImporting, onImport = { launcher.launch(IMPORT_MIME_TYPES) })
        },
    ) { contentPadding ->
        LibraryContent(
            books = books,
            progressByBookId = progressByBookId,
            sortOrder = sortOrder,
            onBookSelected = onBookSelected,
            onBookLongPressed = { viewModel.deleteBook(it) },
            onToggleSortOrder = { viewModel.toggleSortOrder() },
            onClipboardRsvp = onClipboardRsvp,
            onTxtRsvp = { txtLauncher.launch(arrayOf("text/plain")) },
            onReviewRequested = onReviewRequested,
            modifier = Modifier.padding(contentPadding),
        )
    }
}

@Composable
private fun LibraryFab(isImporting: Boolean, onImport: () -> Unit) {
    FloatingActionButton(onClick = { if (!isImporting) onImport() }) {
        if (isImporting) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.cd_import_book),
            )
        }
    }
}

@Composable
private fun LibraryContent(
    books: List<Book>,
    progressByBookId: Map<String, Double>,
    sortOrder: SortOrder,
    onBookSelected: (Book) -> Unit,
    onBookLongPressed: (Book) -> Unit,
    onToggleSortOrder: () -> Unit,
    onClipboardRsvp: () -> Unit,
    onTxtRsvp: () -> Unit,
    onReviewRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bookPendingDelete by remember { mutableStateOf<Book?>(null) }

    bookPendingDelete?.let { book ->
        DeleteBookDialog(
            title = book.title ?: stringResource(R.string.book_title_untitled),
            onConfirm = {
                onBookLongPressed(book)
                bookPendingDelete = null
            },
            onDismiss = { bookPendingDelete = null },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SortToggleRow(
            sortOrder = sortOrder,
            onToggle = onToggleSortOrder,
            onClipboardRsvp = onClipboardRsvp,
            onTxtRsvp = onTxtRsvp,
            onReviewRequested = onReviewRequested,
        )
        if (books.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.no_books_yet),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(books, key = { it.id }) { book ->
                    val title = book.title ?: stringResource(R.string.book_title_untitled)
                    BookRow(
                        title = title,
                        coverPath = book.coverPath,
                        progress = progressByBookId[book.id],
                        onClick = { onBookSelected(book) },
                        onLongClick = { bookPendingDelete = book },
                    )
                }
            }
        }
    }
}

@Composable
private fun SortToggleRow(
    sortOrder: SortOrder,
    onToggle: () -> Unit,
    onClipboardRsvp: () -> Unit,
    onTxtRsvp: () -> Unit,
    onReviewRequested: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        val reviewLabel = stringResource(R.string.review_library_action)
        TextButton(
            onClick = onReviewRequested,
            modifier = Modifier.semantics { contentDescription = reviewLabel },
        ) {
            Text(reviewLabel, style = MaterialTheme.typography.labelMedium)
        }
        val clipboardLabel = stringResource(R.string.rsvp_clipboard_action)
        TextButton(
            onClick = onClipboardRsvp,
            modifier = Modifier.semantics { contentDescription = clipboardLabel },
        ) {
            Text(clipboardLabel, style = MaterialTheme.typography.labelMedium)
        }
        val txtLabel = stringResource(R.string.rsvp_txt_action)
        TextButton(
            onClick = onTxtRsvp,
            modifier = Modifier.semantics { contentDescription = txtLabel },
        ) {
            Text(txtLabel, style = MaterialTheme.typography.labelMedium)
        }
        val label = stringResource(
            if (sortOrder == SortOrder.ADDED) R.string.sort_added else R.string.sort_last_opened,
        )
        val cdSortToggle = stringResource(R.string.cd_sort_toggle)
        TextButton(
            onClick = onToggle,
            modifier = Modifier.semantics { contentDescription = cdSortToggle },
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DeleteBookDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_book_title)) },
        text = { Text(stringResource(R.string.delete_book_message, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_book_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun BookRow(
    title: String,
    coverPath: String?,
    progress: Double?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverPath?.let { File(it) },
            contentDescription = null,
            placeholder = painterResource(R.drawable.cover_placeholder),
            error = painterResource(R.drawable.cover_placeholder),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (progress != null) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
