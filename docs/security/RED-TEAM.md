# LECTERN-ANDROID-RED-TEAM.md — Security & Adversarial Test Suite
# Lectern Android — AuDHD-first reading app

> Security and adversarial test coverage for a local-only Android reading app
> built on Kotlin + Jetpack Compose + Readium Kotlin Toolkit 3.1.2 +
> Android TTS + Room 2.8.4 + DataStore 1.2.1 + CameraX 1.4.0 + MediaPipe
> Tasks Vision 0.10.35.
>
> Primary standard: OWASP Mobile Application Security Verification Standard v2
> (MASVS v2) — https://mas.owasp.org/MASVS/
>
> Lectern Android has NO LLM, NO backend, NO cloud sync (V1). No analytics SDK.
> OWASP LLM Top 10 does NOT apply.
>
> Primary attack surfaces:
> 1. EPUB3 content rendering via Readium Kotlin + Android WebView
> 2. File import from untrusted sources (EPUB / PDF / CBZ / CBR)
> 3. Room DB integrity and migration safety
> 4. DataStore and local storage exposure (no cloud sync in V1)
> 5. Gaze tracking pipeline — camera data + calibration weights (Android-only)
> 6. Readium Kotlin Toolkit + zip4j + junrar + EJML + MediaPipe supply chain
> 7. AuDHD-specific UI safety regressions (Compose)
> 8. Android platform security (Intents, Backup, Network Security Config)
>
> iOS tests that do NOT apply to Android: iCloud/CloudKit (no sync in V1),
> WKWebView content rules API, NSFileProtection, AVSpeechSynthesizer,
> Swift 6 actor isolation, NSPersistentCloudKitContainer.

---

## Status legend

| Symbol | Meaning |
|---|---|
| ✅ | Test exists and passes |
| ⚠️ | Test exists but documents a known issue |
| 🔴 | Test does not yet exist — needs implementation |
| 🔍 | Needs source verification before test can be written |
| ✓ | Verified from source — confirmed safe / confirmed risk |

---

## Confirmed implementation facts (from source research)

| Property | Status | Source |
|---|---|---|
| `android:allowBackup="false"` | ✓ Safe | AndroidManifest.xml |
| `data_extraction_rules.xml` excludes all sharedpref, database, file from cloud backup | ✓ Safe | data_extraction_rules.xml |
| Calibration weights excluded from D2D device transfer | ✓ Safe | data_extraction_rules.xml |
| Reading position / focus band prefs NOT excluded from D2D transfer | 🔍 Intentional or gap? | data_extraction_rules.xml |
| No custom URL scheme registered | ✓ Safe | AndroidManifest.xml |
| Only LAUNCHER intent filter; `exported="true"` on MainActivity only | ✓ Safe | AndroidManifest.xml |
| `INTERNET` permission declared but unused in V1 (comment: "for future Supabase sync") | ⚠️ Risk | AndroidManifest.xml |
| No `res/xml/network_security_config.xml` present | ⚠️ No explicit ATS equivalent | filesystem |
| Gaze logs: only error/state messages — no iris coordinates or weights logged | ✓ Safe | GazeProviderImpl.kt, GazeViewModel.kt |
| CalibrationResult stores ridge regression weights (not raw iris UV) | ✓ Correctly abstracted | CalibrationRepository.kt |
| Raw iris UV and calibration weights never written to logcat | ✓ Confirmed | All gaze files |
| Room uses explicit migration (MIGRATION_1_2), no `fallbackToDestructiveMigration()` | ✓ Safe | AppDatabase.kt |
| Readium Kotlin 3.1.2 version pin | ✓ Pinned in version catalog | libs.versions.toml |
| zip4j 2.11.6 + junrar 7.5.7 version pins | ✓ Pinned | libs.versions.toml |
| Android WebView JS config in Readium Kotlin | 🔍 Not visible in app source — inside Readium | N/A |
| `check_gaze_data_leak.sh` CI prevents gaze terms in entity names and DataStore keys | ✓ Active | scripts/ |

---

## Section A — EPUB3 content injection via Readium Kotlin + Android WebView

> Readium Kotlin renders EPUB3 content inside an `EpubNavigatorFragment` backed
> by an Android WebView. EPUB3 supports JavaScript, CSS, SVG, audio, video, and
> MathML. A malicious EPUB is the primary injection surface.
>
> MASVS-PLATFORM-1: The app uses WebViews securely.
> References: CVE-2021-40870 (Readium-2 path traversal), EPUB3 W3C Security
> Considerations, Android WebView security hardening guide.

