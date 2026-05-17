# 06 — Progress

**Last updated:** 2026-05-17 (Sprint 25, Set 5)

Status key: ✅ shipped & tested · 🚧 in progress · 🔲 deferred (V2+)

## Library (✅)

- `LibraryViewModel` + `Book` Room entity (2 columns + format)
- `LibraryScreen` Compose UI
- Book import via SAF (EPUB / PDF / CBZ / CBR)
- Cover thumbnail extraction (EPUB via `Publication.cover()`)
- Sort by added / last-opened
- Delete with confirmation
- Tests: `GroupBSecurityTest.bookCacheId_*` (7 tests, deterministic ID),
  `DeleteBookDbTest`, `DuplicateImportDbTest`, `RoomConcurrencyTest`
  (instrumented)

## EPUB Reader (✅)

- Readium 3.1.2 `EpubNavigatorFragment` in `ComposeView` host
- `EpubBlockingWebViewClient` enforces:
  - Predicate host gate (only `https://readium/` resolves)
  - Scheme denylist
  - `allowContentAccess = false` unconditional
  - No `evaluateJavascript`, no `addJavascriptInterface` in main sources
- `BlockingHttpClient` wired into both Readium network entry points
- Locator persistence (`LocatorRepository`, `toJSON()` serialization)
- Reading anchor (`AnchorRepository`, last-utterance position)
- Typography preferences applied via `EpubPreferences`
- Tests: `GroupASecurityTest` (14 tests, full EPUB WebView surface),
  `GroupCSecurityTest.schemaV*_identityHash` + `migration1to2`,
  `EpubImportTest` (instrumented)

## PDF Reader (✅)

- `PdfReaderViewModel` + Android `PdfRenderer`-backed
  `PdfPageRepository`
- Page navigation, fit-to-width
- Bitmap two-pass decode (`maxBitmapDim = 2048`, `inSampleSize` guard)

## Comics Reader (✅)

- `ComicsReaderViewModel` + `ComicsPageRepository` (zip4j
  `getInputStream` only — no on-disk extraction)
- Bitmap two-pass decode + `inSampleSize` calculation
- Zip-Slip prevention pinned by `cbz_pathTraversalEntry_getInputStreamDoesNotExtract`

## TTS (✅)

- Readium `TtsNavigator` wraps `android.speech.tts.TextToSpeech`
- `AudioSessionCoordinator` sole-owner (ADR-AND-A)
- `AUDIOFOCUS_GAIN_TRANSIENT`, granted-check, resume-reacquire
- Engine-unavailable state surfaces via `TtsUiState.EngineUnavailable`
- `@Volatile _ttsNavigator` for audio-focus-thread visibility
- TTS navigator closed on focus-denied path
- Tests in `GroupEFSecurityTest`:
  `tts_audioFocus_ownedByAudioSessionCoordinator`,
  `tts_viewModelDelegatesToAudioSessionCoordinator`,
  `tts_audioFocus_denied_closesNavigator`,
  `tts_engineUnavailable_viewModel_emitsState`,
  `tts_noAnnotationFeatureInV1`, `tts_noMicrophonePermissionRequested`,
  `tts_onStop_callsPauseTts`, `tts_onCleared_callsCleanUpTts`

## Gaze tracking (✅)

- CameraX front-camera `ImageAnalysis` pipeline
- MediaPipe `FaceLandmarker` `LIVE_STREAM`, `numFaces = 1`, GPU delegate,
  asset model
- 6-feature ridge regression calibration (EJML), λ = 1e-4, LOO-CV error
- Confined dispatcher (`Dispatchers.Default.limitedParallelism(1)`)
- Thermal throttle pauses analysis (clears analyzer, not just flag)
- Permission revocation handled cleanly via `SecurityException` catch
- Tests:
  - `GroupGSecurityTest.audhd_gazeOverlay_defaultOff_inPrefsClass` /
    `_inRepository`
  - `GroupIJSecurityTest.gaze_*` (multiple)
  - `GroupDSecurityTest.gazeProviderImpl_logLines_containNoWeightOrIrisData`,
    `gazeViewModel_logLines_containNoWeightOrIrisData`,
    `calibrationRepository_hasNoLogCalls`

## Calibration UI (✅)

- 9-point calibration flow with on-screen targets
- `BackHandler` wired to `cancelCalibration`
- `CalibrationError` surfaces fixed user-facing string
  `"Couldn't complete calibration. Try again."`
