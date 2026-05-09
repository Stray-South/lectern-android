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

## 2026-05-09T00:00Z — Security Sprint: Group B fixes (B.2 BitmapFactory + B.8 BlockingHttpClient)
- **Did:** Executed fixes for the two Group B code findings.

  **B.2 (BitmapFactory OOM):** Replaced single-pass `BitmapFactory.decodeStream()` in
  `ComicsReaderViewModel` with two-pass decode: `renderZipPage()` and `renderRarPage()` (split
  from `renderPage()` to keep cyclomatic complexity under detekt threshold). Pass 1 uses
  `inJustDecodeBounds = true` to read image header only (≤ 4 KB, no pixel allocation). Pass 2
  decodes with `inSampleSize` computed by `calculateInSampleSize()` to cap both axes at
  `MAX_BITMAP_DIM = 2048` (worst-case 16 MB allocation). zip4j supports independent
  `getInputStream` calls per FileHeader. junrar CBR path opens `Archive` twice per page — same
  as existing sequential-read pattern. Unknown-format fallback: `sampleSize = 1`.

  **B.8 (DefaultHttpClient exfiltration):** New `BlockingHttpClient` object implements
  Readium's `HttpClient` interface and unconditionally returns
  `Try.failure(HttpError.IO(Exception(...)))`. `PublicationRepository` now uses
  `BlockingHttpClient` for both `AssetRetriever` and `DefaultPublicationParser`. No code path
  in V1 requires Readium to make outbound HTTP calls — all EPUB content is served from
  local `content://` assets via `WebViewServer.shouldInterceptRequest()`. Verified: normal
  EPUB import, chapter serving, TTS, and decorations are unaffected by blocking HTTP.

  All gates green (detekt `CyclomaticComplexMethod` resolved by extracting helper methods;
  `ReturnCount` resolved by `?.let` pattern).

- **Why:** B.2 closes app-process OOM risk on malformed CBZ/CBR entries. B.8 closes the
  import-time HTTPS tracking beacon surface introduced by Readium's DefaultHttpClient.
- **Files:** BlockingHttpClient.kt (new), PublicationRepository.kt, ComicsReaderViewModel.kt,
  docs/security/RED-TEAM.md
- **Next:** Plan Group B test implementation for B.3–B.7 (confirmed-safe tests).
- **Blockers:** none

## 2026-05-09T00:00Z — Security Sprint: Group B JVM tests (B.3/B.5/B.7)
- **Did:** Wrote `GroupBSecurityTest.kt` with 8 JUnit 4 tests covering the JVM-testable
  safety properties for B.3, B.5, and B.7. All 8 tests pass.

  **B.3 regression guard (`cbz_pathTraversalEntry_getInputStreamDoesNotExtract`):**
  Creates a CBZ with entry `../../traversal_target.png` using `java.util.zip.ZipOutputStream`.
  Opens with zip4j's `Zip4jFile`. Calls `getInputStream()` on every header. Asserts the
  filesystem snapshot (walkTopDown) is identical before and after — proving `getInputStream()`
  creates zero filesystem artifacts. Catches any future regression where `extractAll()` or
  `extractFile()` is accidentally added to the ViewModel.

  **B.5 tests (`bookCacheId_*`):**
  5 tests verifying: same URI → same ID (determinism); UUID format (8-4-4-4-12 hex);
  ID is keyed on full URI not filename segment; traversal string in URI doesn't collide
  with legitimate URI; non-ASCII URI is stable.

  **B.7 tests (`bookCacheId_*`):**
  2 tests verifying: idempotent duplicate import (same URI → same ID → upsert replaces);
  different URIs with same filename → different IDs (no false collision).

  **Deferred (instrumented test sprint):**
  B.4 (Readium path traversal), B.6 (invalid EPUB import error), B.7 Room upsert semantics
  — all require Android `Context` + Room in-memory DB. Tracked in RED-TEAM.md entries.

  Detekt `NestedBlockDepth` violation fixed by extracting `createTraversalCbz()` and
  `readAllZipEntries()` helpers from the B.3 test function.

- **Why:** Regression tests make the Group B safety properties machine-checkable.
  Without tests, a future change adding `zip.extractAll()` would be silently unsafe.
- **Files:** GroupBSecurityTest.kt (new), docs/security/RED-TEAM.md
- **Next:** Group B complete. Begin Group C (Room) or deferred instrumented tests.
- **Blockers:** none

