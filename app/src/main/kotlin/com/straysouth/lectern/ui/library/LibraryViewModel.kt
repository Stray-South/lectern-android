package com.straysouth.lectern.ui.library

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.R
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.Book
import com.straysouth.lectern.data.repository.AnchorRepository
import com.straysouth.lectern.data.repository.ComicsPageRepository
import com.straysouth.lectern.data.repository.LocatorRepository
import com.straysouth.lectern.data.repository.PdfPageRepository
import com.straysouth.lectern.data.repository.PublicationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val pubRepository = PublicationRepository(application)
    private val bookDao = AppDatabase.getInstance(application).bookDao()
    private val readingProgressDao = AppDatabase.getInstance(application).readingProgressDao()
    private val locatorRepository = LocatorRepository(application)
    private val pdfPageRepository = PdfPageRepository(application)
    private val comicsPageRepository = ComicsPageRepository(application)
    private val anchorRepository = AnchorRepository(application)

    val books: StateFlow<List<Book>> =
        bookDao.observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    val progressByBookId: StateFlow<Map<String, Double>> =
        readingProgressDao.observeAll()
            .map { rows ->
                rows.mapNotNull { r ->
                    val id = r.bookId ?: return@mapNotNull null
                    val p = r.totalProgression ?: return@mapNotNull null
                    id to p
                }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    // Emits the id of a book that was just deleted. Collected by MainActivity to
    // navigate back to the library if the deleted book is currently open.
    // replay=1 ensures the emission is not lost if the Activity recreates (rotation)
    // between the delete coroutine firing and the new LaunchedEffect subscribing.
    private val _deletedBookId = MutableSharedFlow<String>(replay = 1)
    val deletedBookId: SharedFlow<String> = _deletedBookId.asSharedFlow()

    fun clearImportError() {
        _importError.value = null
    }

    fun importBook(uri: Uri) {
        if (_isImporting.value) return
        _isImporting.value = true
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.e("LibraryViewModel", "Cannot persist URI permission: $uri", e)
                _isImporting.value = false
                return@launch
            }

            val format = detectFormat(uri, getApplication<Application>().contentResolver)
            if (format == null) {
                Log.e("LibraryViewModel", "Unsupported format: $uri")
                _importError.value = getApplication<Application>().getString(R.string.import_error_unsupported_format)
                _isImporting.value = false
                return@launch
            }

            val id = UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString()

            try {
                if (format == FORMAT_EPUB) {
                    importEpub(uri, id)
                } else {
                    importByFilename(uri, id, format)
                }
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            val id = book.id
            val filePath = book.filePath
            // Ordering: Room rows → DataStore keys → release URI permission → cache file.
            // This minimises the window where a still-open reader ViewModel could observe
            // a missing Row while its file handles remain valid.
            bookDao.deleteById(id)
            readingProgressDao.deleteByBookId(id)
            locatorRepository.remove(id)
            pdfPageRepository.remove(id)
            comicsPageRepository.remove(id)
            anchorRepository.clear(id)
            if (filePath != null) {
                val uri = Uri.parse(filePath)
                runCatching {
                    getApplication<Application>().contentResolver
                        .releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // CBZ/CBR cache file — format-agnostic UUID key; no-op for EPUB/PDF.
                // File.delete() is dispatched to IO — disk operations must not run on main.
                withContext(Dispatchers.IO) {
                    val cacheFile = File(
                        getApplication<Application>().cacheDir,
                        UUID.nameUUIDFromBytes(filePath.toByteArray()).toString(),
                    )
                    if (cacheFile.exists()) cacheFile.delete()
                }
            }
            _deletedBookId.emit(id)
        }
    }

    // Imports via Readium to extract publication title.
    private suspend fun importEpub(uri: Uri, id: String) {
        val result = pubRepository.open(uri)
        if (result.isFailure) {
            Log.e("LibraryViewModel", "Cannot open publication: $uri", result.exceptionOrNull())
            _importError.value = getApplication<Application>().getString(R.string.import_error_epub_open)
            return
        }
        val pub = result.getOrThrow()
        val title = pub.metadata.title?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "Untitled"
        pub.close()
        bookDao.upsert(book(id, uri, title, FORMAT_EPUB))
    }

    // Imports PDF/CBZ/CBR using filename as title (no Readium call).
    private suspend fun importByFilename(uri: Uri, id: String, format: String) {
        val title = uri.lastPathSegment
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "Untitled"
        bookDao.upsert(book(id, uri, title, format))
    }

    private fun book(id: String, uri: Uri, title: String, format: String) = Book(
        id = id,
        title = title,
        filePath = uri.toString(),
        coverPath = null,
        addedAt = System.currentTimeMillis(),
        lastOpenedAt = null,
        format = format,
    )

    companion object {
        const val FORMAT_EPUB = "EPUB"
        const val FORMAT_PDF = "PDF"
        const val FORMAT_CBZ = "CBZ"
        const val FORMAT_CBR = "CBR"

        // Extension is the primary signal for comics — system MIME types for CBZ/CBR
        // are not standardised and vary across file managers.
        // Returns null for unrecognised formats — callers must handle the null case.
        fun detectFormat(uri: Uri, contentResolver: ContentResolver): String? {
            val ext = uri.lastPathSegment?.substringAfterLast('.')?.lowercase()
            val mime = contentResolver.getType(uri)
            return when {
                ext == "cbz" || mime == "application/vnd.comicbook+zip" -> FORMAT_CBZ
                ext == "cbr" || mime == "application/vnd.comicbook-rar" -> FORMAT_CBR
                ext == "pdf" || mime == "application/pdf" -> FORMAT_PDF
                ext == "epub" || mime == "application/epub+zip" -> FORMAT_EPUB
                else -> null
            }
        }
    }
}
