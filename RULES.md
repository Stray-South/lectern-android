# Lectern Android — Engineering Rules

These rules mirror lectern-ios/RULES.md adapted for Kotlin/Android.
CI enforces all of them. Zero exceptions without an ADR entry.

## Code quality
- `kotlinOptions { jvmTarget = "17" }` on all targets
- Zero detekt errors on merge
- Zero ktlint errors on merge
- No force-unwrap `!!` — use `requireNotNull(msg)` or `?.let`
- No catching exceptions to silence them — fail loudly
- No `println` / `Log.d` left in shipped code (debug builds OK)
- No `TODO` / `FIXME` / `HACK` comments in non-test code

## Threading
- All Room operations on `Dispatchers.IO`
- All Compose UI updates on `Dispatchers.Main`
- No `runBlocking` in production code paths
- GazeProvider uses `Dispatchers.Default.limitedParallelism(1)`

## Privacy (non-negotiable)
- No telemetry SDK: no Firebase Analytics, Crashlytics (default),
  Mixpanel, Amplitude, Segment, Bugsnag, Datadog
- No analytics SDK in build.gradle.kts
- No raw gaze coordinates written to Room, DataStore, or any file
- No Room entity names matching: face, eye, gaze, lookAt
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