**A.1** `epub_javascriptExecutionDisabledOrSandboxed` 🔴
- MASVS: MASVS-PLATFORM-1
- Attack: Open an EPUB3 file containing JavaScript that attempts `window.location = "intent://..."`, `fetch("https://evil.com")`, or `document.cookie` access.
- Pass: Readium Kotlin's internal WebView has JavaScript disabled, OR a `WebViewClient.shouldOverrideUrlLoading` intercepts and blocks non-local navigation. No JS execution reaches external endpoints.
- 🔍 Verify: Readium Kotlin 3.1.2 WebView configuration — `WebSettings.javaScriptEnabled`? Does it use `WebMessageListener` or `addJavascriptInterface`? Check Readium source at `readium-navigator/src/main/java/org/readium/r2/navigator/epub/`.
- Note: Readium may enable JS for accessibility features (MathML interactive content). If intentional, document scope and verify sandbox.

**A.2** `epub_externalResourceLoadingBlocked` 🔴
- MASVS: MASVS-PLATFORM-1 · MASVS-NETWORK-1
- Attack: EPUB3 content contains `<img src="https://tracker.evil.com/pixel.png">`. Verify no outbound network request is made.
- Pass: `WebViewClient.shouldInterceptRequest()` or `WebSettings` blocks all external loads. Verify with Android Network Profiler / Wireshark during test.
- 🔍 Verify: Does Readium Kotlin register a `WebViewClient` that intercepts non-`content://` or non-`file://` scheme requests?

**A.3** `epub_intentSchemeInjection` 🔴
- MASVS: MASVS-PLATFORM-3
- Attack: EPUB3 content contains `<a href="intent://settings#Intent;scheme=android-app;package=com.android.settings;end">` or `<a href="market://details?id=com.evil.app">`. These are Android-specific URL schemes that Android WebView will fire as Intents if not blocked.
- Pass: `WebViewClient.shouldOverrideUrlLoading()` intercepts non-EPUB-local navigation. `intent://` and `market://` scheme links are blocked and not dispatched as Android Intents.
- Android-specific: iOS has no equivalent `intent://` scheme risk.

**A.4** `epub_cssInjection_noNativeUiOverlay` 🔴
- MASVS: MASVS-PLATFORM-1
- Attack: EPUB3 CSS contains `position: fixed; top: 0; z-index: 99999; width: 100vw; height: 100vh` overlaying the content. In Android WebView, CSS is scoped to the WebView viewport — Compose UI above it cannot be overlaid. Verify.
- Pass: CSS injection stays within WebView bounds. The Compose `ReaderOverlay` (toolbar, TtsBar, GazeFocusBandOverlay) cannot be obscured by EPUB CSS.
- Note: This is lower risk on Android than iOS because the WebView is a View contained within the Fragment, not a full-screen sheet.

**A.5** `epub_svgXssInjection` 🔴
- MASVS: MASVS-PLATFORM-1
- Attack: EPUB3 content contains inline SVG with `<script>` tag or `onload` attribute.
- Pass: SVG scripts do not execute. If JS is globally disabled (A.1), this is covered — verify SVG-specific handling independently.

**A.6** `epub_malformedContainer_noCrash` 🔴
- MASVS: MASVS-CODE-4
- Attack: Import an EPUB with: malformed `container.xml`, missing OPF spine, zero-byte content documents, intentionally corrupt ZIP structure.
- Pass: Readium parser fails gracefully. `LibraryViewModel.importBook()` surfaces the error via `_importError` StateFlow → Snackbar. No crash. No corruption of existing Room records.
- Source: AFL/libFuzzer patterns for ZIP/XML parsers.

**A.7** `epub_annotationXssViaCfiString` 🔴
- MASVS: MASVS-PLATFORM-1 · MASVS-STORAGE-1
- Attack: Craft an EPUB with a CFI locator string containing characters that might be interpolated or executed when restored (e.g., `epubcfi(/6/4[ch01]!/4/2/1,<script>alert(1)</script>,/1:0)`). Verify CFIs are stored and restored as opaque strings with no eval path.
- Pass: CFI strings from `LocatorRepository` are stored as DataStore string values and passed to Readium's `locate()` API — no eval or HTML interpolation.

---

## Section B — File import from untrusted sources

> `LibraryViewModel.importBook()` accepts EPUB, PDF, CBZ, and CBR files via
> Android's `ACTION_OPEN_DOCUMENT` picker. Content is delivered as a `content://`
> URI. All imported files are untrusted external content.
>
> EPUB extraction: Readium Kotlin (internal).
> CBZ extraction: zip4j 2.11.6.
> CBR extraction: junrar 7.5.7.
> PDF: Android PdfRenderer (system).

**B.1** `fileImport_zipBombEpub` 🔴
- MASVS: MASVS-CODE-4
- Attack: Import an EPUB that is a ZIP bomb (e.g., 42.zip variant that expands to gigabytes). Verify Readium Kotlin enforces a decompressed size limit.
- Pass: Import fails with a clear error message. No storage exhaustion. No ANR.
- 🔍 Verify: Does Readium Kotlin's `DefaultStreamer` or `ReadiumSharedStreamer` enforce a maximum decompressed entry size? Check `readium-shared` source.