## 2026-05-09T00:00Z — Group B code-review fixes

- **Did:** Addressed 3 findings from post-DoD code review of Group B changes.

  **Fix 1 — `renderRarPage` corrupt-archive robustness:**
  Replaced `first { it.fileName == entry }` (throws `NoSuchElementException` if archive
  header list diverges between the two `Archive` opens) with `firstOrNull { ... }?.let {}`
  in both passes. If the entry is absent, both passes return null gracefully. The `?.let`
  pattern also avoids non-local returns inside inline lambdas, resolving a detekt
  `ReturnCount` violation (was 4 returns, limit 2).

  **Fix 2 — B.3 test strengthened:**
  `readAllZipEntries()` now returns `List<String>` (raw `FileHeader.fileName` values).
  The B.3 test now asserts `entryNames.any { it.contains("../") }` — confirming zip4j
  surfaces traversal entry names unchanged rather than silently normalising them.
  This is the security-relevant property: the ViewModel uses entry names as read-only
  lookup keys, and the test now verifies zip4j exposes the raw names it was given.

  **Fix 3 — `bookCacheId` cross-session stability test:**
  Replaced the redundant `bookCacheId_sameUri_returnsSameId` (which only asserted
  `f(x) == f(x)` within the same JVM process, redundant with
  `bookCacheId_idempotent_onDuplicateImport`) with `bookCacheId_knownUri_matchesExpectedUuid`.
  The new test asserts a hardcoded expected UUID (`00a4f86e-a2c5-39bb-a313-5ed48abb9580`)
  derived from `UUID.nameUUIDFromBytes` of the known URI — catching any future change to
  the hashing algorithm or charset encoding across JVM sessions.

  All gates green: assembleDebug, testDebugUnitTest (8/8 pass), detekt, ktlintCheck,
  lintDebug, banned-strings, gaze-data-leak.

- **Why:** Code review after DoD surfaced a robustness gap (corrupt CBR), a vacuous test
  (B.3 proved nothing path-traversal-specific), and a redundant test that missed
  cross-session regression coverage.
- **Files:** ComicsReaderViewModel.kt, GroupBSecurityTest.kt
- **Next:** Group C (Room) or deferred instrumented tests.
- **Blockers:** none

## 2026-05-09T01:00Z — Group C JVM security tests (Room DB integrity)

- **Did:** Researched Room implementation (entities, DAOs, migrations, schema JSON) and
  wrote `GroupCSecurityTest.kt` with 8 JVM tests covering all JVM-testable Group C properties.

  **C.2 — No fallbackToDestructiveMigration:**
  Source text assertion that `AppDatabase.getInstance()` builder never calls
  `fallbackToDestructiveMigration()`. If called, Room silently destroys user library data
  on any schema mismatch — unacceptable for a reading app with local-only storage.

  **C.4 — deleteBook cascade guard:**
  `ReadingProgress` has no `@ForeignKey` CASCADE DELETE to `Book`. Orphan cleanup relies
  entirely on `LibraryViewModel.deleteBook()` calling `readingProgressDao.deleteByBookId(id)`.
  Source text assertion verifies both `deleteById` and `deleteByBookId` are called within
  the `deleteBook` function body — guards against the cascade call being silently removed.

  **C.5 — Schema JSON integrity (5 tests):**
  - `schemaV1_identityHash_isStable` — pins v1 hash `187531121d9fe06eec1def42f91a6b93`
  - `schemaV2_identityHash_isStable` — pins v2 hash `3f5b9ab23f084f68bf34e8a4d0c00cdb`
  - `schemaV2_booksTable_hasFormatColumn_notNull` — `format TEXT NOT NULL` present in v2
  - `schemaV1_booksTable_hasNoFormatColumn` — `format` absent from v1
  - `migration1to2_sql_addsFormatColumnWithEpubDefault` — pins exact migration SQL

  **Research findings documented:**
  - No `@ForeignKey` between `books` and `reading_progress` — confirmed gap covered by ViewModel
  - `room-testing` (`androidx.room:room-testing`) not in deps — needed before C.1/C.3/C.4 DB instrumented tests
  - Both `1.json` and `2.json` committed to `app/schemas/`
  - `OnConflictStrategy.REPLACE` on both `BookDao.upsert()` and `ReadingProgressDao.upsert()`

  **Deferred (instrumented test sprint):**
  - C.1: `MigrationTestHelper` v1→v2 data preservation (needs `room-testing` dependency)
  - C.3: Concurrent write safety (needs Android context + Room in-memory DB)
  - C.4 DB: After `deleteBook()`, `getProgress(deletedBookId)` returns null

  All gates green: assembleDebug, testDebugUnitTest (all pass), detekt, ktlintCheck,
  lintDebug, banned-strings, gaze-data-leak.

