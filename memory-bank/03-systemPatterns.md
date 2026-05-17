# 03 — System Patterns

## Compose UI layer

- Single `MainActivity` hosting Compose NavGraph
- Every `@Composable` screen has a `semantics {}` block (RULES.md §Accessibility)
- All animations branch on `LocalAccessibilityManager.current.isAnimationEnabled`
- All durations ≤ 200 ms (pinned by `audhd_animations_allTweenDurations_atMost200ms`)
- No auto-advance / timer-driven navigation (`audhd_noAutoAdvance_*`)
- Touch targets ≥ 48×48 dp
- TalkBack audit must pass before any screen-PR merges

## Room persistence

- 2 entities: `Book` (table `books`), `ReadingProgress` (table `reading_progress`)
- Schema version 2 — v1→v2 migration adds `format` column with `EPUB` default
- `AppDatabase.kt` does **not** call `fallbackToDestructiveMigration` — pinned
  by `appDatabase_builderDoesNotCallFallbackToDestructiveMigration`
- Schema JSON exports in `app/schemas/`, wired into `androidTest` assets via
  `sourceSets.androidTest.assets.srcDirs("$projectDir/schemas")`
- Identity-hash stability tests pin schema bytes per version
- No `@Entity` may use a table name matching `face|eye|gaze|lookAt`
  (enforced by `scripts/check_gaze_data_leak.sh`)

## DataStore preferences

- 5 stores: typography, tts, focusBand, anchor, calibration
- All excluded from cloud backup via `data_extraction_rules.xml`
  `<cloud-backup>` blanket exclusion
- Calibration store + tts speed prefs additionally excluded from D2D
  transfer (device-specific calibration is meaningless on a different
  camera; tts speed depends on installed engine)
- Per-feature defaults stored in prefs classes; verified by
  `audhd_gazeOverlay_defaultOff_inPrefsClass` + `_inRepository`

## Readium WebView (EPUB)

- `EpubNavigatorFragment` host (Readium 3.1.2) — runs an Android `WebView`
- `EpubBlockingWebViewClient` (`ui/reader/EpubBlockingWebViewClient.kt`)
  is the sole gate:
  - Predicate host check (only `https://readium/` resolves)
  - `shouldOverrideUrlLoading` scheme denylist
  - Registration guard ordering — client set before any URL loads
  - `allowContentAccess = false` unconditional
- `BlockingHttpClient` wired into both `AssetRetriever` AND
  `DefaultPublicationParser` — refuses all outbound requests
- No `evaluateJavascript` in main sources (`epub_noDirectEvaluateJavascript_inMainSources`)
- No `addJavascriptInterface` in main sources (`epub_noJavascriptInterface_inMainSources`)
- Locator round-trip via `Locator.toJSON()` — no string interpolation

See ADR-AND-N (on `docs/adr-and-backfill` branch) for the full firewall
description.

## CameraX + MediaPipe (Gaze)

- `GazeProvider` interface; `GazeProviderImpl` is sole implementation
- CameraX front camera, `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`,
  `OUTPUT_IMAGE_FORMAT_RGBA_8888`
- MediaPipe `FaceLandmarker` `LIVE_STREAM` mode, `numFaces = 1`,
  GPU delegate, model from `app/src/main/assets/face_landmarker.task`
- Raw `Bitmap` frames + iris UV are in-memory only — never logged, never
  persisted (`gaze_rawIrisUV_neverLoggedInFullGazeModule`)
- Only the 13-double `CalibrationResult` (6-float weightsX + 6-float
  weightsY + 1 meanErrorPx) persists, to DataStore at
  `files/datastore/calibration_prefs.preferences_pb`
- 6-feature ridge regression `[u, v, u², v², uv, 1]`, λ = 1e-4, EJML solver
- LOO-CV mean error reported (not in-sample residual)
- `Dispatchers.Default.limitedParallelism(1)` — confined dispatcher for
  all state mutation
- Thermal throttle pauses analysis (clears analyzer, not just flag —
  `gaze_pauseAnalysis_clearsAnalyzer_notJustSetsState`)
- Permission revocation handled via `SecurityException` catch — gaze
  disables cleanly

See ADR-AND-E (on `docs/adr-and-e-gaze-stack` branch) for the gaze-stack
decision and ADR-AND-J (on `docs/adr-and-backfill` branch) for
ephemerality.

## TTS + Audio focus

- Readium `TtsNavigator` wraps `android.speech.tts.TextToSpeech`
- `AudioSessionCoordinator` (`audio/AudioSessionCoordinator.kt`) is the
  sole owner of `AudioManager` focus state (ADR-AND-A)
- API: `acquireForTts(onLoss)` / `reacquire()` / `release()` — idempotent
- `AUDIOFOCUS_GAIN_TRANSIENT` (not `MAY_DUCK` — Sprint 20 invariant);
  return-value-checked; resume re-acquires before `nav.play()`
- `_ttsNavigator` is `@Volatile` because the audio-focus-thread callback
  reads it from `pauseTts()`
- TTS navigator is closed on focus-denied path (regression test:
  `tts_audioFocus_denied_closesNavigator`)
- Enforced repo-wide by `scripts/check_audio_session.sh` + two JVM tests

## Threading invariants (RULES.md §Threading)

- `suspend` DAO functions are main-safe — Room 2.1+ dispatches
  internally; do NOT wrap in `withContext(Dispatchers.IO)`
- Non-suspend Room calls (blocking transactions, raw queries) MUST run
  on `Dispatchers.IO`
- All Compose UI updates on `Dispatchers.Main`
- No `runBlocking` in production code paths
- `GazeProvider` uses `Dispatchers.Default.limitedParallelism(1)`

## CI gate ordering

`preflight.sh` runs 9 gates: build, unit-tests, detekt, ktlint,
banned-strings, gaze-leak, audio-session, banned-deps, release-logging.
Every gate has a negative-test (planted-violation) verifying detection.
