package com.straysouth.lectern

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.straysouth.lectern.ui.library.LibraryScreen
import com.straysouth.lectern.ui.library.LibraryViewModel
import com.straysouth.lectern.ui.reader.ReaderScreen
import com.straysouth.lectern.ui.theme.LecternTheme

class MainActivity : AppCompatActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LecternTheme {
                val cdApp = stringResource(R.string.cd_app)
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = cdApp },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Process death resets to null — user lands on library and resumes
                    // from DataStore-persisted locator on next open. Intentional.
                    var currentBookId by rememberSaveable { mutableStateOf<String?>(null) }
                    var currentBookFormat by rememberSaveable { mutableStateOf<String?>(null) }

                    // Navigate back to library if the currently-open book is deleted.
                    // acknowledgeDeletedBook() clears the replay cache so a re-imported
                    // file with the same URI (and thus the same UUID) does not
                    // spuriously close the newly-opened reader.
                    LaunchedEffect(Unit) {
                        libraryViewModel.deletedBookId.collect { deletedId ->
                            if (currentBookId == deletedId) {
                                currentBookId = null
                                currentBookFormat = null
                            }
                            libraryViewModel.acknowledgeDeletedBook()
                        }
                    }

                    BackHandler(enabled = currentBookId != null) {
                        currentBookId = null
                        currentBookFormat = null
                    }

                    val bookId = currentBookId
                    if (bookId == null) {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onBookSelected = { book ->
                                currentBookId = book.id
                                currentBookFormat = book.format
                            },
                        )
                    } else {
                        ReaderScreen(bookId = bookId, format = currentBookFormat ?: "EPUB")
                    }
                }
            }
        }
    }
}