- **Why:** Room schema drift and silent data destruction are high-severity storage risks.
  Source-pinned tests provide immediate regression coverage while the instrumented sprint
  sets up `androidTest/` infrastructure.
- **Files:** GroupCSecurityTest.kt (new), docs/security/RED-TEAM.md, DEVLOG.md
- **Next:** Group D (DataStore + local storage) or deferred instrumented tests.
- **Blockers:** none

## 2026-05-09T02:00Z — Group D JVM security tests (DataStore + local storage)

- **Did:** Deep-researched Group D (D.1–D.5), resolved D.2 design-decision gap, wrote
  `GroupDSecurityTest.kt` with 13 JVM tests. All gates green.

  **D.1 — Calibration/TTS excluded from D2D (4 tests):**
  XML assertions that `calibration_prefs.preferences_pb` and `tts_prefs.preferences_pb`
  appear in the `<device-transfer>` block; source assertions that both repository DataStore
  delegate names exactly match the exclusion paths. Guards against silent re-enablement
  if a repository is renamed without updating the XML.

  **D.2 — Reading position D2D: confirmed benign (2 tests):**
  Deep research resolved the open design-decision question. The four reading-position
  stores (`reader_prefs`, `anchor_prefs`, `comics_page_prefs`, `pdf_page_prefs`) are not
  excluded, but transferred entries are permanently orphaned: `bookId = UUID(content://URI)`
  and content URIs are device-specific, so keys never match on the new device. Readium
  Locator values contain only EPUB-internal hrefs/CFIs — no PII, no content:// URIs.
  Room is excluded entirely from D2D, so no Book rows are available to look up against.
  Tests pin the `bookId`-discriminator key pattern in all 4 repos and the `nameUUIDFromBytes`
  mechanism — both fail if the key derivation changes to content-stable, prompting addition
  of D2D exclusions. Status updated from 🔍 to ✓ in RED-TEAM.md.

  **D.3 — No sensitive terms in log calls (3 tests):**
  Line-level filter (only lines containing `Log.` or `Timber.`) then assert none of
  `weightsX`, `weightsY`, `irisU`, `irisV`, `CalibrationResult`, `toJsonString` appear.
  CalibrationRepository separately verified to have zero log calls at all.

  **D.4 — No sensitive data in Auto Backup (2 tests):**
  `allowBackup="false"` pinned in manifest; all three cloud-backup wildcard exclusions
  (`file`, `database`, `sharedpref`) verified in the `<cloud-backup>` block.

  **D.5 — App-private storage only (2 tests):**
  Source assertions for filesDir (covers) and cacheDir (CBZ/CBR); global file walk of
  all main-source .kt files asserts zero external storage API usage.

  **Cascade risk mitigations applied:**
  - `deviceTransferBlock()` / `cloudBackupBlock()` helpers scope XML assertions to the
    correct block, preventing cross-block false positives.
  - D.3 log-line filter avoids false positives from legitimate non-log references to
    weight/iris field names in production code.
  - D.5 global walk uses `.flatMap` + `.toList()` pattern (no `ReturnCount` issue);
    violations list in assertion message names the offending file and API.

- **Why:** DataStore misconfiguration (wrong exclusion path, accidental allowBackup
  re-enablement) can silently expose biometric-adjacent calibration data or fail to
  protect book reading history. Source-pinned tests catch regressions before they ship.
- **Files:** GroupDSecurityTest.kt (new), docs/security/RED-TEAM.md, DEVLOG.md
- **Next:** Group E (TTS privacy) or deferred instrumented tests.
- **Blockers:** none

## 2026-05-09T00:00Z — Security sprint: Groups E+F and H JVM tests