**B.2** `fileImport_zipBombCbz` 🔴
- MASVS: MASVS-CODE-4
- Attack: Import a CBZ that is a ZIP bomb. zip4j 2.11.6 is used — verify it enforces a size limit.
- Pass: `ComicsReaderViewModel` fails gracefully. No storage exhaustion. `UnsupportedRarV5Exception` catch pattern suggests error handling exists — verify it covers size explosions too.
- Android-specific: zip4j does not have a built-in decompressed-size limit by default. Manual stream limit may be needed.

**B.3** `fileImport_pathTraversal_cbz` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: CBZ archive contains entries with paths like `../../data/data/com.straysouth.lectern/databases/lectern.db` or `../files/datastore/calibration_prefs.preferences_pb`. Verify zip4j extraction does not write outside the app's cache directory.
- Pass: `ComicsReaderViewModel.uriToFile()` copies to `context.cacheDir` — verify zip4j entry paths are validated against the target directory before extraction. CWE-22.
- Android-specific: iOS uses EPUB-only path; Android has the additional zip4j/junrar extraction path.

**B.4** `fileImport_pathTraversal_epub` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: EPUB ZIP contains entries with paths `../../databases/lectern.db`. Verify Readium Kotlin's streamer validates all entry paths.
- Pass: No file is written outside the app's designated book storage. Readium's `DefaultStreamer` correctly rejects path traversal entries.
- Source: CVE-2021-40870 (Readium-2 path traversal) — verify fix is present in Readium Kotlin 3.1.2.

**B.5** `fileImport_contentUri_pathTraversal` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: A malicious app provides a `content://` URI whose `DISPLAY_NAME` column returns `../../lectern.db`. If `LibraryViewModel` uses the display name to construct a file path, it could write outside the app sandbox.
- Pass: `LibraryViewModel` uses a deterministic `UUID.nameUUIDFromBytes` ID for book storage — not the display name. Display name is only used for `title` metadata. Verify no file path is constructed from `DISPLAY_NAME`.
- Android-specific: iOS does not use `content://` URIs.

**B.6** `fileImport_nonEpubMasqueradingAsEpub` 🔴
- MASVS: MASVS-CODE-4
- Attack: Import a file with `.epub` extension containing binary executable or malformed XML content.
- Pass: Readium validates EPUB container structure (META-INF/container.xml, `application/epub+zip` mimetype file). Non-EPUB content is rejected with the `import_error_unsupported_format` string.
- 🔍 Verify: `LibraryViewModel.detectFormat()` uses MIME type and extension — does it also validate EPUB magic bytes or container structure?

**B.7** `fileImport_duplicateBook_noDataCorruption` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: Import the same EPUB twice, or two EPUBs with identical metadata. Verify Room `BookDao.upsert()` handles the duplicate correctly with no silent data loss.
- Pass: Duplicate detection based on deterministic `UUID.nameUUIDFromBytes(uri.toString().toByteArray(Charsets.UTF_8))` — same URI → same ID → upsert overwrites. Different URI, same content → separate books. Verify the upsert semantics match user expectations.

---

## Section C — Room database integrity and migration safety

> Room 2.8.4 backs all book metadata and reading progress.
> No cloud sync in V1 — Room is the authoritative source of truth.
> `exportSchema = true` — schema JSON is committed.

**C.1** `room_migration_v1v2_noDataLoss` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: Install v1 (schema without `format` column). Migrate to v2. Verify all existing books, reading progress records survive. Verify `format` defaults to `'EPUB'` for pre-migration rows.
- Pass: All pre-v2 `books` rows accessible post-migration with `format = 'EPUB'`. Zero dropped records. `schemas/2.json` matches the live schema.
- Source: `MIGRATION_1_2` in `AppDatabase.kt`.

**C.2** `room_noFallbackToDestructiveMigration` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: Install a version with a schema version that has no defined migration (simulating a future sprint skip). Verify Room throws `IllegalStateException` rather than silently destroying user data.
- Pass: `fallbackToDestructiveMigration()` is NOT present in `AppDatabase.getInstance()`. Room throws `IllegalStateException` — testable by calling `Room.databaseBuilder` with a version bump and no migration.
- Source: `AppDatabase.kt` — confirmed: no `fallbackToDestructiveMigration()` call.

**C.3** `room_concurrentWrite_noConflict` 🔴
- MASVS: MASVS-CODE-3
- Attack: Trigger simultaneous Room writes from two coroutines: `BookDao.upsert(book)` and `ReadingProgressDao.upsertProgress(progress)` with a shared `bookId`. Verify no `SQLiteConstraintException` or `IllegalStateException`.
- Pass: Room DAO operations use `suspend` on correct dispatchers (main-safe, Room internally dispatches to IO). No conflict. All operations complete.

