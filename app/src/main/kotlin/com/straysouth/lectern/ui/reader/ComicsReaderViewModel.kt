package com.straysouth.lectern.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.ReadingProgress
import com.straysouth.lectern.data.repository.ComicsPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import java.io.File

class ComicsReaderViewModel(application: Application) : AndroidViewModel(application) {

    sealed class State {
        object Loading : State()
        data class Ready(val pageCount: Int) : State()
        data class Error(val message: String) : State()
    }

    private val comicsPageRepository = ComicsPageRepository(application)
    private val bookDao = AppDatabase.getInstance(application).bookDao()
    private val readingProgressDao = AppDatabase.getInstance(application).readingProgressDao()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    private val _pageBitmap = MutableStateFlow<Bitmap?>(null)
    val pageBitmap: StateFlow<Bitmap?> = _pageBitmap

    // ZipFile / Archive are not thread-safe — all I/O serialised on one IO thread.
    private val ioSerial = Dispatchers.IO.limitedParallelism(1)

    // Exactly one of these is non-null at runtime, depending on format.
    private var zipFile: ZipFile? = null
    private var rarArchive: Archive? = null

    // Sorted image entry names for stable page order.
    private var pageEntries: List<String> = emptyList()

    private var bookId: String? = null
    private var pageCount: Int = 0

    fun load(id: String) {
        bookId = id
        viewModelScope.launch {
            // DAO call outside withContext — Room dispatches internally (RULES.md).
            val book = bookDao.getById(id)
            if (book == null) {
                _state.value = State.Error("Book not found")
                return@launch
            }
            val savedPage = comicsPageRepository.get(id)

            withContext(ioSerial) {
                runCatching {
                    openArchive(book.filePath ?: error("Missing file path"), book.format)
                    pageCount = pageEntries.size
                    if (pageCount == 0) error("No images found in archive")
                    val clamped = savedPage.coerceIn(0, pageCount - 1)
                    _currentPage.value = clamped
                    _state.value = State.Ready(pageCount)
                    renderPage(clamped)
                }.onFailure { e ->
                    Log.e("ComicsReaderViewModel", "Cannot open comic", e)
                    val msg = if (e is UnsupportedRarV5Exception) {
                        "This file uses RAR5 format and cannot be opened. Convert it to CBZ to read."
                    } else {
                        "Unable to open comic"
                    }
                    _state.value = State.Error(msg)
                }
            }
        }
    }

    fun nextPage() {
        val next = (_currentPage.value + 1).coerceAtMost((pageCount - 1).coerceAtLeast(0))
        if (next == _currentPage.value) return
        _currentPage.value = next
        viewModelScope.launch {
            withContext(ioSerial) { renderPage(next) }
            saveProgress(next)
        }
    }

    fun prevPage() {
        val prev = (_currentPage.value - 1).coerceAtLeast(0)
        if (prev == _currentPage.value) return
        _currentPage.value = prev
        viewModelScope.launch {
            withContext(ioSerial) { renderPage(prev) }
            saveProgress(prev)
        }
    }

    // Opens the archive and populates pageEntries with sorted image filenames.
    // Must run on ioSerial.
    private fun openArchive(filePath: String, format: String) {
        val ctx = getApplication<Application>()
        if (format == "CBR") {
            // junrar requires a java.io.File — copy to cache if URI is a content URI.
            val file = uriToFile(filePath, ctx)
            val archive = Archive(file)
            rarArchive = archive
            pageEntries = archive.fileHeaders
                .filter { !it.isDirectory && isImageFile(it.fileName) }
                .sortedWith(compareBy(naturalOrder()) { it.fileName })
                .map { it.fileName }
        } else {
            val zip = ZipFile(uriToFile(filePath, ctx))
            zipFile = zip
            pageEntries = zip.fileHeaders
                .filter { !it.isDirectory && isImageFile(it.fileName) }
                .sortedWith(compareBy(naturalOrder()) { it.fileName })
                .map { it.fileName }
        }
    }

    // Must run on ioSerial — shared archive handle is not thread-safe.
    private fun renderPage(index: Int) {
        val entry = pageEntries.getOrNull(index) ?: return
        val bmp = zipFile?.let { zip ->
            zip.getInputStream(zip.getFileHeader(entry)).use { BitmapFactory.decodeStream(it) }
        } ?: rarArchive?.let { rar ->
            val header = rar.fileHeaders.first { it.fileName == entry }
            rar.getInputStream(header).use { BitmapFactory.decodeStream(it) }
        }
        if (bmp != null) _pageBitmap.value = bmp
    }

    // Room and DataStore are main-safe suspend functions — no withContext needed (RULES.md).
    private suspend fun saveProgress(index: Int) {
        val id = bookId ?: return
        val progression = if (pageCount > 1) index.toDouble() / (pageCount - 1) else 0.0
        comicsPageRepository.save(id, index)
        readingProgressDao.upsert(
            ReadingProgress(
                id = id,
                bookId = id,
                locatorJson = null,
                totalProgression = progression,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override fun onCleared() {
        super.onCleared()
        // Post cleanup to ioSerial — guarantees it runs AFTER any in-flight renderPage.
        CoroutineScope(ioSerial).launch {
            zipFile?.close()
            zipFile = null
            rarArchive?.close()
            rarArchive = null
        }
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        private fun isImageFile(name: String): Boolean =
            name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

        // Converts a content:// URI to a java.io.File by copying to cache.
        // zip4j and junrar both require a File, not an InputStream.
        private fun uriToFile(filePath: String, ctx: android.content.Context): File {
            val uri = Uri.parse(filePath)
            if (uri.scheme == "file") return File(uri.path ?: filePath)
            val name = uri.lastPathSegment ?: "comic"
            val cache = File(ctx.cacheDir, name)
            if (!cache.exists()) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    cache.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return cache
        }
    }
}