- **Did:** Added `GroupEFSecurityTest` (7 tests) and `GroupHSecurityTest` (9 tests).
  All 16 new tests pass. Preflight green.

  **E.1 — TTS teardown on clear (1 test):**
  `EpubReaderViewModel.onCleared()` → `cleanUpTts()` pinned as the last-resort TTS
  teardown. Confirmed gap documented: no `onPause()` hook stops TTS on background —
  deferred to instrumented.

  **E.2 — No annotation entity in V1 (1 test):**
  `AppDatabase.kt` entity list scanned; no Annotation/Highlight/UserNote entity.
  TTS reads `Publication` content only; regression guard triggers if V2 annotation
  feature is added without a TTS-routing review.

  **E.3 — No microphone permission (1 test):**
  Manifest assertFalse for RECORD_AUDIO and MODIFY_AUDIO_SETTINGS.

  **E.4 — EngineUnavailable: no silent no-op (2 tests):**
  ViewModel: ≥ 2 `TtsUiState.EngineUnavailable` emission paths in `startTts()`.
  TtsBar: branch present, `tts_engine_unavailable` string shown, dismiss action wired.

  **F.1 — Exact version pins (1 test):**
  All 5 external deps confirmed: readium 3.1.2, zip4j 2.11.6, junrar 7.5.7,
  ejml 0.44.0, mediapipe 0.10.35. Global no-floating-version guard (TOML `#`
  comment filter applied).

  **F.4 — zip4j no extraction API (1 test):**
  Global walk: `extractFile(`, `extractAll(`, `extractEntry(` absent from all main
  sources. Entries served via `getInputStream()` only.

  **F.6 — Gradle checksums (no test, documented gap):**
  `gradle/verification-metadata.xml` absent. Accepted V1 risk (exact pins + CI).
  RED-TEAM.md updated to ⚠️ with V2 pre-beta action item.

  **H.1 — Network security config (2 tests):**
  `network_security_config.xml` content check + Manifest reference check.

  **H.2 — INTERNET permission, no network calls (1 test):**
  Global walk for `openConnection(`, `OkHttpClient(`, `Retrofit.Builder(`,
  `DefaultHttpClient`; comment lines stripped before check (KDoc false-positive fix
  applied after first test run — `BlockingHttpClient.kt` KDoc mentions
  `DefaultHttpClient` by name).

  **H.3 — Camera feature flags + IOException handler (2 tests):**
  `camera.front required="false"` extracted by line (attribute-order-safe).
  `startGazeInternal` IOException → `_gazeEnabled.value = false` via 300-char window.

  **H.4 — No deep-link / no intent extras (2 tests):**
  Manifest: no `<data android:scheme=>` or `<data android:host=>`.
  `MainActivity.kt`: six-term intent-extras scan clean.

  **H.5 — No ContentProvider (1 test):** Manifest assertFalse `<provider`.

  **H.6 — FLAG_SECURE absent, intentional (1 test):**
  Global walk clean. Test comment documents accessibility rationale + V2 revisit trigger.

- **Why:** Platform-level hardening properties (NSC, intent surface, camera degradation,
  no network in V1) are Manifest/source-checkable without a device. JVM regression
  tests catch accidental rollbacks. Supply-chain exact pins prevent silent dep upgrades.
- **Files:** GroupEFSecurityTest.kt (new), GroupHSecurityTest.kt (new),
  docs/security/RED-TEAM.md, DEVLOG.md
- **Next:** Groups I (coroutine safety) + J (gaze pipeline) JVM tests.
- **Blockers:** none

## 2026-05-09T00:00Z — Security regression tests: Groups I + J (coroutine / gaze privacy)