**C.4** `room_deleteBook_cascadeProgress` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: Delete a book. Verify `ReadingProgress` rows with the deleted `bookId` are also deleted. No FK cascade is defined in the schema — `LibraryViewModel.deleteBook()` calls `ReadingProgressDao.deleteByBookId` explicitly.
- Pass: After `deleteBook()`, `ReadingProgressDao.getProgress(deletedBookId)` returns null. No orphaned progress records.
- Source: `LibraryViewModel.deleteBook()` — confirmed explicit delete call.

**C.5** `room_schemaJson_committed_notDrifted` 🔴
- MASVS: MASVS-CODE-3
- Attack: Modify a Room entity without updating `version` or migration. Verify the CI build catches the schema drift via `schemas/*.json` mismatch.
- Pass: `assembleDebug` fails with a schema verification error when entity fields change without a version bump. `schemas/2.json` reflects the current live schema.

---

## Section D — DataStore and local storage security

> DataStore 1.2.1 (Preferences) backs: typography, TTS speed, focus band,
> calibration weights, reading positions (CFI locators), anchor locators,
> PDF/Comics page positions.
>
> No cloud sync in V1. `android:allowBackup="false"` confirmed in Manifest.
> `data_extraction_rules.xml` excludes all sharedpref/database/file from cloud backup.

**D.1** `datastore_calibrationWeights_notInDeviceTransfer` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: Perform a D2D (device-to-device) transfer to a new device. Verify calibration weights (`calibration_prefs.preferences_pb`) are NOT transferred — they encode device-specific camera geometry and would produce wrong gaze predictions on a different device.
- Pass: `data_extraction_rules.xml` explicitly excludes `calibration_prefs.preferences_pb` from `<device-transfer>`. Verify via ADB backup + manual inspection.
- Source: `data_extraction_rules.xml` — confirmed exclusion.

**D.2** `datastore_readingPosition_deviceTransfer_intentional` 🔍
- MASVS: MASVS-STORAGE-1
- Attack / Question: Reading positions (CFI locators), focus band prefs, and anchor locators are NOT excluded from D2D transfer. Is this intentional?
- Pass if intentional: Document the decision — reading positions following the user to a new device is a feature. Verify the transferred CFIs are valid on the new device (book content must also transfer or be re-imported).
- Pass if unintentional: Exclude the relevant DataStore files from `<device-transfer>`.
- 🔍 Design decision gap — needs explicit answer before beta.

**D.3** `datastore_calibrationWeights_notInLogcat` 🔴
- MASVS: MASVS-STORAGE-2
- Attack: Run a full calibration session. Capture Logcat output (`adb logcat -d`). Verify no calibration weight values (`weights_x`, `weights_y` double arrays) appear in any log line.
- Pass: Zero Logcat lines containing numeric calibration weight arrays. Regression test to prevent future regressions.
- Source: Verified from source — all `Log.*` calls in gaze module use only status messages.

**D.4** `datastore_noSensitiveDataInAutoBackup` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: Enable Google Auto Backup (temporarily override `allowBackup="false"` in test). Trigger a backup. Examine the backup to verify no book content, calibration weights, or reading positions are included.
- Pass: `allowBackup="false"` prevents all Auto Backup in production. This test validates the exclusion rule and prevents future accidental re-enablement.
- Source: `android:allowBackup="false"` confirmed.

**D.5** `datastore_nocleartextStorageOfBookContent` 🔴
- MASVS: MASVS-STORAGE-1 · MASVS-CRYPTO-1
- Attack: On a rooted device, examine the app's private storage (`/data/data/com.straysouth.lectern/`). Verify book content is stored in app-private storage, not on external storage.
- Pass: All imported files stored under `context.filesDir` or `context.cacheDir` — not `Environment.getExternalStorageDirectory()`. No book content is world-readable.
- 🔍 Verify: `ComicsReaderViewModel.uriToFile()` writes to `context.cacheDir` — correct. Verify EPUB/PDF import path also uses `filesDir`, not external.

---

## Section E — TTS / Android speech engine privacy

> Android TTS uses the device's installed TTS engine (e.g., Google TTS).
> `TtsNavigatorFactory` from Readium Kotlin handles word-level sync.
> Samsung One UI 7+ may have no TTS engine — surfaced via `TtsUiState.EngineUnavailable`.

**E.1** `tts_stopsOnAppBackground` 🔴
- MASVS: MASVS-PLATFORM-2
- Attack: Start TTS playback. Background the app. Verify TTS stops unless background audio is explicitly configured.
- Pass: Android TTS stops when audio focus is released on `onPause()`. No silent background audio playback.
- 🔍 Verify: Does `TtsNavigatorFactory` properly release audio focus on Activity pause? No `FOREGROUND_SERVICE` or `WAKE_LOCK` declared in Manifest — background audio should stop automatically.

**E.2** `tts_annotationContentNotSpokenWithoutIntent` 🔴
- MASVS: MASVS-PLATFORM-2
- Attack: Add a highlight/annotation to a passage. Start TTS at that passage. Verify annotation text is NOT read aloud.
- Pass: Readium `TtsNavigator` reads `Publication` content only. Annotation text requires a separate explicit action.
- Note: Lectern Android V1 has no annotation feature — this test is a regression guard for V2.

