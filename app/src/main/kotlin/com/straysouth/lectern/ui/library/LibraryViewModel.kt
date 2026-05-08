package com.straysouth.lectern.ui.library

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
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
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val pubRepository = PublicationRepository(application)
    private val db = AppDatabase.getInstance(application)
    private val bookDao = db.bookDao()
    private val readingProgressDao = db.readingProgressDao()
    private val locatorRepository = LocatorRepository(application)
    private val pdfPageRepository = PdfPageRepository(application)
    private val comicsPageRepository = ComicsPageRepository(application)
    private val anchorRepository = AnchorRepository(application)

    enum class SortOrder { ADDED, LAST_OPENED }

    private val _sortOrder = MutableStateFlow(SortOrder.ADDED)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    fun toggleSortOrder() {
        _sortOrder.value = if (_sortOrder.value == SortOrder.ADDED) SortOrder.LAST_OPENED else SortOrder.ADDED
    }

    val books: StateFlow<List<Book>> = _sortOrder
        .flatMapLatest { order ->
            if (order == SortOrder.ADDED) bookDao.observeAll()
            else bookDao.observeAllByLastOpened()
        }
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

    // Updates lastOpenedAt so last-opened sort reflects when books were read.
    // EpubReaderViewModel also calls updateLastOpened on open — the duplicate
    // call from MainActivity is idempotent (same timestamp within milliseconds).
    fun recordOpened(id: String) {
        viewModelScope.launch {
            bookDao.updateLastOpened(id, System.currentTimeMillis())
        }
    }

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    // Emits the id of a book that was just deleted. Collected by MainActivity to
    // navigate back to the library if the deleted book is currently open.
    // replay=1 ensures the emission is not lost if the Activity recreates (rotation)
    // between the delete coroutine firing and the new LaunchedEffect subscribing.
    // Call acknowledgeDeletedBook() after handling the emission so the replayed id
    // is cleared — prevents spurious navigation if the same file is re-imported.
    private val _deletedBookId = MutableSharedFlow<String>(replay = 1)
    val deletedBookId: SharedFlow<String> = _deletedBookId.asSharedFlow()

    fun acknowledgeDeletedBook() {
        _deletedBookId.resetReplayCache()
    }

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

            val id = bookCacheId(uri.toString())

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
                // CBZ/CBR cache file and EPUB cover — all disk ops on Dispatchers.IO.
                withContext(Dispatchers.IO) {
                    val cacheFile = File(
                        getApplication<Application>().cacheDir,
                        bookCacheId(filePath),
                    )
                    if (cacheFile.exists()) cacheFile.delete()
                    book.coverPath?.let { File(it).delete() }
                }
            }
            _deletedBookId.emit(id)
        }
    }

    // Imports via Readium to extract publication title and cover thumbnail.
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
        // Extract cover before closing — pub.cover() returns null after pub.close().
        val coverPath = extractAndSaveCover(pub, id)
        pub.close()
        bookDao.upsert(book(id, uri, title, FORMAT_EPUB, coverPath))
    }

    // Saves the publication cover bitmap to filesDir as cover_$id.png.
    // Returns the absolute path on success, null if no cover or on error.
    private suspend fun extractAndSaveCover(pub: Publication, id: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = pub.cover() ?: return@runCatching null
                val file = File(getApplication<Application>().filesDir, "cover_$id.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                file.absolutePath
            }.getOrNull()
        }

    // Imports PDF/CBZ/CBR using filename as title (no Readium call; no cover).
    private suspend fun importByFilename(uri: Uri, id: String, format: String) {
        val title = uri.lastPathSegment
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "Untitled"
        bookDao.upsert(book(id, uri, title, format, null))
    }

    private fun book(id: String, uri: Uri, title: String, format: String, coverPath: String?) = Book(
        id = id,
        title = title,
        filePath = uri.toString(),
        coverPath = coverPath,
        addedAt = System.currentTimeMillis(),
        lastOpenedAt = null,
        format = format,
    )

    companion object {
        const val FORMAT_EPUB = "EPUB"
        const val FORMAT_PDF = "PDF"
        const val FORMAT_CBZ = "CBZ"
        const val FORMAT_CBR = "CBR"

        // Stable cache-file name derived from a URI or file-path string.
        // Explicit UTF-8 encoding avoids platform-default-charset drift.
        fun bookCacheId(key: String): String =
            UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()

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