- **Did:** Added `GroupIJSecurityTest.kt` — 4 JVM security regression tests across
  groups I (Kotlin coroutines / dispatcher safety) and J (gaze privacy).

  **I.1 — Gaze pipeline confinement (1 test):**
  `GazeProviderImpl` uses `Dispatchers.Default.limitedParallelism(1)` for serial
  frame analysis. `calibrate()` confirmed on `confined` dispatcher via `withContext(confined)`.
  Both assertions use comment-stripped source to prevent false positives from KDoc references.

  **I.2 — Room not on main thread (1 test):**
  `AppDatabase.kt` assertFalse `allowMainThreadQueries` — guards against StrictMode
  violations and coroutine-cancellation bypass.

  **I.3 — TTS cleanUp cancels directly, no race (1 test):**
  `cleanUpTts()` body extracted via `nextClassMemberIndex()`. Cancel must precede any
  `launch {` in the body. Boundary helper extended with `val`/`var`/`@` patterns.
  Launch detection uses regex `\blaunch\s*(\([^)]*\)\s*)?\{` to cover `launch{`
  and parametrized forms.

  **J.1 — CalibrationRepository stores weights only (1 test):**
  Comment-stripped whole-file scan of `CalibrationRepository.kt`:
  `weightsX`/`weightsY` present; `irisU`/`irisV` absent. Raw iris UV coordinates
  must never be persisted (ADR-J: gaze data ephemeral in-memory only).

  **Review fixes applied:**
  - `nextClassMemberIndex()` extended with `val`/`var`/`private val`/`private var`/`@`
    patterns to prevent body silently growing past a property declaration.
  - `stripComments()` helper added; used in I.1 and J.1 source scans.
  - Launch detection upgraded from `body.indexOf("launch {")` to regex to cover all forms.
  - KDoc for `stripComments` rewritten as `//` comments to avoid nested-comment
    parse failure (Kotlin nests `/*` inside block comments — `/**` in KDoc opened
    an unclosed level-2 comment that consumed the remaining helpers).

- **Why:** Serialized gaze pipeline, no main-thread DB access, and no racing TTS
  teardown are verifiable from source without device. Iris UV non-persistence is the
  core ADR-J invariant. JVM tests catch regressions before any physical device run.
- **Files:** GroupIJSecurityTest.kt (new), docs/security/RED-TEAM.md, DEVLOG.md
- **Next:** Groups A–G instrumented tests (androidTest sprint).
- **Blockers:** none

## 2026-05-09T00:00Z — Security regression tests: Group G (AuDHD safety)

- **Did:** Added `GroupGSecurityTest.kt` — 6 JVM security regression tests for Group G.

  **G.1 — All tween() durations ≤ 200 ms (1 test):**
  Global walk of main-source `.kt` files. Two regex forms: positional first arg
  `tween(200)` and named param `durationMillis = 200`. All 4 usages in
  `ReaderOverlay.kt` are `tween(200)`. No violations. Variable-based durations
  documented as static-analysis limitation in KDoc.

  **G.2 — No timer-driven auto-advance (1 test):**
  Comment-stripped (whole-line and inline `//` tail) global walk asserts no
  `postDelayed(` or `CountDownTimer(`. Zero occurrences confirmed.
  `BackHandler(` not scanned — Compose API, not a timer.

  **G.5 — Gaze overlay defaults OFF (2 tests):**
  `FocusBandPrefs.kt`: `gazeOverlayEnabled: Boolean = false` (data class default).
  `FocusBandRepository.kt`: `KEY_FIXATION_OVERLAY] ?: false` (DataStore fallback).
  Both layers required: either flipping to `true` silently enables gaze overlay
  on new installs without user consent.

  **G.6 — Calibration BackHandler scoped to calibration guard (1 test):**
  `MainActivity.kt`: asserts `BackHandler { gazeViewModel.cancelCalibration() }`
  AND uses `lastIndexOf` for the calibration guard token so a prior comment
  containing the guard string cannot defeat the ordering check.

  **G.7 — No banned copy in string resources (1 test):**
  Walks `src/main/res/values/*.xml`, case-insensitive scan against 12 banned terms
  from `check_banned_strings.sh`. JVM defence-in-depth alongside the CI shell gate.

  **Review fixes applied:**
  - G.6 ordering check upgraded to `lastIndexOf(guardToken)` so earlier comments
    cannot spoof the containment check.
  - `stripComments()` now also strips inline `//` tails via `substringBefore("//")`
    to prevent false positives from commented-out code on code lines.
  - Removed `values/*.xml` literal from class-level KDoc — the `/*` in that
    path opens a nested Kotlin block comment, consuming the rest of the class.

  **Deferred:** G.3 (Snackbar duration — product decision before V2 beta),
  G.4 (theme flash — instrumented Compose test only).

- **Why:** Animation duration, no auto-advance, gaze default-off, and calibration
  dismissability are concrete AuDHD invariants with real regression risk. Source checks
  catch regressions before device testing. The banned-copy JVM test adds a local-run
  layer that the CI-only shell script cannot provide.
- **Files:** GroupGSecurityTest.kt (new), docs/security/RED-TEAM.md, DEVLOG.md
- **Next:** Group A (EPUB3 WebView security) JVM-testable subset or androidTest sprint.
- **Blockers:** none