**E.3** `tts_noMicrophonePermissionRequested` 🔴
- MASVS: MASVS-PLATFORM-1
- Attack: Audit `AndroidManifest.xml` and runtime permission flow. Verify `RECORD_AUDIO` is not declared and never requested.
- Pass: No `uses-permission RECORD_AUDIO` in Manifest. TTS is output-only.
- Source: Manifest confirmed — only `INTERNET` and `CAMERA` declared.

**E.4** `tts_engineUnavailable_noSilentFailure` 🔴
- MASVS: MASVS-PLATFORM-2 (UX safety)
- Attack: Simulate `ttsFactory == null` (no TTS engine installed). Tap Play in `TtsBar`.
- Pass: `TtsUiState.EngineUnavailable` emitted. `TtsBar` displays `tts_engine_unavailable` string with dismiss button. No silent no-op.
- Source: Sprint 12 — `EngineUnavailable` state confirmed in source.

---

## Section F — Supply chain (Readium Kotlin + zip4j + junrar + EJML + MediaPipe)

> Lectern Android has 5 non-Jetpack external dependencies with meaningful attack surface:
> Readium Kotlin Toolkit 3.1.2, zip4j 2.11.6, junrar 7.5.7, EJML 0.44.0,
> MediaPipe Tasks Vision 0.10.35.
>
> All version-pinned in `gradle/libs.versions.toml`.

**F.1** `readium_versionPinned_notFloating` 🔴
- MASVS: MASVS-CODE-5
- Attack: Verify `readium = "3.1.2"` in `libs.versions.toml` is an exact pin, not `3.+` or `latest.release`.
- Pass: Version string `"3.1.2"` is exact. All 5 external dependency versions are exact pins.
- Source: Confirmed `readium = "3.1.2"`, `zip4j = "2.11.6"`, `junrar = "7.5.7"`, `ejml = "0.44.0"`, `mediapipe = "0.10.35"` in version catalog.

**F.2** `readium_cve_202140870_notAffected` 🔴
- MASVS: MASVS-CODE-5
- Attack: CVE-2021-40870 (Readium-2 path traversal in EPUB import) — verify Readium Kotlin 3.1.2 is not affected.
- Pass: No CVE-2021-40870 equivalent in Readium Kotlin 3.1.2. NVD check clean. Any open CVEs documented in `SECURITY.md`.

**F.3** `readium_noUnexpectedNetworkAccess` 🔴
- MASVS: MASVS-NETWORK-1
- Attack: Open a book, read pages, activate TTS. Capture all traffic via Android Network Profiler. Verify zero outbound requests from `org.readium.*`.
- Pass: All EPUB assets load from local storage only. No Readium analytics, CDN, or telemetry endpoints contacted.

**F.4** `zip4j_pathTraversalPrevented` 🔴
- MASVS: MASVS-STORAGE-1 · MASVS-CODE-5
- Attack: Verify `ComicsReaderViewModel` validates `ZipFile` entry names before extraction. zip4j 2.11.6 does not automatically reject `../` sequences — the caller must validate.
- Pass: All entry names are stripped to filename only (`File(entryName).name`) or validated against the target directory before `extractFile()`. CWE-22 mitigated at app level.

**F.5** `mediapipe_noFrameDataExfiltration` 🔴
- MASVS: MASVS-NETWORK-1 · MASVS-STORAGE-2
- Attack: Run gaze tracking for a full session. Capture network traffic. Verify MediaPipe Tasks Vision 0.10.35 makes no outbound requests.
- Pass: Zero network requests from `com.google.mediapipe.*` during gaze tracking. `face_landmarker.task` model file is bundled as an asset.

**F.6** `gradleDependencyVerification_checksums` 🔴
- MASVS: MASVS-CODE-5
- Attack: Verify `gradle/verification-metadata.xml` is present and covers external deps, or document why it is not required.
- Pass: Either checksums committed and verified in CI, or decision not to use them is explicitly documented with rationale.
- 🔍 Verify: Does `gradle/verification-metadata.xml` exist?

---

## Section G — AuDHD-first safety regressions (Compose)

**G.1** `audhd_noAutoPlayAnimation_onBookOpen` 🔴
- Attack: Open any book. Verify no Compose animation exceeds 200ms and no looping animation begins without user action.
- Pass: All `tween()` durations ≤ 200ms. Confirmed from source: `tween(200)` used consistently in `ReaderOverlay.kt`.

**G.2** `audhd_noAutoAdvancePages` 🔴
- Attack: Open a book. Wait 60 seconds without interaction. Verify no auto-advance, auto-scroll, or auto-dismiss.
- Pass: No `LaunchedEffect` or `Handler.postDelayed` driving navigation without explicit user input.

