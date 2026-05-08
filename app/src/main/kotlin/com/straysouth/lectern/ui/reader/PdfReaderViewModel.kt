package com.straysouth.lectern.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.ReadingProgress
import com.straysouth.lectern.data.repository.PdfPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfReaderViewModel(application: Application) : AndroidViewModel(application) {

    sealed class State {
        object Loading : State()
        data class Ready(val pageCount: Int) : State()
        data class Error(val message: String) : State()
    }

    private val pdfPageRepository = PdfPageRepository(application)
    private val bookDao = AppDatabase.getInstance(application).bookDao()
    private val readingProgressDao = AppDatabase.getInstance(application).readingProgressDao()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    private val _pageBitmap = MutableStateFlow<Bitmap?>(null)
    val pageBitmap: StateFlow<Bitmap?> = _pageBitmap

    // PdfRenderer is not thread-safe — all PdfRenderer calls serialised on one IO thread.
    private val ioSerial = Dispatchers.IO.limitedParallelism(1)

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var bookId: String? = null
    private var pageCount: Int = 0

    fun load(id: String) {
        bookId = id
        viewModelScope.launch {
            // DAO and DataStore calls outside withContext — both dispatch internally (RULES.md).
            val filePath = bookDao.getById(id)?.filePath
            if (filePath == null) {
                _state.value = State.Error("Book not found")
                return@launch
            }
            val savedPage = pdfPageRepository.get(id)

            // Only PdfRenderer I/O runs on ioSerial.
            withContext(ioSerial) {
                runCatching {
                    val descriptor = getApplication<Application>().contentResolver
                        .openFileDescriptor(Uri.parse(filePath), "r")
                        ?: error("Cannot open file descriptor: $filePath")
                    pfd = descriptor
                    val r = PdfRenderer(descriptor)
                    renderer = r
                    pageCount = r.pageCount
                    val clamped = savedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    _currentPage.value = clamped
                    _state.value = State.Ready(pageCount)
                    renderPage(clamped)
                }.onFailure { e ->
                    Log.e("PdfReaderViewModel", "Cannot open PDF", e)
                    _state.value = State.Error("Unable to open PDF")
                }
            }
        }
    }

    fun nextPage() {
        val next = (_currentPage.value + 1).coerceAtMost((pageCount - 1).coerceAtLeast(0))
        if (next == _currentPage.value) return
        _currentPage.value = next
        viewModelScope.launch {
            // Render on ioSerial; save progress after — Room/DataStore are main-safe.
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

    // Must run on ioSerial — PdfRenderer.openPage throws if called concurrently.
    // PdfRenderer.Page must be closed before the next openPage call.
    private fun renderPage(index: Int) {
        val r = renderer ?: return
        val page = r.openPage(index)
        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        _pageBitmap.value = bmp
    }

    // Room and DataStore are both main-safe suspend functions — no withContext needed.
    private suspend fun saveProgress(index: Int) {
        val id = bookId ?: return
        val progression = if (pageCount > 1) index.toDouble() / (pageCount - 1) else 0.0
        pdfPageRepository.save(id, index)
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
        // Post cleanup to ioSerial so it runs AFTER any in-flight renderPage completes.
        // ioSerial is serial (limitedParallelism(1)) — ordering is guaranteed.
        // A fresh CoroutineScope is used because viewModelScope is already cancelled here.
        CoroutineScope(ioSerial).launch {
            renderer?.close()
            renderer = null
            pfd?.close()
            pfd = null
        }
    }
}
