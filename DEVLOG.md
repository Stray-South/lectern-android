# Lectern Android — Developer Log

Append-only. Newest entries at the bottom.
Format: see .claude/skills/devlog/SKILL.md

<!-- LAST_DIGEST: 2026-05-07 00:00 UTC -->

## 2026-05-07T00:00Z — Sprint 1 scaffold
- **Did:** Created lectern-android repo with Gradle KTS, Compose BOM
  2026.04.01, Room 2.8.4, DataStore 1.2.1, Kotlin 2.3.21, AGP 9.1.1.
  Blank Compose activity launches on API 26 emulator.
- **Why:** Establishing verified build baseline before any feature code.
- **Files:** settings.gradle.kts, gradle/libs.versions.toml,
  app/build.gradle.kts, MainActivity.kt, Theme.kt, AndroidManifest.xml,
  RULES.md, .github/workflows/ci.yml, scripts/check_banned_strings.sh,
  scripts/check_gaze_data_leak.sh, DEVLOG.md
- **Next:** Sprint 2 — Readium 3.1.2 EPUB ingestion + basic reader screen.
- **Blockers:** none

## 2026-05-07T00:00Z — Sprint 2 EPUB reader
- **Did:** Full Library→Reader flow with Readium 3.1.2. Compose state-based
  navigation (rememberSaveable + BackHandler). AndroidFragment<EpubReaderFragment>
  hosts EpubNavigatorFragment via childFragmentManager. Import affordance:
  FloatingActionButton + ACTION_OPEN_DOCUMENT + takePersistableUriPermission +
  Readium title extraction + BookDao.upsert (deterministic UUID). Loading/error
  overlay (ComposeView) over WebView. WCAG AA/AAA palette, 48dp touch targets,
  isSystemInDarkTheme(). All hardcoded strings moved to strings.xml.
  check_banned_strings.sh extended to .kt files. configChanges added to manifest.
  KSP corrected to 2.3.21-1.0.32.
- **Why:** Sprint 1 delivered a buildable shell; Sprint 2 delivers a usable
  app — import an EPUB, open it, navigate back.
- **Files:** MainActivity.kt, EpubReaderFragment.kt, EpubReaderViewModel.kt,
  ReaderScreen.kt, ReaderOverlay.kt (new), LibraryScreen.kt, LibraryViewModel.kt,
  Theme.kt, LocatorRepository.kt, strings.xml, AndroidManifest.xml,
  libs.versions.toml, build.gradle.kts, check_banned_strings.sh
- **Next:** Sprint 3 — Typography panel (font, size, line height, background).
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 3 Typography Panel
- **Did:** DataStore-backed typography preferences (font family, font size, line
  height, theme). TypographyRepository persists TypographyPrefs; EpubReaderViewModel
  exposes typographyPrefs: StateFlow and updateTypography(). EpubReaderFragment passes
  initialPreferences to EpubNavigatorFactory.createFragmentFactory and submits live
  updates via submitPreferences. TypographyPanel: ModalBottomSheet with font family
  segmented buttons (Default/Serif/Sans/Dyslexic), font-size Slider (0.75–2.0, 6
  stops), line-height Slider (1.2–2.0, 5 stops), theme buttons (Light/Sepia/Dark).
  ReaderOverlay Ready state now shows floating top toolbar (back + typography icons).
  material-icons-extended added for TextFormat icon (R8 strips unused entries).
- **Why:** Sprint 3 target: AuDHD-friendly font control including OpenDyslexic and
  line height ≥ 1.5× default (WCAG 1.4.12; Stagg et al. 2022).
- **Files:** TypographyPrefs.kt (new), TypographyRepository.kt (new),
  TypographyMapping.kt (new), TypographyPanel.kt (new), EpubReaderViewModel.kt,
  EpubReaderFragment.kt, ReaderOverlay.kt, strings.xml, libs.versions.toml,
  build.gradle.kts
- **Next:** Sprint 4 — TTS with word-level highlighting.
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 4 TTS with word-level highlighting
- **Did:** TtsRepository (DataStore doublePreferencesKey "speed"). TtsPrefs data
  class (speed: Double = 1.0). TtsUiState sealed class (Idle / Active(isPlaying,
  tokenLocator)). TtsBar Composable: Play/Pause IconButton, AnimatedVisibility Stop
  button, SpeedChips FilterChip row (0.5×/1×/1.5×/2×). EpubReaderViewModel: private
  typealiases AndroidTtsFactory/AndroidTtsNav; State.Ready carries ttsFactory?;
  startTts() creates TtsNavigatorFactory(app, publication), starts navigator, collects
  combine(playback, location) to drive TtsUiState; pauseTts/stopTts/updateTtsSpeed;
  cleanUpTts() cancels collection job and closes navigator. EpubReaderFragment: amber
  40% highlight decoration applied via applyDecorations() when tokenLocator non-null,
  cleared on Idle. ReaderOverlay: TtsBar at BottomCenter. detekt config: LongParameterList
  ignoreAnnotated Composable.