**G.3** `audhd_errorMessagesInlineNotAutoDismissed` 🔴
- Attack: Trigger an import error (malformed EPUB). Verify the Snackbar error message does not auto-dismiss before the user can read it.
- Pass: `snackbarHostState.showSnackbar(msg)` uses `SnackbarDuration.Short` (4s).
- 🔍 Design question: `Short` (4s) may be insufficient for AuDHD users. `Indefinite` with explicit dismiss is preferable for error messages — requires decision before beta.

**G.4** `audhd_noFlashOnThemeChange` 🔴
- Attack: Switch reading theme (Light → Sepia → Dark) via the Typography Panel. Verify no flash or abrupt color change.
- Pass: Theme transition applies via Readium CSS injection — verify crossfade rather than flash.

**G.5** `audhd_gazeFocusBandDefaultOff` 🔴
- Attack: Open a book with gaze enabled and no explicit user preference set. Verify the Focus Band overlay does NOT appear by default.
- Pass: `FocusBandPrefs.gazeOverlayEnabled = false` confirmed default. No overlay on first launch.
- Source: `FocusBandPrefs.kt` confirmed default.

**G.6** `audhd_calibrationOverlayDismissable_noTrap` 🔴
- Attack: Open CalibrationScreen. Press device back button mid-calibration. Verify overlay dismisses.
- Pass: `BackHandler { gazeViewModel.cancelCalibration() }` in `AppContent` fires, sets `CalibrationUiState.Idle`, dismisses overlay.
- Source: `MainActivity.kt` — confirmed BackHandler present (Sprint 14).

**G.7** `audhd_noStreakLanguage_noBannedCopy` 🔴
- Attack: Audit all `strings.xml` entries for banned copy: streak language, loss-framing, urgency copy, contingent reward mechanics.
- Pass: `scripts/check_banned_strings.sh` CI check passes on every build.
- Source: CI gate confirmed active.

---

## Section H — Android platform security

**H.1** `platform_noNetworkSecurityConfig_risk` 🔴
- MASVS: MASVS-NETWORK-1
- Attack: No `android:networkSecurityConfig` in Manifest. Verify `targetSdk = 36` inherits default cleartext-blocking policy, or add explicit `network_security_config.xml`.
- Pass: Either explicit `<base-config cleartextTrafficPermitted="false">` config present, or confirmed `targetSdk >= 28` default applies with no cleartext exemptions.
- ⚠️ Risk: No `network_security_config.xml` present. `INTERNET` permission declared without explicit "no cleartext" config is a defense-in-depth gap.

**H.2** `platform_internetPermission_unusedInV1` 🔴
- MASVS: MASVS-NETWORK-1
- Attack: `INTERNET` permission declared for "future Supabase sync (V2)". Verify Lectern V1 makes zero network requests during normal use.
- Pass: Android Network Profiler shows zero outbound connections during a full V1 session.
- Note: INTERNET cannot be revoked by users on Android. Consider removing until V2 ships.

**H.3** `platform_cameraPermission_runtimeOnly_noHardDep` 🔴
- MASVS: MASVS-PLATFORM-1
- Attack: Install on device without front camera. Verify app does not crash and gaze features degrade gracefully.
- Pass: `uses-feature camera.front required="false"` set. `GazeViewModel` catches `IOException` from `GazeProvider.start()`. App fully usable without gaze.
- Source: Manifest confirmed `required="false"`.

**H.4** `platform_mainActivityExported_noDeepLinkInjection` 🔴
- MASVS: MASVS-PLATFORM-3
- Attack: Send a crafted Intent to `MainActivity` with unexpected extras (e.g., `bookId` extra containing path traversal characters).
- Pass: `MainActivity` does not access `intent.extras`. State derived from ViewModel only. Crafted Intent has no effect.
- Source: `MainActivity.kt` — confirmed no `intent.extras` access.

**H.5** `platform_contentProviderNotExported` 🔴
- MASVS: MASVS-PLATFORM-2
- Attack: Verify no exported `ContentProvider` allows other apps to read book data or DataStore.
- Pass: No `<provider>` element in Manifest. Room and DataStore accessible only to app process.
- Source: Manifest confirmed.

**H.6** `platform_screenshotBehavior_documented` 🔍
- MASVS: MASVS-PLATFORM-2
- Design decision: No `FLAG_SECURE` is set. Screenshots permitted. Document this as intentional (accessibility tools need screenshots). Revisit if private annotation feature is added in V2.

---

## Section I — Kotlin coroutines / dispatcher safety

**I.1** `coroutines_gazeProviderConfinedDispatcher` 🔴
- MASVS: MASVS-CODE-3
- Attack: Verify `GazeProviderImpl` uses `Dispatchers.Default.limitedParallelism(1)` for all state mutations. Two concurrent `onLandmarkerResult` callbacks must not race on `_state`.
- Pass: MediaPipe callback → `scope.launch` on confined dispatcher. All `_state.value` assignments serialized.
- Source: `GazeProviderImpl.kt` — confirmed `limitedParallelism(1)`.

