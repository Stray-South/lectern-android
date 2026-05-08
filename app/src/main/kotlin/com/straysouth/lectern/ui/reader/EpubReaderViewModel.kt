package com.straysouth.lectern.ui.reader

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.ReadingProgress
import com.straysouth.lectern.data.repository.AnchorRepository
import com.straysouth.lectern.data.repository.FocusBandPrefs
import com.straysouth.lectern.data.repository.FocusBandRepository
import com.straysouth.lectern.data.repository.LocatorRepository
import com.straysouth.lectern.data.repository.PublicationRepository
import com.straysouth.lectern.data.repository.TtsPrefs
import com.straysouth.lectern.data.repository.TtsRepository
import com.straysouth.lectern.data.repository.TypographyPrefs
import com.straysouth.lectern.data.repository.TypographyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

// Private type aliases to avoid repeating the full Android TTS type parameter list.
private typealias AndroidTtsFactory = TtsNavigatorFactory<
    AndroidTtsSettings,
    AndroidTtsPreferences,
    AndroidTtsPreferencesEditor,
    AndroidTtsEngine.Error,
    AndroidTtsEngine.Voice>

private typealias AndroidTtsNav = TtsNavigator<
    AndroidTtsSettings,
    AndroidTtsPreferences,
    AndroidTtsEngine.Error,
    AndroidTtsEngine.Voice>

