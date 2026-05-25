package com.straysouth.lectern.ui.reader

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.audio.AudioSessionCoordinator
import com.straysouth.lectern.service.TtsForegroundService
import com.straysouth.lectern.service.TtsForegroundService.LocalBinder
import com.straysouth.lectern.service.TtsServiceCallbacks
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.ReadingProgress
import com.straysouth.lectern.data.repository.AnchorRepository
import com.straysouth.lectern.data.repository.AnnotationRepository
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
import kotlinx.coroutines.flow.receiveAsFlow
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

@Suppress("TooManyFunctions")
class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val pubRepository = PublicationRepository(application)
    private val locatorRepository = LocatorRepository(application)
    private val typographyRepository = TypographyRepository(application)
    private val ttsRepository = TtsRepository(application)
    private val focusBandRepository = FocusBandRepository(application)
    private val anchorRepository = AnchorRepository(application)
    private val bookDao = AppDatabase.getInstance(application).bookDao()
    private val readingProgressDao = AppDatabase.getInstance(application).readingProgressDao()
    // V2.2 — annotations. The Repository indirection keeps `AnnotationDao` out of
    // this VM's import set so `GroupEFSecurityTest.tts_doesNotReadAnnotationBodyText`
    // can pin "no annotation text path inside the TTS-owning ViewModel" by source
    // assertion on the missing `AnnotationDao` import.
    private val annotationRepository = AnnotationRepository(
        AppDatabase.getInstance(application).annotationDao(),
    )

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

    // V2.6 — chapter rotor source. Populated once on publication open from
    // Publication.tableOfContents. Top-level entries only (depth 0); footnote /
    // image / link rotors are explicitly out of scope for the first V2 cut.
    // Consumed by EpubReaderFragment which installs one ViewCompat
    // accessibility action per entry on the navigator's root view.
    private val _tocEntries = MutableStateFlow<List<org.readium.r2.shared.publication.Link>>(emptyList())
    val tocEntries: StateFlow<List<org.readium.r2.shared.publication.Link>> = _tocEntries

    // V2.2 — annotations stream for the open book. Re-computed whenever load(bookId)
    // is called; subscribers (EpubReaderFragment for decoration rendering) collect on
    // every emission. Empty until a book is loaded.
    private val _annotationsForOpenBook = MutableStateFlow<List<com.straysouth.lectern.data.db.Annotation>>(emptyList())
    val annotationsForOpenBook: StateFlow<List<com.straysouth.lectern.data.db.Annotation>> = _annotationsForOpenBook
    private var annotationCollectionJob: Job? = null

    private val _anchorLocator = MutableStateFlow<Locator?>(null)
    val anchorLocator: StateFlow<Locator?> = _anchorLocator

    // Tracked separately so onCleared() can close regardless of current _state value
    private var _publication: Publication? = null
    private val _loading = java.util.concurrent.atomic.AtomicBoolean(false)

    // Stored on load so cleanUpTts() can persist the anchor without a bookId parameter.
    private var _bookId: String? = null

    // @Volatile: read on the audio-focus thread via pauseTts() (invoked from
    // AudioSessionCoordinator.onLoss); written on viewModelScope (Main).
    // ADR-AND-A documents the cross-thread reception contract.
    @Volatile private var _ttsNavigator: AndroidTtsNav? = null
    private var _ttsCollectionJob: Job? = null
    // Last sentence locator seen during TTS playback; persisted as anchor on stop.
    private var _lastUtteranceLocator: Locator? = null
    // Sole owner of AudioManager focus state — see ADR-AND-A.
    private val audioSession = AudioSessionCoordinator(application)

    // V2.9 — foreground-service binding. The service is a sentinel + notification
    // owner per ADR-AND-W; this VM remains the sole owner of _ttsNavigator and
    // the AudioSessionCoordinator binding. Null while no TTS session is active
    // or when POST_NOTIFICATIONS is denied (graceful fallback to V1 behaviour).
    @Volatile private var _ttsServiceBinder: LocalBinder? = null
    private var _bookTitleForNotification: String? = null

    // V2.9-A adversarial fix: one-shot events, not persistent state.
    // The previous StateFlow<Boolean> only fired the Snackbar on the false→true
    // transition; a user who declined POST_NOTIFICATIONS once and re-attempted
    // TTS would see nothing. Channel-backed events fire on every attempt.
    //
    // BufferOverflow.DROP_OLDEST + capacity=1: rapid repeat attempts collapse
    // to a single "latest" notification rather than queueing N Snackbars.
    private val _permissionDeniedEvents =
        kotlinx.coroutines.channels.Channel<Unit>(
            capacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    val permissionDeniedEvents: kotlinx.coroutines.flow.Flow<Unit> =
        _permissionDeniedEvents.receiveAsFlow()

    // V2.9-A adversarial fix: lazy permission prompt. The launcher lives in the
    // Fragment (must be registered before STARTED); the VM signals "ask now"
    // via this channel when startTts() needs the permission and we haven't
    // asked yet in this VM lifetime. Avoids prompting on Fragment.onCreate
    // for users who never open the TTS panel.
    private val _permissionRequestEvents =
        kotlinx.coroutines.channels.Channel<Unit>(
            capacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    val permissionRequestEvents: kotlinx.coroutines.flow.Flow<Unit> =
        _permissionRequestEvents.receiveAsFlow()

    // V2.9-A adversarial fix: surfaces ForegroundServiceStartNotAllowedException
    // (API 31+) when the OS rejects the FGS start. Distinct from the
    // permission-denied event so the Snackbar copy can name the failure mode
    // ("background playback unavailable" vs "notifications denied"). Per
    // ADR-AND-W §FGS start exception policy.
    private val _backgroundPlaybackUnavailableEvents =
        kotlinx.coroutines.channels.Channel<Unit>(
            capacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    val backgroundPlaybackUnavailableEvents: kotlinx.coroutines.flow.Flow<Unit> =
        _backgroundPlaybackUnavailableEvents.receiveAsFlow()

    // Guards "ask only once per VM lifetime" so a user who is mid-decision
    // doesn't get a second system dialog stacked on top. Reset only on
    // explicit grant or when the VM is cleared.
    @Volatile private var _permissionRequestInFlight = false

    // Guards "ask only once total per VM lifetime". After the first prompt
    // (regardless of grant/deny), subsequent missing-permission attempts go
    // straight to the Snackbar — re-prompting on every startTts() is
    // user-hostile (Android shows the dialog up to 2x before silent denial,
    // but UX expectation is one prompt then in-app feedback).
    @Volatile private var _postNotificationsAskedOnce = false

    private val ttsServiceCallbacks = object : TtsServiceCallbacks {
        override fun onPlayPause() {
            val active = _ttsUiState.value as? TtsUiState.Active
            if (active?.isPlaying == true) pauseTts() else startTts()
        }
        override fun onStop() { stopTts() }
        override fun onTaskRemoved() { stopTts() }
    }

    private val ttsServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as? LocalBinder ?: return
            _ttsServiceBinder = local
            local.setCallbacks(ttsServiceCallbacks)
            pushNowPlaying()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _ttsServiceBinder = null
        }
    }

    fun load(bookId: String) {
        if (_publication != null || !_loading.compareAndSet(false, true)) return
        _bookId = bookId
        viewModelScope.launch {
            val book = bookDao.getById(bookId)
            val filePath = book?.filePath
            if (filePath == null) {
                _loading.set(false)
                _state.value = State.Error("Book not found")
                return@launch
            }
            _bookTitleForNotification = book.title
            val savedLocator = locatorRepository.get(bookId)
            _anchorLocator.value = anchorRepository.get(bookId)
            pubRepository.open(Uri.parse(filePath))
                .onSuccess { publication ->
                    _publication = publication
                    _tocEntries.value = publication.tableOfContents
                    // V2.2 — start collecting annotations for this book. Re-collection
                    // on a subsequent load() is guarded by the cancel-and-replace
                    // pattern; the suspend collect runs forever on viewModelScope
                    // until onCleared() (or until annotationCollectionJob is cancelled).
                    annotationCollectionJob?.cancel()
                    annotationCollectionJob = viewModelScope.launch {
                        annotationRepository.observeForBook(bookId).collect { list ->
                            _annotationsForOpenBook.value = list
                        }
                    }
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
        if (resumeExistingTts()) return
        viewModelScope.launch {
            val listener = object : TtsNavigator.Listener {
                override fun onStopRequested() { stopTts() }
            }
            factory.createNavigator(
                listener = listener,
                initialLocator = initialLocator,
                initialPreferences = AndroidTtsPreferences(speed = ttsPrefs.value.speed),
            ).onSuccess { nav ->
                // Audio focus owned by AudioSessionCoordinator (ADR-AND-A). pauseTts is
                // invoked on AUDIOFOCUS_LOSS / LOSS_TRANSIENT so calls and navigation
                // prompts interrupt TTS rather than overlapping spoken-word audio.
                val granted = audioSession.acquireForTts(onLoss = ::pauseTts)
                if (!granted) {
                    // Another app holds exclusive focus (e.g. active call) — don't start TTS.
                    // Close the just-created navigator to release its TextToSpeech binding;
                    // without this the engine binding leaks (nav is unreachable after return).
                    nav.close()
                    return@onSuccess
                }
                _ttsNavigator = nav
                bindForegroundServiceIfPermitted()
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
                            pushNowPlaying(state)
                        }
                        _ttsUiState.value = state
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

    // Returns true if an existing navigator handled the request so the caller can return early.
    // Re-requests focus before play(): focus may have been lost transiently after
    // AUDIOFOCUS_LOSS_TRANSIENT → pauseTts() without nulling _ttsNavigator.
    private fun resumeExistingTts(): Boolean {
        val nav = _ttsNavigator ?: return false
        if (!nav.playback.value.playWhenReady) {
            if (audioSession.reacquire()) nav.play()
        }
        return true
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

    // Cancels collection job, closes navigator, resets UI state, releases audio focus.
    // Persists the last utterance locator as anchor so the reading position
    // remains visually marked after TTS stops.
    // Called from stopTts(), onCleared(), and the Ended/Failure branch of the collect.
    private fun cleanUpTts() {
        _ttsCollectionJob?.cancel()
        _ttsCollectionJob = null
        _ttsNavigator?.close()
        _ttsNavigator = null
        _ttsUiState.value = TtsUiState.Idle
        // Release audio focus so music apps resume at full volume after TTS stops.
        audioSession.release()
        unbindAndStopForegroundService()
        val bookId = _bookId ?: return
        val anchor = _lastUtteranceLocator ?: return
        _lastUtteranceLocator = null
        _anchorLocator.value = anchor
        viewModelScope.launch {
            anchorRepository.save(bookId, anchor)
        }
    }

    /**
     * V2.2 — create a highlight at [locator] in the currently-open book.
     *
     * Called from the Fragment after `navigator.currentSelection()` resolves;
     * the Fragment passes the resolved locator here so the VM doesn't need a
     * direct navigator reference. The Repository handles JSON serialization
     * (via `Locator.toJSON()` per ADR-AND-N §8) and UUID generation.
     *
     * No-op if [_bookId] is null (no book open) — guards against a stale
     * highlight request firing after teardown.
     */
    fun createHighlight(locator: Locator) {
        val bookId = _bookId ?: return
        viewModelScope.launch {
            annotationRepository.createHighlight(bookId, locator)
        }
    }

    /**
     * V2.2.2 — create a note at [locator] with the user-typed [body].
     *
     * The dialog-side validation already rejected blank/empty body, so this
     * function trusts the input. body verbatim — user-content opaque to the
     * banned-token AuDHD lint (which targets app strings, not user data).
     *
     * No-op if no book is open.
     */
    fun createNote(locator: Locator, body: String) {
        val bookId = _bookId ?: return
        viewModelScope.launch {
            annotationRepository.createNote(bookId, locator, body)
        }
    }

    /**
     * V2.2.2 — delete an annotation. V2.2.3 expanded to take the full
     * Annotation (not just id) so the undo Snackbar can restore the
     * exact row. Emits the deleted Annotation on [deletedAnnotations]
     * for the Snackbar host to consume.
     */
    fun deleteAnnotation(annotation: com.straysouth.lectern.data.db.Annotation) {
        viewModelScope.launch {
            annotationRepository.delete(annotation.id)
            _deletedAnnotationsChannel.trySend(annotation)
        }
    }

    /**
     * V2.2.3 — re-insert a deleted annotation. Bound to the Snackbar's
     * Undo action. The row is identical to the pre-delete row (id, body,
     * locatorJson, createdAt all preserved); the user observes the
     * decoration reappear in the same position.
     */
    fun restoreAnnotation(annotation: com.straysouth.lectern.data.db.Annotation) {
        viewModelScope.launch {
            annotationRepository.upsert(annotation)
        }
    }

    /**
     * V2.2.3 — undo events for the delete Snackbar. V2.2.3 fix: replaced
     * the prior SharedFlow(replay=0, extraBufferCapacity=1) with a
     * Channel-backed flow so rapid multi-delete and the (delete →
     * Fragment-destroy-before-collector-resumes) race no longer drop
     * undo affordances.
     *
     * Semantics:
     *   - Channel.UNLIMITED: emits never suspend or drop on burst delete.
     *   - consumeAsFlow(): single-consumer; the next Snackbar collector
     *     re-subscribes after configuration change and picks up any
     *     events that arrived while no Composable was active.
     *   - Delete is already committed to DB when the event is sent;
     *     undo is the user's only recovery, so the event must not be
     *     lost.
     */
    private val _deletedAnnotationsChannel =
        kotlinx.coroutines.channels.Channel<com.straysouth.lectern.data.db.Annotation>(
            capacity = kotlinx.coroutines.channels.Channel.UNLIMITED,
        )
    val deletedAnnotations: kotlinx.coroutines.flow.Flow<com.straysouth.lectern.data.db.Annotation> =
        _deletedAnnotationsChannel.receiveAsFlow()

    /**
     * V2.2.2 — request the Fragment navigate the reader to [locator].
     *
     * SharedFlow with replay=0 — emits are one-shot events, not state.
     * Fragment-side collector calls `navigatorFragment.go(locator)`.
     * Used by the annotation list panel (tap row → navigate).
     */
    private val _navigationRequests = kotlinx.coroutines.flow.MutableSharedFlow<Locator>(replay = 0)
    val navigationRequests: kotlinx.coroutines.flow.SharedFlow<Locator> = _navigationRequests

    fun requestNavigation(locator: Locator) {
        viewModelScope.launch {
            _navigationRequests.emit(locator)
        }
    }

    /**
     * V2.2.2 — pending locator for the note-entry dialog. Non-null while
     * the user has selected text and tapped "Note"; the Composable observes
     * this state to show/hide the dialog. Owned by the VM so the dialog
     * survives configuration changes.
     */
    private val _pendingNoteLocator = MutableStateFlow<Locator?>(null)
    val pendingNoteLocator: StateFlow<Locator?> = _pendingNoteLocator

    fun startNoteEntry(locator: Locator) {
        _pendingNoteLocator.value = locator
    }

    fun cancelNoteEntry() {
        _pendingNoteLocator.value = null
    }

    fun confirmNoteEntry(body: String) {
        val locator = _pendingNoteLocator.value ?: return
        createNote(locator, body)
        _pendingNoteLocator.value = null
    }

    // ── V2.9 foreground service plumbing ────────────────────────────────────

    /**
     * V2.9-A adversarial fix #2: the POST_NOTIFICATIONS runtime grant is now
     * requested lazily on the first [startTts] attempt, not from
     * [EpubReaderFragment.onCreate]. Users who never open the TTS panel never
     * see the permission dialog. Called via [permissionRequestEvents] —
     * Fragment owns the launcher (activity-result contracts must register
     * before STARTED) and triggers it on event.
     */
    fun onPostNotificationsResult(granted: Boolean) {
        _permissionRequestInFlight = false
        _postNotificationsAskedOnce = true
        if (granted) {
            if (_ttsNavigator != null && _ttsServiceBinder == null) {
                bindForegroundServiceIfPermitted()
            }
        } else {
            // Denial path: fire the Snackbar even on first deny. Subsequent
            // startTts() attempts also fire (per-attempt, not first-only) via
            // the gate inside bindForegroundServiceIfPermitted.
            _permissionDeniedEvents.trySend(Unit)
        }
    }

    private fun needsPostNotificationsRequest(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return !hasPostNotificationsPermission()
    }

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val ctx: Context = getApplication()
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun bindForegroundServiceIfPermitted() {
        // V2.9-A adversarial fix #2+#4: lazy prompt + one-shot Snackbar.
        // First missing-permission attempt → trigger the system dialog.
        // Subsequent attempts (or denial) → fire Snackbar per-attempt.
        // We do NOT request the prompt again if one is already in flight
        // (the system would queue a second dialog above the first).
        if (!hasPostNotificationsPermission()) {
            val canAsk = needsPostNotificationsRequest() &&
                !_postNotificationsAskedOnce &&
                !_permissionRequestInFlight
            if (canAsk) {
                _permissionRequestInFlight = true
                _permissionRequestEvents.trySend(Unit)
            } else {
                _permissionDeniedEvents.trySend(Unit)
            }
            return
        }
        if (_ttsServiceBinder != null) return
        startAndBindForegroundService()
    }

    private fun startAndBindForegroundService() {
        val ctx: Context = getApplication()
        val intent = Intent(ctx, TtsForegroundService::class.java)
        // V2.9-A adversarial fix #3: ForegroundServiceStartNotAllowedException
        // (API 31+) fires if the OS decides the app isn't allowed to start a
        // FGS at this moment (no qualifying allowlist condition: not in
        // foreground, no recent user interaction, no exempt source). On catch
        // we fall back to V1 foreground-only TTS and surface a Snackbar so
        // the user knows background playback won't survive backgrounding.
        // Per ADR-AND-W §FGS start exception policy.
        //
        // Catch the specific exception class on API 31+; on older API levels
        // the exception type doesn't exist, so a generic IllegalStateException
        // catch covers the (unreachable on 26-30) defensive path.
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: IllegalStateException) {
            // FgServiceStartNotAllowedException extends IllegalStateException
            // and is the only IllegalStateException this call documents on
            // API 31+; on API 26-30 we should never reach this catch, but
            // the broader type keeps the API floor clean.
            Log.w("EpubReaderViewModel", "startForegroundService rejected; falling back to V1 TTS path", e)
            _backgroundPlaybackUnavailableEvents.trySend(Unit)
            return
        }
        ctx.bindService(intent, ttsServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindAndStopForegroundService() {
        val ctx: Context = getApplication()
        val binder = _ttsServiceBinder
        if (binder != null) {
            binder.setCallbacks(null)
            try {
                ctx.unbindService(ttsServiceConnection)
            } catch (_: IllegalArgumentException) {
                // Connection already released by the system; idempotent stop.
            }
            _ttsServiceBinder = null
        }
        ctx.stopService(Intent(ctx, TtsForegroundService::class.java))
    }

    private fun pushNowPlaying(state: TtsUiState.Active? = _ttsUiState.value as? TtsUiState.Active) {
        val binder = _ttsServiceBinder ?: return
        val title = _bookTitleForNotification
            ?: getApplication<Application>().getString(com.straysouth.lectern.R.string.app_name)
        val chapter = state?.utteranceLocator?.title
        binder.updateNowPlaying(title, chapter, state?.isPlaying ?: true)
    }

    override fun onCleared() {
        super.onCleared()
        cleanUpTts()
        annotationCollectionJob?.cancel()
        _deletedAnnotationsChannel.close()
        _publication?.close()
    }
}