**I.2** `coroutines_roomNotOnMainThread` 🔴
- MASVS: MASVS-CODE-3
- Attack: Verify `AppDatabase.getInstance()` does not call `allowMainThreadQueries()`. All DAO calls use suspend or explicit IO dispatcher.
- Pass: No `allowMainThreadQueries()` in `AppDatabase.kt`. Confirmed.

**I.3** `coroutines_ttsCollectionJob_noCancellationRace` 🔴
- MASVS: MASVS-CODE-3
- Attack: Rapidly start → stop → start TTS three times. Verify no double-cancel race and no `EngineUnavailable` false positive.
- Pass: `cleanUpTts()` called directly (Sprint 12 race fix). Rapid start/stop sequence produces consistent state.
- Source: Sprint 12 review fix confirmed.

**I.4** `coroutines_gazeStopDuringCalibration_noUnhandledThrow` 🔴
- MASVS: MASVS-CODE-3
- Attack: Start calibration, disable gaze mid-calibration. `finishCalibration()` calls `requireNotNull(provider)` after `provider = null`.
- Pass: `IllegalArgumentException` caught → `CalibrationUiState.CalibrationError` emitted. No unhandled exception.
- Source: Sprint 14 research confirmed error path handled.

**I.5** `coroutines_imageAnalysisExecutor_shutdown` 🔴
- MASVS: MASVS-CODE-3
- Attack: Toggle gaze on/off rapidly five times. Verify `analysisExecutor` is shutdown on `stop()` without thread leaks.
- Pass: `analysisExecutor.shutdown()` called in `stop()`. New `GazeProviderImpl` per `startGazeInternal()` session = fresh executor per session.
- Source: `GazeProviderImpl.kt` — confirmed `analysisExecutor.shutdown()` in `stop()`.

---

## Section J — Gaze tracking pipeline (Android-only)

> Gaze tracking is unique to Lectern Android — no equivalent in the iOS app.
> CameraX 1.4.0 + MediaPipe Face Landmarker + ridge regression calibration.
> Raw face data must never be persisted, logged, or transmitted.

**J.1** `gaze_rawIrisUV_neverPersisted` 🔴
- MASVS: MASVS-STORAGE-1 · MASVS-STORAGE-2
- Attack: Run a calibration session. Inspect all DataStore files. Verify raw iris UV coordinates (`irisU`, `irisV`) are not persisted — only derived regression weights are stored.
- Pass: `CalibrationRepository.save()` stores only `weightsX` and `weightsY` DoubleArrays. No `irisU`/`irisV` values in any DataStore file.
- Source: `CalibrationRepository.kt` — confirmed weights-only storage.

**J.2** `gaze_rawIrisUV_neverLogged` 🔴
- MASVS: MASVS-STORAGE-2
- Attack: Run gaze tracking in DEBUG build. Capture full Logcat. Verify no line contains `irisU`, `irisV`, or raw UV float values.
- Pass: Zero Logcat lines with iris coordinate data. `check_gaze_data_leak.sh` CI check prevents source-level regressions.
- Source: Confirmed from source — gaze logs contain only status messages.

**J.3** `gaze_calibrationWeights_notCorruptedByDegenerate` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: Confirm all 9 calibration points at identical positions (degenerate / collinear data). Verify `ridge()` fails gracefully, not silently stores wrong weights.
- Pass: `check(solver.setA(xtx))` throws `IllegalStateException` → caught → `CalibrationUiState.CalibrationError`. Previously valid weights are NOT overwritten (repository written only on success).
- Source: `GazeProviderImpl.ridge()` — confirmed `check()` guard.

**J.4** `gaze_cameraPermissionRevoked_gracefulStop` 🔴
- MASVS: MASVS-PLATFORM-1
- Attack: Enable gaze, then revoke CAMERA permission from Settings while running (Android 11+). Verify no crash.
- Pass: CameraX exception caught by `startGazeInternal()` `IOException` handler. `_gazeEnabled` set to false. App remains functional.

**J.5** `gaze_thermalThrottle_stopsInference` 🔴
- MASVS: MASVS-CODE-4 (system resource safety)
- Attack: Simulate `THERMAL_STATUS_SEVERE` via `adb shell cmd power set-thermal-override SEVERE`. Verify CameraX frame delivery stops.
- Pass: `thermalListener` calls `pauseAnalysis()` → `imageAnalysis?.clearAnalyzer()`. Frame delivery and GPU inference stop. State transitions to `GazeState.Paused`.
- Source: Sprint 13 review fix confirmed `pauseAnalysis()` is called.

**J.6** `gaze_modelFile_notExtractedToWorldReadable` 🔴
- MASVS: MASVS-STORAGE-1
- Attack: Verify `face_landmarker.task` is not extracted to external or world-readable storage at runtime.
- Pass: `BaseOptions.setModelAssetPath("face_landmarker.task")` loads from APK assets. No model file visible as world-readable in app sandbox.
- 🔍 Verify: MediaPipe may internally extract to `filesDir` for performance — verify it is not world-readable.

