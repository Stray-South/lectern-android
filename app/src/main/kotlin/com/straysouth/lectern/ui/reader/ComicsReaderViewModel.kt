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
import java.util.UUID

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

    // ZipFile is not thread-safe — all I/O serialised on one IO thread.
    // rarArchive is NOT held open; Archive is re-created per renderPage (see renderPage).
    private val ioSerial = Dispatchers.IO.limitedParallelism(1)

    private var zipFile: ZipFile? = null

    // For CBR: store the local File reference (not the Archive itself).
    // junrar Archive uses stateful forward-only reads; re-opening per renderPage is
    // the only safe way to support non-sequential page access.
    private var rarCacheFile: File? = null

    // Tracks decoded bitmaps for safe recycling. Compose may still be drawing the
    // previous bitmap when renderPage assigns a new one — never recycle at assignment
    // time. Keep at most 2 entries; recycle the oldest when the queue exceeds the cap.
    private val bitmapQueue = ArrayDeque<Bitmap>()

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
        // pageCount == 0 cannot be reached here: State.Ready is only set after
        // pageCount > 0 is verified in load(). nextPage is only reachable from
        // ComicsPageView which is only shown in State.Ready.
        val next = (_currentPage.value + 1).coerceAtMost(pageCount - 1)
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
            // Store the File reference only — Archive is opened fresh per renderPage.
            rarCacheFile = uriToFile(filePath, ctx)
        } else {
            val zip = ZipFile(uriToFile(filePath, ctx))
            zipFile = zip
            pageEntries = zip.fileHeaders
                .filter { !it.isDirectory && isImageFile(it.fileName) }
                .sortedWith(compareBy(naturalOrder()) { it.fileName })
                .map { it.fileName }
            return
        }
        // CBR: open a temporary Archive to enumerate entries, then close immediately.
        Archive(rarCacheFile).use { rar ->
            pageEntries = rar.fileHeaders
                .filter { !it.isDirectory && isImageFile(it.fileName) }
                .sortedWith(compareBy(naturalOrder()) { it.fileName })
                .map { it.fileName }
        }
    }

    // Must run on ioSerial — shared ZipFile handle is not thread-safe.
    private fun renderPage(index: Int) {
        val entry = pageEntries.getOrNull(index) ?: return
        val bmp = renderZipPage(entry) ?: renderRarPage(entry)
        if (bmp != null) {
            _pageBitmap.value = bmp
            bitmapQueue.addLast(bmp)
            // Recycle bitmaps beyond the 2-entry cap. The retained entries cover the
            // currently-displayed frame and one prior, ensuring Compose never draws a
            // recycled bitmap even during recomposition.
            if (bitmapQueue.size > 2) bitmapQueue.removeFirst().recycle()
        }
    }

    // SECURITY B.2: Two-pass decode. Pass 1 reads only the image header via inJustDecodeBounds
    // (fast, ≤ 4 KB). Pass 2 decodes with inSampleSize capping both axes to MAX_BITMAP_DIM,
    // bounding allocation to MAX_BITMAP_DIM² × 4 bytes. zip4j supports independent
    // getInputStream calls on the same FileHeader (returns a fresh ZipInputStream each time).
    private fun renderZipPage(entry: String): Bitmap? {
        val zip = zipFile ?: return null
        return zip.getFileHeader(entry)?.let { header ->
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(header).use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)
            }
            zip.getInputStream(header).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        }
    }

    // SECURITY B.2: Same two-pass approach for CBR. Archive is already re-opened per
    // renderPage call (junrar sequential-read contract); opening twice is consistent.
    // firstOrNull + ?.let avoids non-local returns inside inline lambdas (ReturnCount).
    // If the entry is absent (corrupt archive), both passes return null gracefully.
    private fun renderRarPage(entry: String): Bitmap? {
        val file = rarCacheFile ?: return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        Archive(file).use { rar ->
            rar.fileHeaders.firstOrNull { it.fileName == entry }?.let { header ->
                rar.getInputStream(header).use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            }
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)
        }
        return Archive(file).use { rar ->
            rar.fileHeaders.firstOrNull { it.fileName == entry }?.let { header ->
                rar.getInputStream(header).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            }
        }
    }

    // Computes the power-of-2 inSampleSize that reduces both axes to ≤ MAX_BITMAP_DIM.
    // Unknown dimensions (outWidth/outHeight ≤ 0 from inJustDecodeBounds on an unrecognised
    // format) fall through to sampleSize = 1, preserving existing null-bmp behaviour.
    private fun calculateInSampleSize(outWidth: Int, outHeight: Int): Int {
        if (outWidth <= 0 || outHeight <= 0) return 1
        var sampleSize = 1
        while (outWidth / sampleSize > MAX_BITMAP_DIM || outHeight / sampleSize > MAX_BITMAP_DIM) {
            sampleSize *= 2
        }
        return sampleSize
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
        // Capture the currently-displayed bitmap on the main thread before dispatching
        // cleanup. The current bitmap may still be referenced by Compose's rendering
        // pipeline — exclude it from recycling and let it be GC'd naturally once all
        // Compose references drop. Older bitmaps in the queue are safe to recycle.
        val currentBitmap = _pageBitmap.value
        // Post cleanup to ioSerial — guarantees it runs AFTER any in-flight renderPage.
        CoroutineScope(ioSerial).launch {
            try {
                zipFile?.close()
                zipFile = null
                rarCacheFile?.delete()
                rarCacheFile = null
                bitmapQueue.forEach { if (it !== currentBitmap) it.recycle() }
                bitmapQueue.clear()
            } catch (e: java.io.IOException) {
                Log.e("ComicsReaderViewModel", "Error during cleanup", e)
            }
        }
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        // SECURITY B.2: Caps decoded bitmap dimensions. Worst-case allocation:
        // 2048 × 2048 × 4 bytes = 16 MB per page — safe on minSdk 26 (heap ≥ 64 MB).
        // High-quality comic scans at 3000–4000 px get inSampleSize=2, rendering at
        // 1500–2000 px — still crisp on typical Android screens.
        private const val MAX_BITMAP_DIM = 2048

        private fun isImageFile(name: String): Boolean =
            name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

        // Converts a content:// URI to a java.io.File by copying to cache.
        // zip4j and junrar both require a File, not an InputStream.
        // Cache key is a UUID derived from the full URI string to prevent collisions
        // between different books that share the same filename.
        private fun uriToFile(filePath: String, ctx: android.content.Context): File {
            val uri = Uri.parse(filePath)
            if (uri.scheme == "file") return File(uri.path ?: filePath)
            val name = UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString()
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