class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val pubRepository = PublicationRepository(application)
    private val locatorRepository = LocatorRepository(application)
    private val typographyRepository = TypographyRepository(application)
    private val ttsRepository = TtsRepository(application)
    private val focusBandRepository = FocusBandRepository(application)
    private val anchorRepository = AnchorRepository(application)
    private val bookDao = AppDatabase.getInstance(application).bookDao()
    private val readingProgressDao = AppDatabase.getInstance(application).readingProgressDao()

    sealed class State {
        object Loading : State()
        data class Ready(
            val publication: Publication,
            val navigatorFactory: EpubNavigatorFactory,
            val initialLocator: Locator?,
            val initialTypography: EpubPreferences,
            val ttsFactory: AndroidTtsFactory?,
        ) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    val typographyPrefs: StateFlow<TypographyPrefs> = typographyRepository.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TypographyPrefs())

    val ttsPrefs: StateFlow<TtsPrefs> = ttsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TtsPrefs())

    val focusBandPrefs: StateFlow<FocusBandPrefs> = focusBandRepository.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FocusBandPrefs())

    private val _ttsUiState = MutableStateFlow<TtsUiState>(TtsUiState.Idle)
    val ttsUiState: StateFlow<TtsUiState> = _ttsUiState

    private val _anchorLocator = MutableStateFlow<Locator?>(null)
    val anchorLocator: StateFlow<Locator?> = _anchorLocator

    // Tracked separately so onCleared() can close regardless of current _state value
    private var _publication: Publication? = null
    private val _loading = java.util.concurrent.atomic.AtomicBoolean(false)

    // Stored on load so cleanUpTts() can persist the anchor without a bookId parameter.
    private var _bookId: String? = null

    private var _ttsNavigator: AndroidTtsNav? = null
    private var _ttsCollectionJob: Job? = null
    // Last sentence locator seen during TTS playback; persisted as anchor on stop.
    private var _lastUtteranceLocator: Locator? = null

    fun load(bookId: String) {
        if (_publication != null || !_loading.compareAndSet(false, true)) return
        _bookId = bookId
        viewModelScope.launch {
            val filePath = bookDao.getById(bookId)?.filePath
            if (filePath == null) {
                _loading.set(false)
                _state.value = State.Error("Book not found")
                return@launch
            }
            val savedLocator = locatorRepository.get(bookId)
            _anchorLocator.value = anchorRepository.get(bookId)
            pubRepository.open(Uri.parse(filePath))
                .onSuccess { publication ->
                    _publication = publication
                    bookDao.updateLastOpened(bookId, System.currentTimeMillis())
                    val app = getApplication<Application>()
                    // Android-specific companion invoke: uses AndroidTtsEngineProvider internally.
                    val ttsFactory: AndroidTtsFactory? = TtsNavigatorFactory(app, publication)
                    _state.value = State.Ready(
                        publication = publication,
                        navigatorFactory = EpubNavigatorFactory(publication),
                        initialLocator = savedLocator,
                        initialTypography = typographyPrefs.value.toEpubPreferences(),
                        ttsFactory = ttsFactory,
                    )
                    _loading.set(false)
                }
                .onFailure { error ->
                    _loading.set(false)
                    Log.e("EpubReaderViewModel", "Could not open publication", error)
                    _state.value = State.Error("Unable to open book")
                }
        }
    }

    // Called on every page turn. Dual-writes: LocatorRepository for navigation restore
    // on next open; ReadingProgressDao for totalProgression display in the library.
    fun saveLocator(bookId: String, locator: Locator) {
        viewModelScope.launch {
            locatorRepository.save(bookId, locator)
            readingProgressDao.upsert(
                ReadingProgress(
                    id = bookId,
                    bookId = bookId,
                    locatorJson = locator.toJSON().toString(),
                    totalProgression = locator.locations.totalProgression,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun updateTypography(prefs: TypographyPrefs) {
        viewModelScope.launch {
            typographyRepository.save(prefs)
        }
    }

    fun updateFocusBand(prefs: FocusBandPrefs) {
        viewModelScope.launch {
            focusBandRepository.save(prefs)
        }
    }

    fun clearAnchor() {
        val bookId = _bookId ?: return
        _anchorLocator.value = null
        viewModelScope.launch {
            anchorRepository.clear(bookId)
        }
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    fun startTts(initialLocator: Locator? = null) {
        val factory = (_state.value as? State.Ready)?.ttsFactory
        if (factory == null) {
            // No TTS engine installed or available (e.g. Samsung One UI 7+).
            _ttsUiState.value = TtsUiState.EngineUnavailable
            return
        }
        // If navigator already exists, resume only if not already playing
        _ttsNavigator?.let { nav ->
            if (!nav.playback.value.playWhenReady) nav.play()
            return
        }
        viewModelScope.launch {
            val listener = object : TtsNavigator.Listener {
                override fun onStopRequested() { stopTts() }
            }
            factory.createNavigator(
                listener = listener,
                initialLocator = initialLocator,
                initialPreferences = AndroidTtsPreferences(speed = ttsPrefs.value.speed),
            ).onSuccess { nav ->
                _ttsNavigator = nav
                nav.play()
                _ttsCollectionJob = launch {
                    combine(nav.playback, nav.location) { pb, loc ->
                        when (pb.state) {
                            is TtsNavigator.State.Ended,
                            is TtsNavigator.State.Failure -> TtsUiState.Idle
                            else -> TtsUiState.Active(
                                isPlaying = pb.playWhenReady,
                                tokenLocator = loc.tokenLocator,
                                utteranceLocator = loc.utteranceLocator,
                            )
                        }
                    }.collect { state ->
                        if (state is TtsUiState.Active) {
                            _lastUtteranceLocator = state.utteranceLocator
                        }
                        _ttsUiState.value = state
                        // Call directly — launching a sibling coroutine races the cancel in cleanUpTts().
                        if (state == TtsUiState.Idle) cleanUpTts()
                    }
                }
            }.onFailure { error ->
                // Engine initialisation race or device-level failure — surface same as no engine.
                Log.w("EpubReaderViewModel", "TTS unavailable: $error")
                _ttsUiState.value = TtsUiState.EngineUnavailable
            }
        }
    }

    fun pauseTts() {
        _ttsNavigator?.pause()
        val current = _ttsUiState.value
        if (current is TtsUiState.Active) {
            _ttsUiState.value = current.copy(isPlaying = false)
        }
    }

    fun stopTts() {
        cleanUpTts()
    }

    /** Clears the EngineUnavailable banner — user has acknowledged it. */
    fun dismissTtsUnavailable() {
        if (_ttsUiState.value is TtsUiState.EngineUnavailable) {
            _ttsUiState.value = TtsUiState.Idle
        }
    }

    fun updateTtsSpeed(speed: Double) {
        viewModelScope.launch {
            ttsRepository.save(TtsPrefs(speed))
            _ttsNavigator?.submitPreferences(AndroidTtsPreferences(speed = speed))
        }
    }

    // Cancels collection job, closes navigator, resets UI state.
    // Persists the last utterance locator as anchor so the reading position
    // remains visually marked after TTS stops.
    // Called from stopTts(), onCleared(), and the Ended/Failure branch of the collect.
    private fun cleanUpTts() {
        _ttsCollectionJob?.cancel()
        _ttsCollectionJob = null
        _ttsNavigator?.close()
        _ttsNavigator = null
        _ttsUiState.value = TtsUiState.Idle
        val bookId = _bookId ?: return
        val anchor = _lastUtteranceLocator ?: return
        _lastUtteranceLocator = null
        _anchorLocator.value = anchor
        viewModelScope.launch {
            anchorRepository.save(bookId, anchor)
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanUpTts()
        _publication?.close()
    }
}