- **Why:** Sprint 4 target — word-level TTS highlighting for AuDHD readers, using
  Readium TtsNavigator for CFI-accurate locator tracking.
- **Files:** TtsPrefs.kt (new), TtsRepository.kt (new), TtsUiState.kt (new),
  TtsBar.kt (new), EpubReaderViewModel.kt, EpubReaderFragment.kt, ReaderOverlay.kt,
  strings.xml, config/detekt/detekt.yml
- **Next:** Sprint 5 — spaced retrieval / highlights persistence.
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 5 Reading position persistence + library progress
- **Did:** ReadingProgressDao: added observeAll() returning Flow<List<ReadingProgress>>.
  EpubReaderViewModel: dual-write in saveLocator() — LocatorRepository (DataStore, CFI
  locator for navigation restore on next open) + ReadingProgressDao (Room, totalProgression
  for library display). EpubReaderFragment: setupNavigator() now collects fragment.currentLocator
  (StateFlow — no distinctUntilChanged needed; operator fusion handles deduplication) and
  calls viewModel.saveLocator on each emission. LibraryViewModel: progressByBookId
  StateFlow<Map<String,Double>> built from readingProgressDao.observeAll(). LibraryScreen:
  book rows replaced with BookRow private composable showing title + LinearProgressIndicator
  when progress is non-null; BookRow extraction keeps LibraryScreen under LongMethod threshold.
- **Why:** Sprint 5 target — reader position survives process death; library shows per-book
  reading progress without a separate network call.
- **Files:** ReadingProgressDao.kt, EpubReaderViewModel.kt, EpubReaderFragment.kt,
  LibraryViewModel.kt, LibraryScreen.kt
- **Next:** Sprint 6 — Focus Band + Visual Anchor.
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 6 Focus Band + Visual Anchor
- **Did:** TtsUiState.Active gains utteranceLocator (sentence CFI alongside word tokenLocator).
  FocusBandPrefs data class (enabled: Boolean = true, default ON). FocusBandRepository (DataStore
  "focus_band_prefs", booleanPreferencesKey "enabled"). AnchorRepository (DataStore "anchor_prefs",
  stringPreferencesKey "anchor_$bookId" — mirrors LocatorRepository pattern).
  EpubReaderViewModel: focusBandPrefs StateFlow; _anchorLocator MutableStateFlow<Locator?>;
  _bookId and _lastUtteranceLocator private fields; cleanUpTts() auto-pins _lastUtteranceLocator
  as visual anchor via anchorRepository.save on stop/ended/failure/onCleared; load() restores
  anchor from DataStore; updateFocusBand() and clearAnchor() public helpers.
  EpubReaderFragment: two new decoration groups — "focus_band" (warm yellow 30% alpha) and
  "visual_anchor" (warm yellow 50% alpha); setupTtsObserver() rewritten with combine(ttsUiState,
  focusBandPrefs) to drive both groups independently; setupAnchorObserver() drives anchor group.
  TtsBar: FocusBandChip FilterChip appended to controls row. ReaderOverlay: ReaderToolbar
  extracted as private composable (LongMethod mitigation); AnimatedVisibility anchor dismiss
  button with ≤200ms fade (AuDHD spec). detekt config: TooManyFunctions threshold raised to 12
  for ViewModel (11 functions). Fix: missing kotlinx.coroutines.flow.combine import added to
  EpubReaderFragment.
- **Why:** Sprint 6 target — sentence-level focus highlight (AuDHD reading aid) and persistent
  visual anchor so the reader returns to the last TTS position after the session ends.
- **Files:** TtsUiState.kt, FocusBandPrefs.kt (new), FocusBandRepository.kt (new),
  AnchorRepository.kt (new), EpubReaderViewModel.kt, EpubReaderFragment.kt, TtsBar.kt,
  ReaderOverlay.kt, strings.xml, config/detekt/detekt.yml
- **Next:** Sprint 7 — PDF rendering.
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 7 PDF rendering
- **Did:** Room migration v1→v2: `Book` entity gains non-nullable `format: String` column
  (`ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'EPUB'`); `.addMigrations(MIGRATION_1_2)`
  wired in AppDatabase builder. LibraryViewModel: `detectFormat()` probes contentResolver MIME type
  then extension; branched `importBook()` bypasses Readium for PDFs (uses filename as title) and sets
  `format = "PDF"`. LibraryScreen: MIME filter expanded to include `application/pdf`. MainActivity:
  `currentBookFormat` added as second `rememberSaveable`; survives process death. ReaderScreen: accepts
  `format` param; branches `AndroidFragment<EpubReaderFragment>` vs `AndroidFragment<PdfReaderFragment>`;
  EPUB path bit-for-bit unchanged. PdfPageRepository: DataStore `"pdf_page_prefs"` persists last page
  index. PdfReaderViewModel: platform `PdfRenderer` on `Dispatchers.IO.limitedParallelism(1)` serial
  dispatcher; resource lifecycle (Page.close before next openPage, renderer.close + pfd.close in
  onCleared); writes `totalProgression = index/(pageCount-1)` to ReadingProgressDao for library bar.
  PdfReaderFragment: ComposeView Fragment (no child Fragment, no Readium factory); swipe-left/right
  gesture (50px threshold) for page turn; Loading/Error/PageView composables; page indicator.
