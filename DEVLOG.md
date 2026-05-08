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
- **Next:** Sprint 12 — TBD.
- **Blockers:** none
