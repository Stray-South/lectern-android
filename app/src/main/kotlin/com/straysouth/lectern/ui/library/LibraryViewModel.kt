package com.straysouth.lectern.ui.library

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.Book
import com.straysouth.lectern.data.repository.PublicationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val pubRepository = PublicationRepository(application)
    private val bookDao = AppDatabase.getInstance(application).bookDao()
    private val readingProgressDao = AppDatabase.getInstance(application).readingProgressDao()

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
            val id = UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString()

            if (format == FORMAT_PDF) {
                val title = uri.lastPathSegment
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: "Untitled"
                bookDao.upsert(
                    Book(
                        id = id,
                        title = title,
                        filePath = uri.toString(),
                        coverPath = null,
                        addedAt = System.currentTimeMillis(),
                        lastOpenedAt = null,
                        format = FORMAT_PDF,
                    ),
                )
            } else {
                val result = pubRepository.open(uri)
                if (result.isFailure) {
                    Log.e("LibraryViewModel", "Cannot open publication: $uri", result.exceptionOrNull())
                    _isImporting.value = false
                    return@launch
                }
                val pub = result.getOrThrow()
                val title = pub.metadata.title?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment
                    ?: "Untitled"
                pub.close()
                bookDao.upsert(
                    Book(
                        id = id,
                        title = title,
                        filePath = uri.toString(),
                        coverPath = null,
                        addedAt = System.currentTimeMillis(),
                        lastOpenedAt = null,
                        format = FORMAT_EPUB,
                    ),
                )
            }
            _isImporting.value = false
        }
    }

    companion object {
        const val FORMAT_EPUB = "EPUB"
        const val FORMAT_PDF = "PDF"

        fun detectFormat(uri: Uri, contentResolver: ContentResolver): String {
            val mime = contentResolver.getType(uri)
            val ext = uri.lastPathSegment?.substringAfterLast('.')?.lowercase()
            return if (mime == "application/pdf" || ext == "pdf") FORMAT_PDF else FORMAT_EPUB
        }
    }
}