- `e.message` channel dropped — no third-party banned-token leak (Set 2
  PR-F adversarial finding)

## Focus Band overlay (✅, V1 pixel-only — see ADR-AND-L)

- Pixel-Y horizontal band in Compose overlay layer (`GazeFocusBandOverlay`)
- Default-OFF in `FocusBandPrefs` + `FocusBandRepository`
- V2 line-level highlight deferred (no native body-text surface in V1
  for EPUB; Readium WebView has no exposed layout geometry API)

## Typography panel (✅)

- Font family (default / serif / sans / dyslexic)
- Font size, line height, theme (light / sepia / dark)
- All applied via Readium `EpubPreferences`

## Reading anchor / position (✅)

- `AnchorRepository` (per-book last utterance locator) — used to mark
  reading position after TTS stops
- `LocatorRepository` (per-book current page)
- Both safe-serialized via `Locator.toJSON()` — no string interpolation

## CI infrastructure (✅, Sets 1-4 work)

### 9 preflight gates (`scripts/preflight.sh`)

1. `assembleDebug`
2. `testDebugUnitTest`
3. `detekt`
4. `ktlintCheck`
5. `check_banned_strings.sh` (XML + .kt two-pass)
6. `check_gaze_data_leak.sh`
7. `check_audio_session.sh`
8. `check_banned_deps.sh`
9. `check_release_logging.sh`

### Test totals

- 91 JVM unit tests (1 example + 90 security tests across 8 Group files)
- 6 instrumented tests (`androidTest/`)
- All planted-violation negative-test cycles verified

## Deferred (V2+) 🔲

- Cloud sync (Supabase planned)
- Annotations (highlights, notes)
- Retrieval / spaced repetition
- RSVP reader
- F5-class envelope-consumer features (paragraph tint, focus vignette,
  gaze-TTS soft pause)
- A11y V2 (chapter rotor with heading levels)
- STT captions (would require `RECORD_AUDIO` permission, currently
  forbidden by `tts_noMicrophonePermissionRequested`)
- DRM / Readium LCP adapter
- Foreground service (any introduction would require FLAG_SECURE
  re-evaluation per ADR-AND-R V2 trigger list)

## Recent material fix-ups (cross-set)

- 2026-05-16 — TtsNavigator leak on focus-denied path closed (Set 1 PR-C1
  fix-up, regression-test `tts_audioFocus_denied_closesNavigator`)
- 2026-05-16 — `@Volatile _ttsNavigator` added (Set 1 PR-C1 fix-up)
- 2026-05-16 — `GazeViewModel.CalibrationError` fallback rephrased and
  `e.message` channel dropped (Set 2 PR-F)
- 2026-05-16 — `GazeProviderImpl.kt:70` `Log.d` removed (Set 2 PR-E)
- 2026-05-16 — `GroupIJSecurityTest.stripComments` inline-tail strip
  harmonization (Set 3 chokepoint fix; closes recurring 3-audit finding)
- 2026-05-17 — `ADR-AND-A.md` "shipped in PR-C2" forward-reference cleaned
  up; `ADR-AND-J` dangling cross-reference annotated in `ADR-AND-R.md`

## Cross-branch follow-ups (Sprint 26 — split across two branches)

Trunk-side (`docs/cleanup-trunk-side`, ships immediately):

- `RULES.md` §"Gaze data" label corrected from `ADR-AND-H equivalent`
  to `ADR-AND-J equivalent` (H is STT-deferred; J is gaze ephemerality).
- `06-progress.md` PR-attribution corrected: ADR-AND-N closure is by
  PR-G (`tests/no-javascript-interface`, GroupA), not PR-H.
- `05-activeContext.md` §Open questions q2 marked closed.
- `DEVLOG.md` Sprint 26 entry describing both halves of the cleanup.

Track-A-side (`docs/cleanup-track-a-side`, built on `docs/adr-and-backfill`
so it carries Track A's 14 ADRs; ships after the Track C stack lands on
trunk so the cited tests/scripts are reachable):

- `ADR-AND-B.md` §"Known gap" — closure note pointing to `aa5b203`
  (`ci/banned-strings-extend-kotlin`, Sprint 24 Set 2 PR-F).
- `ADR-AND-I.md` §"Code markers" — citation added for
  `scripts/check_banned_deps.sh` (enforces Decision §3 no-analytics).
- `ADR-AND-N.md` §"Known gap" — closure note pointing to `106e8b9`
  (`tests/no-javascript-interface`, Sprint 25 Set 3 PR-G).
