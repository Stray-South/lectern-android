# 04 — Tech Context

## Build & SDK

- Kotlin 2.3.21 (KSP 2.3.7)
- AGP 9.1.1 → Gradle 9.5.0
- `minSdk` 26 (Android 8.0 Oreo)
- `targetSdk` 36, `compileSdk` 36
- `compileOptions { JavaVersion.VERSION_17 }` on all targets
- `isCoreLibraryDesugaringEnabled = true` (`com.android.tools:desugar_jdk_libs:2.1.4`)

## Compose

- `compose-bom` 2026.04.01 → Material3 + Material Icons (core + extended)
- `activity-compose` 1.9.3
- `lifecycle-runtime-ktx` 2.8.7
- `fragment-compose` 1.8.9 (host for Readium `EpubNavigatorFragment`)
- `core-ktx` 1.15.0
- `appcompat` 1.7.0

## Persistence

- Room 2.8.4 (`runtime`, `ktx`, `compiler` via KSP, `testing`)
- DataStore Preferences 1.2.1
- Schema JSON exports in `app/schemas/` wired into `androidTest` assets

## Readium toolkit (exact pins, no version range)

- `readium-shared`, `readium-streamer`, `readium-navigator`,
  `readium-navigator-media-tts`: 3.1.2
- `readium-adapter-pdfium-document` 3.1.2 (declared in catalog;
  currently unused since PDF rendering uses Android `PdfRenderer`)
- `webkit` 1.11.0 (AndroidX WebView)
- `BlockingHttpClient` (custom) wraps Readium's network entry points

## Camera + ML

- CameraX 1.4.0 (`core`, `camera2`, `lifecycle`, `view`)
- MediaPipe Tasks Vision 0.10.35
- Model: `face_landmarker.task` (loaded from `app/src/main/assets/`,
  bootstrapped by `scripts/download_models.sh`, gitignored in repo)

## Numerics

- EJML 0.44.0 (ridge regression for gaze calibration)

## File handling

- zip4j 2.11.6 (CBZ extraction — `getInputStream()` only; never
  `extractFile` / `extractAll` / `extractEntry`, pinned by
  `supply_zip4j_noExtractionApiCalls_inMainSources`)
- junrar 7.5.7 (CBR)
- Coil 3.1.0 (`coil-compose`, `coil-android`) — cover image rendering

## Coroutines / serialization

- `kotlinx-coroutines-android` 1.10.2
- `kotlinx-serialization-json` 1.8.1

## CI tooling

- detekt 1.23.8 (config: `config/detekt/detekt.yml`)
- ktlint plugin (`org.jlleitschuh.gradle.ktlint`) 12.1.1
- 7 shell scripts in `scripts/` for grep-based gates
- BSD-awk-compatible regex (macOS dev machines) — explicit
  `[^A-Za-z0-9_]` instead of `\b` / `\<`

## Permissions

- `android.permission.INTERNET` — declared but unused in V1; forward-
  compat for future sync. Asserted by
  `platform_internetPermission_noActualNetworkCalls_inMainSources`
- `android.permission.CAMERA` — runtime; required for gaze, gated by
  user opt-in
- `<uses-feature android:name="android.hardware.camera.any" required="true"/>`
- `<uses-feature android:name="android.hardware.camera.front" required="false"/>` —
  tablets without a front camera reach the Play Store listing

## Excluded by RULES.md §Privacy

No Firebase / Crashlytics / Mixpanel / Amplitude / Segment / Bugsnag /
Datadog / Sentry / AppsFlyer / Adjust. Enforced by
`scripts/check_banned_deps.sh` over `app/build.gradle.kts` and
`gradle/libs.versions.toml`.

## Working directory for tests

`./gradlew testDebugUnitTest` runs with CWD = `app/`. Test helpers
(`sourceFile()`, `manifestXml()`) assume this — they construct paths
like `File("src/main/AndroidManifest.xml")`.