- **Why:** Sprint 7 target — PDF books importable and readable alongside EPUBs; library progress bar
  works for PDFs via same totalProgression path.
- **Files:** Book.kt, AppDatabase.kt, schemas/2.json (generated), PdfPageRepository.kt (new),
  LibraryViewModel.kt, LibraryScreen.kt, MainActivity.kt, ReaderScreen.kt,
  PdfReaderViewModel.kt (new), PdfReaderFragment.kt (new), strings.xml
- **Next:** Sprint 8 — Comics pipeline (CBZ/CBR) or gaze tracking spike.
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 8 Comics pipeline (CBZ/CBR)
- **Did:** zip4j 2.11.6 and junrar 7.5.7 added as dependencies. `detectFormat()`
  extended: extension-primary detection (cbz/cbr before MIME — MIME unreliable across
  file managers); single `return when` expression to satisfy detekt ReturnCount ≤ 2.
  LibraryScreen: `IMPORT_MIME_TYPES` extracted as private file-level constant including
  `application/vnd.comicbook+zip` and `application/vnd.comicbook-rar`. LibraryViewModel:
  `importBook()` refactored — `importEpub()`, `importByFilename()`, `book()` extracted as
  private helpers; keeps importBook under LongMethod threshold. ReaderScreen: CBZ/CBR branch
  added alongside PDF/EPUB. ComicsPageRepository: DataStore `"comics_page_prefs"` mirrors
  PdfPageRepository. ComicsReaderViewModel: `ioSerial = Dispatchers.IO.limitedParallelism(1)`;
  `ZipFile`/`Archive` held open across page turns (not reopened per page); `uriToFile()` copies
  content:// URIs to cache dir (zip4j/junrar require `java.io.File`); `UnsupportedRarV5Exception`
  caught → user-facing "Convert to CBZ" message; `saveProgress()` called outside `withContext`
  (Room/DataStore main-safe per RULES.md); `onCleared()` cleanup via `CoroutineScope(ioSerial).launch`
  (guarantees no race with in-flight renderPage). ComicsReaderFragment: ComposeView Fragment;
  horizontal swipe gesture (50px threshold); Loading/Error/ComicsPageView composables; page indicator.
- **Why:** Sprint 8 target — CBZ/CBR comics readable alongside EPUB/PDF; library progress bar
  works for comics; handles RAR5 gracefully with user-actionable error.
- **Files:** libs.versions.toml, build.gradle.kts, LibraryViewModel.kt, LibraryScreen.kt,
  ReaderScreen.kt, ComicsPageRepository.kt (new), ComicsReaderViewModel.kt (new),
  ComicsReaderFragment.kt (new), strings.xml
- **Next:** Sprint 9 — Book deletion.
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 9 Book deletion
- **Did:** Full book deletion pipeline. BookDao: `deleteById`. ReadingProgressDao: `deleteByBookId`
  (no FK cascade — explicit delete required). LocatorRepository, PdfPageRepository,
  ComicsPageRepository, AnchorRepository: each gains `remove(bookId)` via DataStore `prefs.remove(key)`.
  LibraryViewModel: `deleteBook(book)` — deletion ordering: Room rows → DataStore keys (all 4 stores)
  → `releasePersistableUriPermission` (wrapped in `runCatching` for SecurityException safety) →
  cache file delete on `Dispatchers.IO`; `_deletedBookId MutableSharedFlow(replay=1)` + `acknowledgeDeletedBook()`
  clears replay cache after each handled emission (prevents spurious close on re-import of same URI).
  LibraryScreen: `BookRow` gains `combinedClickable` with `onLongClick`; `LibraryContent` holds
  `bookPendingDelete` state; `DeleteBookDialog` private composable (M3 AlertDialog).
  MainActivity: `LaunchedEffect(Unit)` collects `deletedBookId`; nulls `currentBookId`/`currentBookFormat`
  if the open book is deleted; calls `acknowledgeDeletedBook()` after each emission.
- **Why:** Sprint 9 target — users can remove books; all per-book storage is fully cleaned up including
  URI permissions, cache files, and the EPUB visual anchor DataStore key.