---

## Full coverage matrix

| Section | Count | Status |
|---|---|---|
| A — EPUB3 content injection | 7 | 🔴 All new |
| B — File import from untrusted sources | 7 | 🔴 All new |
| C — Room DB integrity | 5 | 🔴 All new |
| D — DataStore and local storage | 5 | 🔴 All new |
| E — TTS / Android speech engine | 4 | 🔴 All new |
| F — Supply chain | 6 | 🔴 All new |
| G — AuDHD-first safety regressions | 7 | 🔴 All new |
| H — Android platform security | 6 | 🔴 All new |
| I — Kotlin coroutines / dispatcher safety | 5 | 🔴 All new |
| J — Gaze tracking pipeline | 6 | 🔴 All new |
| **Total** | **58** | **58 new** |

---

## MASVS coverage summary

| Code | Name | Sections |
|---|---|---|
| MASVS-STORAGE-1 | Data stored securely | B.2–B.5, C.1–C.5, D.1–D.5, J.1, J.3 |
| MASVS-STORAGE-2 | Sensitive data not logged/shared | D.3, E.2, J.2 |
| MASVS-CRYPTO-1 | Cryptography / file protection | D.5 |
| MASVS-NETWORK-1 | Secure network communication | A.2, D.2, F.3, F.5, H.1–H.2 |
| MASVS-PLATFORM-1 | Secure WebView / component use | A.1–A.5, E.3, H.3, J.4 |
| MASVS-PLATFORM-2 | No unintended data exposure | A.2, E.1–E.2, H.5–H.6 |
| MASVS-PLATFORM-3 | URL scheme / Intent security | A.3, H.4 |
| MASVS-CODE-3 | Thread / concurrency safety | C.3, I.1–I.5 |
| MASVS-CODE-4 | Memory / parser / resource safety | A.6, B.1–B.2, J.5 |
| MASVS-CODE-5 | Dependency hygiene | F.1–F.6 |

---

## Implementation priority

### Phase 1 — Before Reader PR 1 merges
- A.1 (JS in Android WebView — highest risk in Readium integration)
- A.2 (external resource loading)
- A.3 (intent:// scheme injection — Android-specific, high risk)
- A.6 (malformed EPUB parser crash)
- B.3 (path traversal in zip4j / CBZ)
- B.4 (path traversal in Readium / EPUB)
- F.1 (version pins confirmed)
- J.1 (iris UV never persisted — regression guard)
- J.2 (iris UV never logged — regression guard)

### Phase 2 — Before library V2
- A.4, A.5 (CSS/SVG injection)
- B.1, B.2 (ZIP bombs — EPUB and CBZ)
- B.6, B.7 (type validation, duplicate books)
- C.1–C.5 (migration, concurrency, cascade delete)
- D.2 (D2D transfer design decision)
- H.1 (network security config — defense in depth)
- H.2 (INTERNET permission unused — consider removing)
- I.3 / I.4 (TTS race, calibration race)

### Phase 3 — Before public beta
- All remaining: E, G, H.3–H.6, I.5, J.3–J.6, F.2–F.6

---

## Removed from iOS doc (not applicable to Android V1)

| iOS test | Reason removed |
|---|---|
| CloudKit sync conflict tests (C.1–C.5 iOS) | No cloud sync in Android V1 |
| iCloud exposure tests (D.1–D.4 iOS) | No iCloud |
| Swift Package checksum (F.4 iOS) | Gradle, not SPM |
| NSFileProtection levels (H.3 iOS) | Android uses different file protection model |
| Swift 6 actor isolation tests (I.1–I.3 iOS) | Kotlin coroutines replace |
| WKContentRuleList tests | Android WebView uses different interception API |
| AVSpeechSynthesizer specifics | Android TTS engine is different |

---

## References

| Resource | URL |
|---|---|
| OWASP MASVS v2 | https://mas.owasp.org/MASVS/ |
| OWASP MSTG (Android section) | https://mas.owasp.org/MASTG/Android/ |
| EPUB3 Security Considerations (W3C) | https://www.w3.org/TR/epub-33/#sec-security-privacy |
| CVE-2021-40870 (Readium path traversal) | https://nvd.nist.gov/vuln/detail/CVE-2021-40870 |
| Android WebView security | https://developer.android.com/guide/webapps/webview |
| Android Network Security Config | https://developer.android.com/training/articles/security-config |
| Android Data Extraction Rules | https://developer.android.com/guide/topics/data/autobackup |
| CWE-22 Path Traversal | https://cwe.mitre.org/data/definitions/22.html |
| CWE-409 ZIP bomb (CWE-409) | https://cwe.mitre.org/data/definitions/409.html |
| zip4j documentation | https://github.com/srikanth-lingala/zip4j |
| MediaPipe Tasks Vision | https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android |
| Room database security | https://developer.android.com/training/data-storage/room |
