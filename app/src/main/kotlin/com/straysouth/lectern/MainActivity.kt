package com.straysouth.lectern

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import com.straysouth.lectern.ui.library.LibraryScreen
import com.straysouth.lectern.ui.library.LibraryViewModel
import com.straysouth.lectern.ui.reader.EpubReaderFragment
import com.straysouth.lectern.ui.theme.LecternTheme

class MainActivity : AppCompatActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LecternTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Lectern" },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onBookSelected = { book ->
                            openReader(book.id, book.filePath ?: return@LibraryScreen)
                        },
                    )
                }
            }
        }
    }

    private fun openReader(bookId: String, fileUri: String) {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, EpubReaderFragment.newInstance(bookId, fileUri))
            .addToBackStack(null)
            .commit()
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    LecternTheme { }
}