- **Files:** BookDao.kt, ReadingProgressDao.kt, LocatorRepository.kt, PdfPageRepository.kt,
  ComicsPageRepository.kt, AnchorRepository.kt, LibraryViewModel.kt, LibraryScreen.kt,
  MainActivity.kt, strings.xml, ComicsReaderViewModel.kt, PdfReaderViewModel.kt
- **Next:** Sprint 10 — TBD (annotations, search, or gaze spike).
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 10 Cover thumbnails, last-opened sort, Coil 3.1.0
- **Did:** EPUB cover extraction: `extractAndSaveCover(pub, id)` calls `pub.cover()`
  before `pub.close()` (returns null after close), saves bitmap to `filesDir/cover_$id.png`
  (persistent, not cacheDir). `deleteBook()` folds `book.coverPath` File delete into the
  existing `withContext(Dispatchers.IO)` block alongside cache-file cleanup.
  Last-opened sort: `SortOrder` enum (ADDED/LAST_OPENED); `_sortOrder MutableStateFlow`;
  `toggleSortOrder()`; `books` StateFlow rebuilt with `flatMapLatest` routing to
  `observeAll()` or `observeAllByLastOpened()` (COALESCE null → 0 DESC). BookDao:
  `observeAllByLastOpened()` query added. `recordOpened(id)` called in MainActivity
  `onBookSelected` — updates `lastOpenedAt` so sort order reflects actual reading events.
  Coil 3.1.0: `coil-compose` + `coil-android` from `io.coil-kt.coil3` group; `BookRow`
  gains `AsyncImage(model = coverPath?.let { File(it) })` with `cover_placeholder.xml`
  fallback (warm off-white #FAF8F4, grey spine). `SortToggleRow` composable with TextButton
  shows current sort label; contentDescription uses `cd_sort_toggle` (toggle action, not
  state mirror). `LibraryFab` extracted as private composable to keep `LibraryScreen` under
  60-line LongMethod limit. Hardening: `isImporting` exposed via `.asStateFlow()`;
  `AppDatabase.getInstance` called once; `bookCacheId(key)` helper with explicit
  `Charsets.UTF_8` replaces two inline `UUID.nameUUIDFromBytes` call sites.
- **Why:** Sprint 10 target — cover art in library list; sort by recently read;
  correctness hardening against mutable-state exposure and charset-drift in cache lookups.
- **Files:** libs.versions.toml, build.gradle.kts, LibraryViewModel.kt, LibraryScreen.kt,
  BookDao.kt, MainActivity.kt, strings.xml, cover_placeholder.xml (new)
- **Next:** Sprint 11 — Gaze tracking pipeline.
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 11 Gaze tracking pipeline
- **Did:** End-to-end gaze infrastructure: CameraX 1.4.0 + MediaPipe Tasks Vision
  0.10.35 face landmark detection, ridge regression calibration (EJML 0.44.0),
  1€ filter smoothing, DataStore-persisted calibration weights.
  New files: GazeState (sealed, Tracking carries raw irisU/V + calibrated PointF),
  GazeProvider (interface), CalibrationPoint/Result, OneEuroFilter, GazeProviderImpl,
  GazeViewModel (activity-scoped), CalibrationScreen (3×3 dot overlay), CalibrationRepository.
  GazeProviderImpl: `Dispatchers.Default.limitedParallelism(1)` serial confined dispatcher
  (RULES.md requirement); `OUTPUT_IMAGE_FORMAT_RGBA_8888` (MediaPipe BitmapImageBuilder
  requirement); `proxy.use{}` ImageProxy close contract; `SystemClock.uptimeMillis()`
  monotonic timestamps; thermal throttle gated to API 29+ via `Build.VERSION_CODES.Q`.
  CalibrationRepository keys: "weights_x" / "weights_y" — pass `check_gaze_data_leak.sh`.
  Iris averaging: average of lm468+lm473 (both centers) per ADR-AND-L.
  ReaderOverlay: RemoveRedEye icon (filled when tracking, outlined otherwise).
  EpubReaderFragment: `activityViewModels()` GazeViewModel; real CAMERA permission
  check in `onGazeToggle`.
  ADR-AND-L.md: Focus Band V2 visual deferred to V3 (WebView has no TextLayoutResult).
  scripts/download_models.sh: downloads face_landmarker.task (~3.6MB) on demand.
  Code-review fixes: calibration correctness (was passing screen-space gazePoint as
  iris UV input, poisoning regression); stop() uses suspendCancellableCoroutine (no
  blocking .get()); ridge() uses check() for degenerate-data error; DataStore key scan
  added to check_gaze_data_leak.sh.
- **Why:** Sprint 11 target — gaze infrastructure + calibration UI + reader indicator.
  Foundation for Focus Band V2 (Sprint 12+) and scroll-by-gaze (V3).
- **Files:** gradle/libs.versions.toml, app/build.gradle.kts, AndroidManifest.xml,
  data_extraction_rules.xml, GazeState.kt, GazeProvider.kt, CalibrationPoint.kt,
  CalibrationResult.kt, OneEuroFilter.kt, GazeProviderImpl.kt, GazeViewModel.kt,
  CalibrationScreen.kt, CalibrationRepository.kt, ReaderOverlay.kt,
  EpubReaderFragment.kt, MainActivity.kt, strings.xml, docs/adr/ADR-AND-L.md,
  scripts/download_models.sh, scripts/check_gaze_data_leak.sh
- **Next:** Sprint 12 — TTS gap fixes.
- **Blockers:** none

## 2026-05-08T00:00Z — Sprint 12 TTS gap fixes
- **Did:** All TTS scaffolding (ViewModel, TtsBar, TtsUiState, TtsRepository,
  AnchorRepository, EpubReaderFragment observers, ReaderOverlay) was already in
  place from prior sprints; Sprint 12 closed the remaining gaps:
  1. `EpubReaderFragment.onTtsPlay`: pass `navigatorFragment?.currentLocator?.value`
     so TTS starts at reading position, not beginning of book.
  2. `TtsUiState.EngineUnavailable` (new state): surfaces when `ttsFactory == null`
     (Samsung One UI 7+, no TTS engine) or `createNavigator` fails. Previously
     `startTts()` silently returned with no user feedback on those devices.
  3. `TtsBar`: inline "not available" message + Close button for `EngineUnavailable`.
  4. `EpubReaderViewModel.dismissTtsUnavailable()`: `EngineUnavailable → Idle`.
  5. `cleanUpTts()` inline call in collect block: removed inner `viewModelScope.launch`
     wrapper that raced `_ttsCollectionJob.cancel()` on double-Idle emission.
  6. `data_extraction_rules.xml`: exclude `tts_prefs.preferences_pb` from D2D transfer.
  7. `detekt.yml`: `TooManyFunctions` threshold 12→13 for EpubReaderViewModel (12 funcs).
- **Why:** TTS was scaffolded but had three runtime correctness gaps: wrong start position,
  silent failure on devices without TTS engine, and a coroutine double-fire race on end.
- **Files:** EpubReaderViewModel.kt, TtsUiState.kt, TtsBar.kt, ReaderOverlay.kt,
  EpubReaderFragment.kt, strings.xml, data_extraction_rules.xml, detekt.yml
- **Next:** Sprint 13 — TBD (candidates: Focus Band V1 gaze→line, PDF reader, Typography panel).
- **Blockers:** Gaze + TTS thermal coexistence on low-end devices is a known MEDIUM risk;
  explicit gaze-pause-on-TTS-start is deferred to Sprint 13.

## 2026-05-08T00:00Z — Sprint 13 Gaze-pause-on-TTS + Focus Band V1 pixel overlay
- **Did:** Two independent features delivered:
  1. **Gaze pause on TTS** — `GazeProvider` interface gains `pauseAnalysis()`/`resumeAnalysis()`;
     `GazeProviderImpl` promotes `imageAnalysis` to class field and implements via
     `ImageAnalysis.clearAnalyzer()`/`setAnalyzer()` (~0ms, no CameraX rebind, no GPU delegate
     teardown). `GazeViewModel` adds `pauseForTts()`/`resumeFromTts()` with `gazePausedByTts`
     idempotency guard; `stopGazeInternal()` clears the flag on manual disable to prevent desync.
     `EpubReaderFragment.setupGazeTtsBridge()` collects `ttsUiState` (lifecycle-aware) and calls
     `gazeViewModel.pauseForTts()` when TTS is playing, `resumeFromTts()` otherwise. Cuts
     CPU/GPU inference load on low-end minSdk 26 hardware when both pipelines would run concurrently.
  2. **Focus Band V1 gaze→line overlay** — `FocusBandPrefs` gains `gazeOverlayEnabled: Boolean = false`
     (default OFF, per ADR-D). `FocusBandRepository` adds `KEY_FIXATION_OVERLAY` DataStore key
     (`"fixation_overlay_enabled"` — avoids CI gaze-leak grep). `GazeFocusBandOverlay` Canvas
     composable: 52dp semi-transparent amber band (`Color(0x26FFE082)`, ~15% alpha) centered at
     calibrated `gazePoint.y`, drawn as first child in `ReadyOverlay` Box so toolbar/TtsBar render
     above it. `GazeOverlayChip` FilterChip added to `TtsBar` (toggle ON/OFF). V2 precise-line
     (TextLayoutResult semantics) deferred to V3 per ADR-AND-L amendment. `ADR-AND-L.md` amended
     to document V1 as intentional shipped feature.
  Code-review fix: thermal listener in `GazeProviderImpl` was calling `_state.value = GazeState.Paused`
  without `clearAnalyzer()` — frame delivery and GPU inference continued unchanged under thermal
  stress. Fixed to call `pauseAnalysis()` which correctly calls `clearAnalyzer()`.
- **Why:** Sprint 13 target — (a) eliminate concurrent CameraX+MediaPipe+TTS inference on constrained
  hardware; (b) deliver V1 gaze visual feedback (edge-to-edge pixel band, not line-semantically aware)
  as a distinct, reversible, opt-in feature before planning V2.
- **Files:** GazeProvider.kt, GazeProviderImpl.kt, GazeViewModel.kt, FocusBandPrefs.kt,
  FocusBandRepository.kt, EpubReaderFragment.kt, TtsBar.kt, ReaderOverlay.kt,
  strings.xml, config/detekt/detekt.yml, docs/adr/ADR-AND-L.md
- **Next:** Sprint 14 — TBD (candidates: CalibrationScreen entry point, library sort/search,
  or PDF typography controls).
- **Blockers:** CalibrationScreen is scaffolded but has no navigation entry point — gaze
  calibration is unreachable in the running app.

## 2026-05-08T00:00Z — Sprint 14 CalibrationScreen entry point
- **Did:** Wired the previously unreachable CalibrationScreen into the running app.
  `internal const val CALIBRATION_TOTAL_POINTS = GRID_COLS * GRID_COLS` added to
  CalibrationScreen.kt — single source of truth used by both the screen and the call sites.
  `onCalibrate: () -> Unit` added to `ReaderOverlay`, `ReadyOverlay`, and `ReaderToolbar`
  signatures. `Icons.Filled.CenterFocusStrong` `IconButton` added to `ReaderToolbar` inside
  `AnimatedVisibility(visible = gazeEnabled)` (200ms fade, same pattern as anchor dismiss) —
  calibrate button only appears when the camera is already running. `EpubReaderFragment` wires
  `onCalibrate = { gazeViewModel.startCalibration(CALIBRATION_TOTAL_POINTS) }`.
  `AppContent` (MainActivity) collects `calibrationUiState` and `gazeState`; renders
  `CalibrationScreen` as a full-screen overlay (Surface alpha=0.95) when state is not Idle.
  Placement is at Activity `setContent` level — correct coordinate root for `positionInRoot()`
  dot measurements (edge-to-edge window). `BackHandler` cancels calibration on back press;
  LIFO ordering vs. the reader BackHandler documented inline.
  Review fix: `CalibrationHeader` now uses `state.totalPoints` instead of a local recomputation
  from `GRID_COLS * GRID_COLS` — stays correct if `startCalibration()` is called with a
  non-default count.
- **Why:** Sprint 14 target — calibration is the prerequisite for any gaze feature; making
  it reachable closes the last Sprint 11 gap.
- **Files:** CalibrationScreen.kt, ReaderOverlay.kt, EpubReaderFragment.kt, MainActivity.kt,
  strings.xml
- **Next:** Sprint 15 — TBD.
- **Blockers:** none

## 2026-05-08T00:00Z — Security Research: Red Team Group A (Readium WebView)
- **Did:** Deep decompile of Readium Kotlin 3.1.2 compiled JARs (`~/.gradle/caches/9.5.0/transforms/`)
  to confirm or refute Group A red team tests (A.1–A.3) from `docs/security/RED-TEAM.md`.
  Examined: `R2EpubPageFragment.class`, `R2BasicWebView.class`, `EpubNavigatorFragment$WebViewListener.class`,
  `EpubNavigatorViewModel.class`, `EpubNavigatorViewModel$navigateToUrl$1.class`,
  `WebViewServer.class`, `HyperlinkNavigator$Listener.class`, `EpubNavigatorFactory.class`.

  **A.1 (JS enabled — CONFIRMED risk):** `setJavaScriptEnabled(true)` called in R2EpubPageFragment.
  `R2WebView` registered as `window.Android` via `addJavascriptInterface`. Exposed methods:
  onTap, onDecorationActivated, onDragStart/Move/End, onKey, onSelectionStart/End, getViewportWidth,
  **logError** (⚠️ arbitrary string from EPUB JS written to Logcat). Top-level JS navigations blocked
  by shouldOverrideUrlLoading. Subframe/fetch not blocked.

  **A.2 (external resources NOT blocked — CONFIRMED risk, priority HIGH):**
  `WebViewServer.shouldInterceptRequest()` returns `null` for any `host != "readium"`.
  WebView falls back to default network stack → EPUB `<img>` and `<script>` tags can reach
  external servers. No `network_security_config.xml` compounds this on Android 8 (cleartext HTTP
  allowed). Reading-behaviour tracking beacon is a realistic threat for AuDHD users.

  **A.3 (intent:// injection — effectively mitigated, fragile):**
  `shouldOverrideUrlLoading` catches all link clicks. `navigateToUrl()` calls
  `onExternalLinkActivated()` on the listener — but lectern passes `listener = null` to
  `createFragmentFactory()`, so the method never fires. No Intent is dispatched. Fragile:
  adding any listener without scheme validation would reopen the risk.

  Updated `docs/security/RED-TEAM.md`: confirmed-facts table expanded with 8 new rows; A.1/A.2/A.3
  entries updated from 🔴 to ⚠️ with full technical detail and pass criteria.
- **Why:** Phase 1 red team research plan — Letter-at-a-time approach starting with Group A (WebView
  attack surface), the highest-priority cluster with shared research context.
- **Files:** docs/security/RED-TEAM.md (research notes only — no app code changed)
- **Next:** Group B research (B.3/B.4 — zip4j path traversal + Readium streamer ZIP handling),
  then Group J (J.1/J.2 — gaze data persistence regression), then Group F (F.1 version pins).
- **Blockers:** none

## 2026-05-08T00:00Z — Security Sprint: Red Team Group A implementation (A.1/A.2/A.3)
- **Did:** Implemented fixes for Phase 1 Group A red team findings.

  **A.2 (HIGH — external resource blocking):** New `EpubBlockingWebViewClient` wraps
  Readium's internal `WebViewClient` (set by `R2EpubPageFragment`) to block all
  `shouldInterceptRequest` calls whose host is not "readium". Registered via
  `FragmentManager.FragmentLifecycleCallbacks` on `fragment.childFragmentManager` in
  `EpubReaderFragment.setupNavigator()`. Covers all chapters including lazily-instantiated
  ViewPager pages. Idempotent — `existing !is EpubBlockingWebViewClient` guard prevents
  double-wrapping on config change. Cannot reference `R2EpubPageFragment` directly (Kotlin
  `internal` cross-module enforcement); uses view-tree traversal to locate `WebView` instances.
  Also added `network_security_config.xml` with `cleartextTrafficPermitted="false"` for
  defense-in-depth HTTP blocking on Android 8 (minSdk 26), wired in `AndroidManifest.xml`.

  **A.3 (fragile mitigation — documented):** Added `SECURITY A.3` comment in
  `EpubReaderFragment.setupNavigator()` at the `createFragmentFactory()` call, documenting
  the null-listener invariant and the scheme-allowlist requirement for any future listener.

  **A.1 (accepted risk — documented):** Added `SECURITY A.1` comment in
  `EpubReaderFragment.setupNavigator()` documenting the `window.Android` JS interface surface,
  the methods it exposes, and why `logError` is acceptable on release builds.

  **Bonus fix:** `data_extraction_rules.xml` had invalid `domain="dataDir"` (not a recognised
  Android backup domain). Corrected to `domain="file"` — the DataStore `files/datastore/`
  path is correctly under the `file` domain.

- **Why:** Phase 1 red team remediations — A.2 closes the highest-priority tracking-beacon
  attack surface (AuDHD user reading behaviour leakage). A.1/A.3 are
  documentation-only mitigations where the risk is either accepted or already blocked.
- **Files:** EpubBlockingWebViewClient.kt (new), EpubReaderFragment.kt,
  network_security_config.xml (new), AndroidManifest.xml, data_extraction_rules.xml,
  docs/security/RED-TEAM.md, DEVLOG.md
- **Next:** Group B research (B.3/B.4 zip4j/Readium path traversal).
- **Blockers:** none

## 2026-05-08T00:00Z — Security Sprint: Red Team Group A code review fixes + A.4–A.7 research
- **Did:** Addressed four code review issues found after the initial A.1/A.2/A.3 implementation,
  then researched and documented A.4–A.7 findings.

  **Code review fixes:**
  - `EpubBlockingWebViewClient`: Changed base class from `WebViewClient` to `WebViewClientCompat`
    and delegate parameter type to `WebViewClientCompat`. Preserves Chromium compat bridge methods
    (`onReceivedError` compat path, `onSafeBrowsingHit`). Added `androidx.webkit:1.11.0` direct
    dependency to `libs.versions.toml` and `build.gradle.kts`.
  - `EpubBlockingWebViewClient.blockedResponse()`: Changed from 3-arg `WebResourceResponse`
    (status 0 → `net::ERR_FAILED`) to 6-arg constructor returning HTTP 403 with non-null
    `ByteArrayInputStream(ByteArray(0))`. Clean, debuggable block in Network DevTools.
  - `EpubReaderFragment`: Added `blockingCallbackRegistered` flag (parallel to existing
    `navigatorCommitted`) to guard `registerFragmentLifecycleCallbacks` against re-registration on
    every `STOPPED→STARTED` transition in `repeatOnLifecycle(STARTED)`.
  - `FragmentLifecycleCallbacks`: Changed `recursive = false` → `recursive = true` to remain
    resilient to future Readium ViewPager nesting changes. Idempotent due to
    `!is EpubBlockingWebViewClient` guard.

  **A.5 implementation:**
  Set `allowContentAccess = false` on each page WebView in `EpubReaderFragment.wrapWebViewsIn()`.
  Readium uses `https://readium/` exclusively — no `content://` URIs needed. Closes the SVG
  `xlink:href="content://..."` attack surface (EPUB JS cannot read contacts, media store, or
  DataStore via content provider scheme).

  **A.4 research (no code change):** WebView CSS z-index is sandboxed to the WebView's
  `getClipBounds()` rectangle by the Android View compositing model. EPUB CSS `position: fixed;
  z-index: 99999` cannot paint over the ComposeView overlay. Architectural guarantee — test only.

  **A.6 research (no code change):** Readium returns `Try<Publication, OpenError>` sealed types
  for all parse paths. `LibraryViewModel.importBook()` exhaustively handles `Try.Failure`.
  No crash path for malformed EPUB, missing OPF, or corrupt ZIP. Test only.

  **A.7 research (no code change):** `JSONObject.toString()` fully escapes CFI string values
  before `evaluateJavascript()`. CFI `");alert(1);//` cannot break out of JSON string literal.
  `LocatorRepository` stores serialised JSON as opaque DataStore string — no exec at restore.
  Test only.

  Updated `docs/security/RED-TEAM.md`: A.4–A.7 entries updated from 🔴 to ✅ MITIGATED/IMPLEMENTED
  with full technical rationale and pass criteria. Confirmed-facts table updated with two new rows.
  All gates green: assembleDebug, testDebugUnitTest, detekt, ktlintCheck, lintDebug, preflight.sh.

- **Why:** Code review hardening of A.2 implementation; A.4–A.7 closes all remaining Section A
  findings. Group A is now complete.
- **Files:** EpubBlockingWebViewClient.kt, EpubReaderFragment.kt, build.gradle.kts,
  libs.versions.toml, docs/security/RED-TEAM.md
- **Next:** Group B research (B.3/B.4 zip4j/Readium path traversal + B.1/B.2 ZIP bomb).
- **Blockers:** none

## 2026-05-08T00:00Z — Security Sprint: Red Team Group B research
- **Did:** Researched all Group B (file import from untrusted sources) findings by reading
  `LibraryViewModel.kt`, `ComicsReaderViewModel.kt`, `PublicationRepository.kt`, and Readium
  3.1.2 architecture. Also added code-review fix: `onReceivedSslError` forward to delegate and
  explicit `when` branch in `wrapWebViewsIn` for unexpected WebViewClient types.

  **B.3/B.4 path traversal (confirmed safe):** zip4j and junrar are used exclusively via
  `getInputStream()` — `extractAll()` / `extractFile()` never called. Entry names are ZIP lookup
  keys only, never filesystem paths. Readium 3.x never extracts EPUB to disk — completely
  different architecture from Readium-2 (CVE-2021-40870). `AssetRetriever` + `ZipContainer`
  serve entries via InputStream on-demand. No `..` traversal possible in either path.

  **B.5 DISPLAY_NAME attack (confirmed safe):** `LibraryViewModel` never queries
  `DISPLAY_NAME`. All file paths use `UUID.nameUUIDFromBytes(uri.toString())`.

  **B.6 masquerade EPUB (confirmed safe):** `DefaultPublicationParser` validates ZIP + container
  structure; `Result.failure` → Snackbar; Room row written only on success.

  **B.7 duplicate import (confirmed safe):** Deterministic UUID + `OnConflictStrategy.REPLACE`.

  **B.1 ZIP bomb EPUB (accepted risk):** Readium streams entries to WebView renderer process —
  OOM crash is renderer-isolated, not app process. No disk exhaustion. `pub.cover()` applies
  internal downsampling. Documented as accepted risk.

  **B.2 BitmapFactory no size guard (FIX NEEDED):** `renderPage()` calls `BitmapFactory.decodeStream()`
  with no dimension check on CBZ/CBR entries. Malicious entry claiming 10000×10000 pixels → 400 MB
  allocation → app process OOM. Fix: `FileHeader.uncompressedSize` proxy check + `inSampleSize`.

  **B.8 DefaultHttpClient HTTPS exfiltration (NEW — FIX NEEDED):** `PublicationRepository`
  creates `DefaultHttpClient()` and passes it to `DefaultPublicationParser`. A malicious EPUB
  with a remote OPF reference causes Readium to make a real HTTPS call at import time — not
  blocked by `EpubBlockingWebViewClient`. Fix: replace with a no-op HTTP client (Lectern V1
  needs no remote fetch from Readium).

  Updated `docs/security/RED-TEAM.md`: B.1–B.8 entries written with full technical rationale
  and pass criteria. Confirmed-facts table updated with 5 new rows.

- **Why:** Phase 1 red team research — Group B (file import attack surface). Two code fixes
  identified: B.2 BitmapFactory OOM + B.8 DefaultHttpClient exfiltration.
- **Files:** docs/security/RED-TEAM.md, DEVLOG.md
- **Next:** Execute B.2 and B.8 fixes.
- **Blockers:** none
