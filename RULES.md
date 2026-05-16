# Lectern Android — Engineering Rules

These rules mirror lectern-ios/RULES.md adapted for Kotlin/Android.
CI enforces all of them. Zero exceptions without an ADR entry.

## Code quality
- `compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }` on all targets (AGP 9.x; `kotlinOptions` removed)
- Zero detekt errors on merge
- Zero ktlint errors on merge
- No force-unwrap `!!` — use `requireNotNull(msg)` or `?.let`
- No catching exceptions to silence them — fail loudly
- No `println` / `Log.d` left in shipped code (debug builds OK)
- No `TODO` / `FIXME` / `HACK` comments in non-test code

## Threading
- `suspend` DAO functions are main-safe — Room 2.1+ dispatches internally; do NOT wrap in `withContext(Dispatchers.IO)` (adds a redundant context switch)
- Non-suspend Room calls (blocking transactions, raw queries) MUST run on `Dispatchers.IO`
- All Compose UI updates on `Dispatchers.Main`
- No `runBlocking` in production code paths
- GazeProvider uses `Dispatchers.Default.limitedParallelism(1)`

## Audio (ADR-AND-A)
- `com.straysouth.lectern.audio.AudioSessionCoordinator` is the **sole** file
  permitted to call `AudioManager.requestAudioFocus`,
  `AudioManager.abandonAudioFocusRequest`, or construct `AudioFocusRequest.Builder`.
- All other code routes audio-focus transitions through the coordinator
  (`acquireForTts(onLoss)`, `reacquire()`, `release()`).
- Direct `AudioManager` focus calls outside the coordinator fail
  `scripts/check_audio_session.sh` and the JVM tests
  `tts_audioFocus_ownedByAudioSessionCoordinator` +
  `tts_viewModelDelegatesToAudioSessionCoordinator`.
- Sprint 20 invariants are coordinator-internal:
  `AUDIOFOCUS_GAIN_TRANSIENT` (not MAY_DUCK); return-value check;
  resume re-acquires before `nav.play()`.

## Privacy (non-negotiable)
- No telemetry SDK: no Firebase Analytics, Crashlytics (default),
  Mixpanel, Amplitude, Segment, Bugsnag, Datadog
- No analytics SDK in build.gradle.kts
- No raw gaze coordinates written to Room, DataStore, or any file
- No Room entity names matching: face, eye, gaze, lookAt
- Never annotate a `@Entity` class with `@Serializable` (KSP2 bug #1896 — use a separate DTO class)
- Calibration weights: DataStore only, allowBackup = false
- allowBackup = false in AndroidManifest.xml — no exceptions

## Accessibility
- Every `@Composable` screen has a `semantics {}` block
- TalkBack audit must pass before merge on any screen PR
- All animations check `LocalAccessibilityManager.current.isAnimationEnabled`
- Minimum touch target: 48×48dp (Android HIG)

## AuDHD copy
- No streak, consecutive, wrong, incorrect, failed, missed,
  great job, keep it up, daily goal, 🔥, 🏆, ⭐ in strings.xml
- CI grep enforced (matches lectern-ios check_banned_strings.sh)
- No therapeutic claims in any user-visible string

## Phase boundary
- No Phase 2 Compose screens until Phase 1 main merge + all
  acceptance tests green
- No mediapipe/opencv/mlkit in build.gradle.kts until Sprint 5+
- No Supabase dependency until V2 sync design is approved

## Gaze data (ADR-AND-H equivalent)
- No write path to Room, DataStore, or network for raw gaze coords
- Calibration: DataStore<Preferences> with allowBackup = false
- CI grep: no Room entity or DataStore key matching face|eye|gaze|lookAt
