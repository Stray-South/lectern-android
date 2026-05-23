package com.straysouth.lectern.ui.rsvp

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.repository.PlainTextTokenizer
import com.straysouth.lectern.data.repository.PublicationRepository
import com.straysouth.lectern.data.repository.RsvpPrefs
import com.straysouth.lectern.data.repository.RsvpRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.content

/**
 * V2.4 — RSVP view-model. Holds the tokenized word list, drives the cadence
 * timer, and surfaces play/pause state to [RsvpScreen].
 *
 * Per ADR-AND-X:
 *   - No Locator persistence yet (exit-writes-locator is a V2.4.1 follow-up).
 *   - Clipboard / .txt content stays in this VM's memory only; never logged,
 *     never written to disk, never sent over the network.
 */
class RsvpViewModel(application: Application) : AndroidViewModel(application) {

    private val pubRepository = PublicationRepository(application)
    private val rsvpRepository = RsvpRepository(application)
    private val bookDao = AppDatabase.getInstance(application).bookDao()

    val prefs: StateFlow<RsvpPrefs> = rsvpRepository.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, RsvpPrefs())

    private val _state = MutableStateFlow<RsvpUiState>(RsvpUiState.Loading)
    val state: StateFlow<RsvpUiState> = _state

    private var cadenceJob: Job? = null
    private var loadJob: Job? = null
    private var publication: Publication? = null
    // V2.4 fix: serializes play/pause state transitions so cadenceJob writes
    // are not lost on rapid toggles (adversarial review finding).
    private val cadenceMutex = kotlinx.coroutines.sync.Mutex()

    fun load(source: RsvpSource) {
        // V2.4 fix: cancel any in-flight load + close the prior publication
        // before starting a new load, otherwise the prior publication leaks
        // and the two coroutines race to write _state.value.
        loadJob?.cancel()
        cadenceJob?.cancel()
        cadenceJob = null
        publication?.close()
        publication = null
        loadJob = viewModelScope.launch {
            val text = when (source) {
                is RsvpSource.Clipboard -> source.text
                is RsvpSource.TxtUri -> readTxtUri(source.uri)
                is RsvpSource.Book -> readBookText(source.bookId)
            }
            if (text == null) {
                _state.value = RsvpUiState.Error("Unable to read source")
                return@launch
            }
            val words = PlainTextTokenizer.tokenize(text)
            if (words.isEmpty()) {
                _state.value = RsvpUiState.Error("No readable text")
                return@launch
            }
            _state.value = RsvpUiState.Ready(words = words, currentIndex = 0, isPlaying = false)
        }
    }

    fun togglePlayPause() {
        val current = _state.value as? RsvpUiState.Ready ?: return
        if (current.isPlaying) pause() else play()
    }

    fun play() {
        viewModelScope.launch {
            cadenceMutex.withLock {
                val current = _state.value as? RsvpUiState.Ready ?: return@withLock
                if (current.isPlaying) return@withLock
                _state.value = current.copy(isPlaying = true)
                cadenceJob?.cancel()
                cadenceJob = viewModelScope.launch { runCadence() }
            }
        }
    }

    fun pause() {
        viewModelScope.launch {
            cadenceMutex.withLock {
                cadenceJob?.cancel()
                cadenceJob = null
                val current = _state.value as? RsvpUiState.Ready ?: return@withLock
                _state.value = current.copy(isPlaying = false)
            }
        }
    }

    fun seek(index: Int) {
        val current = _state.value as? RsvpUiState.Ready ?: return
        val clamped = index.coerceIn(0, current.totalWords - 1)
        _state.value = current.copy(currentIndex = clamped)
    }

    fun updateWpm(wpm: Int) {
        viewModelScope.launch {
            rsvpRepository.save(prefs.value.copy(wpm = wpm))
        }
    }

    fun updatePauseOnPunctuation(enabled: Boolean) {
        viewModelScope.launch {
            rsvpRepository.save(prefs.value.copy(pauseOnPunctuation = enabled))
        }
    }

    private suspend fun runCadence() {
        var keepRunning = true
        while (keepRunning) {
            val current = _state.value as? RsvpUiState.Ready
            keepRunning = current != null && current.isPlaying && advanceOnce(current)
        }
    }

    /**
     * Returns true if the cadence loop should continue, false if it should stop
     * (paused, ended, or state transitioned out of Ready).
     */
    @Suppress("ReturnCount")
    private suspend fun advanceOnce(current: RsvpUiState.Ready): Boolean {
        val word = current.currentWord ?: run {
            _state.value = RsvpUiState.Done
            return false
        }
        val baseMs = (MS_PER_MINUTE / prefs.value.wpm).coerceAtLeast(MIN_MS_PER_WORD.toLong())
        val multiplier = if (prefs.value.pauseOnPunctuation) wordMultiplier(word) else 1.0
        delay((baseMs * multiplier).toLong())
        // V2.4 fix: re-read state after delay. If the user called seek() during
        // the delay, the state's currentIndex no longer matches `current` —
        // honor the seek by advancing from the now-current index, not from
        // the stale pre-delay snapshot.
        val latest = _state.value as? RsvpUiState.Ready ?: return false
        if (!latest.isPlaying) return false
        val next = latest.currentIndex + 1
        val keepGoing = next < latest.totalWords
        _state.value = if (keepGoing) latest.copy(currentIndex = next) else RsvpUiState.Done
        return keepGoing
    }

    private fun wordMultiplier(word: String): Double {
        val last = word.lastOrNull() ?: return 1.0
        return when (last) {
            '.', '!', '?' -> RsvpPrefs.SENTENCE_MULTIPLIER
            ',', ';', ':' -> RsvpPrefs.COMMA_MULTIPLIER
            '\n' -> RsvpPrefs.PARAGRAPH_MULTIPLIER
            else -> 1.0
        }
    }

    private suspend fun readBookText(bookId: String): String? {
        val filePath = bookDao.getById(bookId)?.filePath ?: return null
        return pubRepository.open(Uri.parse(filePath))
            .map { pub ->
                publication = pub
                pub.content()?.text() ?: ""
            }
            .getOrElse {
                // ADR-AND-X: log only the exception class, NEVER the message —
                // PublicationRepository embeds the source URI in its error
                // strings, which would leak content:// paths to logcat.
                Log.w(TAG, "Could not open publication for RSVP: ${it.javaClass.simpleName}")
                null
            }
    }

    private fun readTxtUri(uri: Uri): String? {
        return runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            }
        }.getOrElse {
            // Intentionally do NOT log the URI or content — ADR-AND-X privacy clause.
            Log.w(TAG, "TxtUri read failed: ${it.javaClass.simpleName}")
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        cadenceJob?.cancel()
        publication?.close()
    }

    companion object {
        private const val TAG = "RsvpViewModel"
        private const val MS_PER_MINUTE = 60_000L
        private const val MIN_MS_PER_WORD = 75
    }
}
