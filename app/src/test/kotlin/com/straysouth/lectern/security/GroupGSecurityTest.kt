package com.straysouth.lectern.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security regression tests for Group G (AuDHD-first safety).
 *
 * Covers JVM-testable properties:
 *   G.1 — All Compose animation durations (tween() calls) are ≤ 200 ms in main
 *           sources; no overly long transition distracts or disables AuDHD readers
 *   G.2 — No auto-advance timer APIs (postDelayed, CountDownTimer) in main sources;
 *           page/chapter navigation is always user-initiated
 *   G.5 — GazeFocusBandOverlay defaults to OFF in both the data class and the
 *           DataStore fallback — new installs and first-launch never show the overlay
 *           without explicit user opt-in (ADR-AND-D)
 *   G.6 — CalibrationScreen BackHandler wires to cancelCalibration() and is scoped
 *           to the calibration-active guard; back-press mid-calibration is always safe
 *   G.7 — strings.xml (and all values XML resources) contain none of the banned
 *           copy terms defined in scripts/check_banned_strings.sh; JVM-layer
 *           defence-in-depth alongside the shell CI gate
 *
 *   G.3 — Import-error Snackbar uses SnackbarDuration.Indefinite + withDismissAction;
 *           error stays visible until the user taps × (AuDHD readers must not miss it)
 *
 * Deferred (instrumented or design decisions):
 *   G.4 — Theme-change flash: requires Compose rendering — instrumented test only.
 *
 * Limitation (G.1): tween() calls using a named constant (e.g. tween(ANIM_MS)) are
 * not covered — the literal-integer extractor silently skips non-literal arguments.
 * If a constant is introduced, add a companion grep for the constant declaration.
 *
 * See docs/security/RED-TEAM.md §G for full attack descriptions and pass criteria.
 *
 * Working-directory assumption: file paths resolve relative to the `app/` module
 * directory, which is the default CWD for `./gradlew testDebugUnitTest`.
 */
class GroupGSecurityTest {

    // ── G.1 — Animation durations ≤ 200 ms ───────────────────────────────────

    /**
     * Animations lasting longer than ~200 ms are perceptually distracting for AuDHD
     * readers and can desynchronise attention from content. All [tween()] calls in
     * main sources must use a literal duration ≤ 200 ms.
     *
     * Two forms covered:
     *   - Positional first arg: `tween(200)`, `tween(200, easing = …)`
     *   - Named param: `tween(durationMillis = 200)`
     *
     * Variable-based durations (e.g., `tween(ANIM_DURATION)`) are not detectable
     * statically — see class-level KDoc limitation note.
     */
    @Test
    fun audhd_animations_allTweenDurations_atMost200ms() {
        val mainSources = File("src/main/kotlin")
        assertTrue(
            "src/main/kotlin not found (working dir: ${System.getProperty("user.dir")})",
            mainSources.exists(),
        )
        val violations = mainSources.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                extractTweenDurations(file.readText())
                    .filter { (_, ms) -> ms > MAX_ANIMATION_MS }
                    .map { (form, ms) -> "${file.name}: $form → ${ms}ms (max $MAX_ANIMATION_MS)" }
            }
            .toList()
        assertTrue(
            "All tween() durations must be ≤ ${MAX_ANIMATION_MS}ms — longer animations " +
                "distract AuDHD readers and desynchronise attention from content (G.1):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── G.2 — No timer-driven auto-advance ────────────────────────────────────

    /**
     * Page and chapter navigation must always be user-initiated. [postDelayed] and
     * [CountDownTimer] are the primary Android APIs that drive time-based actions
     * without user input. Their presence anywhere in main sources is a regression signal.
     *
     * Note: [Handler(] is not scanned directly because [BackHandler(] (a Compose API)
     * is a legitimate safe usage whose name contains the substring. [postDelayed] is
     * the specific method that drives auto-advance — it is the correct signal.
     *
     * [delay()] inside coroutines is not scanned globally — it has legitimate non-nav
     * uses (debounce, back-off). Time-driven LaunchedEffect navigation is an instrumented
     * concern. The current codebase has zero delay() calls (verified from source).
     */
    @Test
    fun audhd_noAutoAdvance_noTimerDrivenNavigation() {
        val mainSources = File("src/main/kotlin")
        assertTrue(
            "src/main/kotlin not found (working dir: ${System.getProperty("user.dir")})",
            mainSources.exists(),
        )
        val timerApis = listOf("postDelayed(", "CountDownTimer(")
        val violations = mainSources.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                val codeLines = stripComments(file.readText())
                timerApis
                    .filter { api -> codeLines.contains(api) }
                    .map { api -> "${file.name}: $api" }
            }
            .toList()
        assertTrue(
            "No timer-driven navigation APIs must appear in main sources — auto-advance " +
                "without user input is prohibited for AuDHD readers (G.2):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── G.5 — Gaze overlay defaults to OFF ───────────────────────────────────

    /**
     * [FocusBandPrefs.gazeOverlayEnabled] must default to [false] in the data class.
     * An in-memory [FocusBandPrefs()] constructed without a stored preference must
     * not show the gaze overlay — ADR-AND-D: all new gaze visuals default off.
     */
    @Test
    fun audhd_gazeOverlay_defaultOff_inPrefsClass() {
        assertTrue(
            "FocusBandPrefs must declare gazeOverlayEnabled: Boolean = false — " +
                "new installs and in-memory prefs must not show the overlay by default; " +
                "ADR-AND-D: gaze visuals default off (G.5)",
            sourceFile("data/repository/FocusBandPrefs.kt")
                .contains("gazeOverlayEnabled: Boolean = false"),
        )
    }

    /**
     * [FocusBandRepository.observe()] must use `?: false` as the DataStore fallback for
     * the gaze overlay key. On first launch (no stored preference), the repository emits
     * [FocusBandPrefs] with [gazeOverlayEnabled = false]. If this fallback is `?: true`,
     * every new install shows the overlay without user consent even though
     * [FocusBandPrefs] correctly defaults to false.
     */
    @Test
    fun audhd_gazeOverlay_defaultOff_inRepository() {
        val source = sourceFile("data/repository/FocusBandRepository.kt")
        // The DataStore key is KEY_FIXATION_OVERLAY. The fallback ?: false is the only
        // place the first-launch default is set at the read path.
        assertTrue(
            "FocusBandRepository.observe() must use KEY_FIXATION_OVERLAY] ?: false as the " +
                "DataStore fallback — first-launch (no stored preference) must not show the " +
                "gaze overlay; ?: true would override the FocusBandPrefs default (G.5)",
            source.contains("KEY_FIXATION_OVERLAY] ?: false"),
        )
    }

    // ── G.6 — CalibrationScreen back-press dismisses overlay ─────────────────

    /**
     * [BackHandler] in [AppContent] must be wired directly to
     * [GazeViewModel.cancelCalibration()] so the system back gesture during calibration
     * cleanly dismisses the overlay. Two properties are verified:
     *
     * 1. The exact wiring exists: `BackHandler { gazeViewModel.cancelCalibration() }`
     *    on a single line — confirms the lambda is not empty and the correct ViewModel
     *    method is called.
     *
     * 2. The calibration guard precedes the BackHandler in the source — confirms the
     *    BackHandler is scoped to the calibration-active condition, not unconditional.
     *    An unconditional BackHandler would intercept ALL back-presses (including
     *    reader → library navigation) and route them to cancelCalibration().
     */
    @Test
    fun audhd_calibrationOverlay_backHandlerWiresCancelCalibration() {
        val source = sourceFile("MainActivity.kt")

        val wiring = "BackHandler { gazeViewModel.cancelCalibration() }"
        assertTrue(
            "MainActivity must contain BackHandler { gazeViewModel.cancelCalibration() } — " +
                "system back gesture during calibration must dismiss the overlay (G.6)",
            source.contains(wiring),
        )

        // Use lastIndexOf for the guard token so that a comment earlier in the file
        // containing the guard string does not defeat the ordering check. The last
        // occurrence is always the actual code line; a BackHandler outside the if-block
        // would appear before that last occurrence.
        val guardToken = "calibrationUiState !is CalibrationUiState.Idle"
        val guardIdx = source.lastIndexOf(guardToken)
        val backHandlerIdx = source.indexOf(wiring)
        assertTrue(
            "CalibrationUiState.Idle guard must appear before BackHandler in MainActivity — " +
                "the BackHandler must be scoped to calibration-active state, not unconditional; " +
                "an unconditional BackHandler would intercept reader → library navigation (G.6)",
            guardIdx >= 0 && guardIdx < backHandlerIdx,
        )
    }

    // ── G.7 — No banned copy in string resources ──────────────────────────────

    /**
     * All XML files under [src/main/res/values/] must contain none of the AuDHD-banned
     * copy terms defined in [scripts/check_banned_strings.sh]. This JVM test provides
     * local-run defence-in-depth alongside the shell CI gate:
     *   - The shell script runs only in CI; this test runs on every `./gradlew test`.
     *   - If the CI YAML accidentally disables the shell gate, this test still catches
     *     regressions before the PR is merged.
     *
     * Banned terms mirror [check_banned_strings.sh] exactly (case-insensitive):
     * streak, consecutive, wrong, incorrect, failed, missed, "great job", "keep it up",
     * "daily goal", 🔥, 🏆, ⭐.
     */
    @Test
    fun audhd_stringsXml_noBannedCopy() {
        val valuesDir = File("src/main/res/values")
        assertTrue(
            "src/main/res/values not found (working dir: ${System.getProperty("user.dir")})",
            valuesDir.exists(),
        )
        val violations = valuesDir.walkTopDown()
            .filter { it.extension == "xml" }
            .flatMap { file ->
                val text = file.readText()
                BANNED_COPY_TERMS
                    .filter { term -> text.contains(term, ignoreCase = true) }
                    .map { term -> "${file.name}: \"$term\"" }
            }
            .toList()
        assertTrue(
            "String resources must contain no AuDHD-banned copy terms — streak language, " +
                "loss-framing, and contingent-reward mechanics are prohibited (G.7):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── V2.6 — A11y chapter rotor installed on each WebView ────────────────

    /**
     * V2.6 — A11y chapter rotor. Asserts that [EpubReaderFragment] wires the
     * publication's [tableOfContents] into ViewCompat accessibility actions on
     * each EPUB WebView (not just the navigator's parent container, which was
     * the original v1 wiring — TalkBack focuses on the inner WebView during
     * reading, so actions on the parent never surface).
     *
     * Pinned by source assertion (not runtime) because:
     *   - The runtime path requires an instrumented test (Fragment lifecycle +
     *     CameraX + Readium publication) which is `androidTest`-only.
     *   - The wiring is shape-pinnable here: presence of `tocEntries.collect`
     *     bound to `installChapterRotor`, `installChapterRotor` walking the
     *     tracked WebViews, and a `registerWebViewForRotor` hook called from
     *     `wrapWebViewsIn` so newly-created WebViews get the rotor too.
     *
     * Regression target: a future contributor removes the per-WebView wiring
     * (e.g. reverts to attaching only on `navigator.view`), breaking the rotor
     * for TalkBack users mid-reading.
     */
    @Test
    fun audhd_chapterRotor_installedOnEachWebView() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderFragment.kt")
                .readText(),
        )
        assertTrue(
            "EpubReaderFragment must observe viewModel.tocEntries to refresh the " +
                "chapter rotor when the publication's TOC becomes available (V2.6)",
            source.contains("viewModel.tocEntries.collect"),
        )
        assertTrue(
            "EpubReaderFragment must define installChapterRotor() to walk tracked " +
                "WebViews and refresh their rotor actions (V2.6)",
            source.contains("fun installChapterRotor"),
        )
        assertTrue(
            "wrapWebViewsIn must call registerWebViewForRotor so each newly-created " +
                "WebView is added to the rotor surface (V2.6 — per-WebView wiring)",
            source.contains("registerWebViewForRotor(root)"),
        )
        assertTrue(
            "installRotorOnWebView must call ViewCompat.addAccessibilityAction on " +
                "the WebView itself (not the parent container) so TalkBack surfaces " +
                "the rotor when focus is in the reading content (V2.6)",
            source.contains("ViewCompat.addAccessibilityAction(webView, label)"),
        )
        assertTrue(
            "installRotorOnWebView must invoke navigator.go(link, ...) inside the " +
                "action callback so activating the rotor entry navigates (V2.6)",
            source.contains("navigator.go(link"),
        )
    }

    // ── V2.2.2 — Annotation list panel + note entry wired in reader ────────

    /**
     * V2.2.2 — `AnnotationListPanel` Composable is invoked from
     * `ReaderOverlay`'s ReadyOverlay block, gated on `showAnnotationList`.
     * Toolbar exposes both the "Note" action and the "Annotations list"
     * action so users can reach the panel without leaving the reader.
     *
     * Pinned by source assertion. Per ADR-AND-T 2026-05-23 amendment.
     */
    @Test
    fun audhd_annotationPanel_existsInReader() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/ReaderOverlay.kt").readText(),
        )
        assertTrue(
            "ReaderOverlay must invoke AnnotationListPanel (V2.2.2)",
            source.contains("AnnotationListPanel("),
        )
        assertTrue(
            "ReaderOverlay must invoke NoteEntryDialog when pendingNoteLocator is " +
                "non-null (V2.2.2 — dialog state survives config change via VM)",
            source.contains("NoteEntryDialog("),
        )
        assertTrue(
            "ReaderToolbar must expose the Note action button (Icons.AutoMirrored.Filled.NoteAdd) " +
                "for V2.2.2 note creation",
            source.contains("Icons.AutoMirrored.Filled.NoteAdd"),
        )
        assertTrue(
            "ReaderToolbar must expose the Annotations list action button " +
                "(Icons.AutoMirrored.Filled.FormatListBulleted) for V2.2.2",
            source.contains("Icons.AutoMirrored.Filled.FormatListBulleted"),
        )
    }

    /**
     * V2.2.3 — annotation delete must offer Undo via Snackbar (AuDHD G.3).
     *
     * Pinned by source assertion:
     *   - `EpubReaderFragment` invokes `UndoDeleteAnnotationEffect` and includes
     *     a `SnackbarHost` in the Compose overlay.
     *   - `EpubReaderViewModel` exposes `deletedAnnotations` and `restoreAnnotation`.
     *   - `AnnotationRepository.upsert(annotation)` exists for the re-insert path.
     */
    @Test
    fun audhd_annotationUndo_existsInReader() {
        val fragmentSrc = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderFragment.kt").readText(),
        )
        val vmSrc = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderViewModel.kt").readText(),
        )
        val repoSrc = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/data/repository/AnnotationRepository.kt").readText(),
        )
        assertTrue(
            "EpubReaderFragment must invoke UndoDeleteAnnotationEffect (V2.2.3)",
            fragmentSrc.contains("UndoDeleteAnnotationEffect("),
        )
        assertTrue(
            "EpubReaderFragment must include a SnackbarHost overlay (V2.2.3)",
            fragmentSrc.contains("SnackbarHost("),
        )
        assertTrue(
            "EpubReaderViewModel must expose deletedAnnotations SharedFlow (V2.2.3)",
            vmSrc.contains("deletedAnnotations"),
        )
        assertTrue(
            "EpubReaderViewModel must expose restoreAnnotation for Undo (V2.2.3)",
            vmSrc.contains("fun restoreAnnotation("),
        )
        assertTrue(
            "AnnotationRepository must expose upsert(annotation) for V2.2.3 undo path",
            repoSrc.contains("fun upsert(annotation: Annotation)"),
        )
    }

    /**
     * V2.2.3 — note and highlight decorations must use distinct tints.
     *
     * Source assertion: `EpubReaderFragment` defines both `ANNOTATION_HIGHLIGHT_TINT`
     * and `ANNOTATION_NOTE_TINT` and selects between them by `ann.type`.
     */
    @Test
    fun audhd_annotationTints_areDistinct() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderFragment.kt").readText(),
        )
        assertTrue(
            "EpubReaderFragment must define ANNOTATION_HIGHLIGHT_TINT (V2.2)",
            source.contains("ANNOTATION_HIGHLIGHT_TINT"),
        )
        assertTrue(
            "EpubReaderFragment must define ANNOTATION_NOTE_TINT (V2.2.3 — distinct from highlight)",
            source.contains("ANNOTATION_NOTE_TINT"),
        )
        assertTrue(
            "Decoration observer must branch tint selection on ann.type (V2.2.3)",
            source.contains("ann.type == AnnotationRepository.TYPE_NOTE") ||
                source.contains("annotation.type == AnnotationRepository.TYPE_NOTE"),
        )
    }

    // ── G.3 — Import-error Snackbar is Indefinite + dismissable ─────────────

    /**
     * AuDHD readers may not notice a 4-second transient error message (the old
     * [SnackbarDuration.Short] default). The Snackbar must:
     *   1. Use [SnackbarDuration.Indefinite] — stays until explicitly dismissed.
     *   2. Pass [withDismissAction] = true — provides a "×" close button.
     *   3. NOT use [SnackbarDuration.Short] anywhere in [LibraryScreen].
     *
     * Comment-stripping guards against a code comment mentioning "Short" for documentation.
     */
    @Test
    fun audhd_importErrorSnackbar_indefiniteDuration_withDismissAction() {
        val source = sourceFile("ui/library/LibraryScreen.kt")
        val stripped = stripComments(source)
        assertTrue(
            "LibraryScreen must pass SnackbarDuration.Indefinite to showSnackbar() " +
                "so AuDHD readers are not timed out before they can read the error (G.3)",
            stripped.contains("SnackbarDuration.Indefinite"),
        )
        assertTrue(
            "LibraryScreen must pass withDismissAction = true to showSnackbar() " +
                "so the user has an explicit affordance to dismiss the error (G.3)",
            stripped.contains("withDismissAction = true"),
        )
        assertFalse(
            "LibraryScreen must not use SnackbarDuration.Short — 4-second auto-dismiss " +
                "is insufficient for AuDHD readers (G.3)",
            stripped.contains("SnackbarDuration.Short"),
        )
        // finally block ensures clearImportError() runs even when LaunchedEffect is
        // cancelled mid-suspension (second error arriving while first Snackbar is showing).
        assertTrue(
            "showSnackbar() call must be wrapped in try/finally so clearImportError() " +
                "always runs — prevents stale importError ViewModel state on cancellation (G.3)",
            stripped.contains("try {") && stripped.contains("} finally {"),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts all literal-integer tween durations from [source]. Returns pairs of
     * (matched form, duration ms) for every tween() call with a numeric literal argument.
     *
     * Handles two forms:
     *   - Positional: `tween(200)` or `tween(200, easing = …)` → extracted via first-arg regex
     *   - Named: `tween(durationMillis = 200)` → extracted via named-param regex
     */
    private fun extractTweenDurations(source: String): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        // Positional first arg: tween(200) or tween( 200, ...)
        TWEEN_POSITIONAL_RE.findAll(source).forEach { m ->
            results += m.value to m.groupValues[1].toInt()
        }
        // Named durationMillis param: durationMillis = 200
        TWEEN_NAMED_RE.findAll(source).forEach { m ->
            results += m.value to m.groupValues[1].toInt()
        }
        return results
    }

    // Strips single-line comments (//), KDoc body lines (*), block-comment openers
    // (slash-star), and inline comment tails (anything after // on a code line) before
    // string checks to prevent both whole-line and inline comment false positives.
    // Note: substringBefore("//") also strips the // inside string literals such as
    // "https://..." — acceptable for the G.2 scan (postDelayed/CountDownTimer are
    // never found after a URL prefix in practice).
    private fun stripComments(source: String): String =
        source.lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .map { it.substringBefore("//") }
            .joinToString("\n")

    private fun sourceFile(relativePath: String): String {
        val base = "src/main/kotlin/com/straysouth/lectern"
        val file = File("$base/$relativePath")
        assertTrue(
            "Source file not found: $base/$relativePath " +
                "(working dir: ${System.getProperty("user.dir")})",
            file.exists(),
        )
        return file.readText()
    }

    // ── V2.4 RSVP — ADR-AND-X privacy invariants ───────────────────────────

    /**
     * ADR-AND-X §Privacy: clipboard content snapshotted into [RsvpSource.Clipboard]
     * MUST NEVER reach Log.*. The clipboard is user-private (passwords, payment
     * data, intimate messages); a single Log call leaks it to logcat.
     */
    @Test
    fun rsvp_clipboardNeverLogged() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/rsvp/RsvpViewModel.kt").readText(),
        )
        source.lineSequence().forEach { line ->
            if (line.contains("Log.")) {
                assertFalse(
                    "RsvpViewModel must never log clipboard content (ADR-AND-X §Privacy): $line",
                    line.contains("clipboard", ignoreCase = true) ||
                        line.contains("source.text") ||
                        line.contains("words"),
                )
            }
        }
    }

    /**
     * ADR-AND-X §Privacy: `.txt` source URI MUST NEVER reach Log.*. The URI can
     * encode device-private path tokens; logging it leaks the user's file
     * picker selection to logcat.
     */
    @Test
    fun rsvp_txtUriNeverLogged() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/rsvp/RsvpViewModel.kt").readText(),
        )
        source.lineSequence().forEach { line ->
            if (line.contains("Log.")) {
                assertFalse(
                    "RsvpViewModel must never log .txt URI (ADR-AND-X §Privacy): $line",
                    line.contains("uri.path") ||
                        line.contains("uri.toString") ||
                        line.contains("source.uri") ||
                        line.contains("\$uri"),
                )
            }
        }
    }

    /**
     * ADR-AND-X §Privacy: the RSVP nav state in MainActivity holds clipboard
     * content (when source is RsvpSource.Clipboard). It MUST be plain
     * `remember { ... }`, never `rememberSaveable`. On process death the
     * RSVP session resets to library — the correct privacy-preserving
     * behavior.
     */
    @Test
    fun rsvp_navStateNotSaveable() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/MainActivity.kt").readText(),
        )
        val rsvpStateLine = source.lineSequence()
            .firstOrNull { it.contains("currentRsvpSource") && it.contains("by") }
        assertTrue(
            "currentRsvpSource declaration not found in MainActivity",
            rsvpStateLine != null,
        )
        assertFalse(
            "currentRsvpSource MUST use plain remember{} not rememberSaveable (ADR-AND-X §Privacy): $rsvpStateLine",
            rsvpStateLine!!.contains("rememberSaveable"),
        )
    }

    /**
     * V2.3 — review screen exists and is wired from LibraryScreen.
     * Source assertion: ReviewScreen.kt is invoked from MainActivity.kt.
     */
    @Test
    fun review_screenIsWiredFromMainActivity() {
        val main = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/MainActivity.kt").readText(),
        )
        assertTrue(
            "MainActivity must compose ReviewScreen (V2.3)",
            main.contains("ReviewScreen("),
        )
        val library = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/library/LibraryScreen.kt").readText(),
        )
        assertTrue(
            "LibraryScreen must expose onReviewRequested (V2.3 entry point)",
            library.contains("onReviewRequested"),
        )
    }

    // ── V2.9 — TTS foreground service (ADR-AND-W) ───────────────────────────

    /**
     * V2.9 — synchronous-default-then-async-rich notification build.
     *
     * `TtsForegroundService.onCreate` must call `startForeground` with
     * `TtsNotificationBuilder.buildDefault(...)` immediately (ANR-safe; meets
     * the foreground-service-start deadline regardless of how long book
     * metadata lookups take). The rich notification (book title + chapter)
     * is populated later via `updateNowPlaying` → `NotificationManagerCompat.
     * notify(NOTIFICATION_ID, buildRich(...))`. Pinned by source assertion
     * per ADR-AND-W §Decision.
     */
    @Test
    fun audhd_serviceNotification_richContent_atomicUpdate() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/service/TtsForegroundService.kt").readText(),
        )
        val onCreateIdx = source.indexOf("override fun onCreate()")
        assertTrue(
            "TtsForegroundService.onCreate() must exist (ADR-AND-W §Decision)",
            onCreateIdx >= 0,
        )
        val onCreateBody = source.substring(
            onCreateIdx,
            (onCreateIdx + 600).coerceAtMost(source.length),
        )
        assertTrue(
            "TtsForegroundService.onCreate must call startForeground with the default " +
                "notification builder result — ANR-safe synchronous start (ADR-AND-W)",
            onCreateBody.contains("startForeground(") &&
                onCreateBody.contains("TtsNotificationBuilder.buildDefault("),
        )
        val updateIdx = source.indexOf("private fun updateNowPlaying(")
        assertTrue(
            "TtsForegroundService.updateNowPlaying must exist as the rich-update path " +
                "(ADR-AND-W §Decision)",
            updateIdx >= 0,
        )
        val updateBody = source.substring(
            updateIdx,
            (updateIdx + 2200).coerceAtMost(source.length),
        )
        assertTrue(
            "updateNowPlaying must atomically replace the default notification — call " +
                "TtsNotificationBuilder.buildRich(...) and post it via " +
                "NotificationManagerCompat.notify(NOTIFICATION_ID, ...) (ADR-AND-W)",
            updateBody.contains("TtsNotificationBuilder.buildRich("),
        )
        assertTrue(
            "TtsForegroundService must post the rich notification through " +
                "NotificationManagerCompat.notify(NOTIFICATION_ID, ...) so the system " +
                "replaces the default notification in place (ADR-AND-W)",
            source.contains("NotificationManagerCompat.from(this).notify(") &&
                source.contains("NOTIFICATION_ID"),
        )
    }

    /**
     * V2.9 — POST_NOTIFICATIONS gate on the service-start path.
     *
     * `EpubReaderViewModel` must check `POST_NOTIFICATIONS` before binding the
     * foreground service. API 33+ users who deny the permission get V1
     * foreground-only TTS, not a crash (ADR-AND-W §Decision).
     */
    @Test
    fun platform_serviceStart_requiresPostNotificationsPermission() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderViewModel.kt").readText(),
        )
        assertTrue(
            "EpubReaderViewModel must reference Manifest.permission.POST_NOTIFICATIONS " +
                "to gate the foreground-service start path (ADR-AND-W)",
            source.contains("Manifest.permission.POST_NOTIFICATIONS"),
        )
        assertTrue(
            "EpubReaderViewModel must invoke ContextCompat.checkSelfPermission on the " +
                "service-start path to detect denied POST_NOTIFICATIONS at API 33+ " +
                "(ADR-AND-W graceful-fallback rule)",
            source.contains("ContextCompat.checkSelfPermission("),
        )
        assertTrue(
            "EpubReaderViewModel must gate the service bind on Build.VERSION.SDK_INT " +
                ">= Build.VERSION_CODES.TIRAMISU — POST_NOTIFICATIONS is install-time on " +
                "older API levels (ADR-AND-W)",
            source.contains("Build.VERSION_CODES.TIRAMISU"),
        )
    }

    /**
     * V2.9 — single cleanup path through the ViewModel.
     *
     * Per ADR-AND-W §Decision: every stop trigger — including a recents-swipe
     * delivered to `onTaskRemoved` — routes through `viewModel.stopTts()`.
     * The service does not call its own `stopSelf()` in response to user
     * input; the VM decides. Pinned by source assertion on the service's
     * onTaskRemoved + the binder's callback contract.
     */
    @Test
    fun platform_serviceCleanup_singlePathThroughViewModel() {
        val serviceSource = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/service/TtsForegroundService.kt").readText(),
        )
        val taskRemovedIdx = serviceSource.indexOf("override fun onTaskRemoved(")
        assertTrue(
            "TtsForegroundService.onTaskRemoved must exist so recents-swipe routes " +
                "back to the VM (ADR-AND-W single-cleanup-path)",
            taskRemovedIdx >= 0,
        )
        val taskRemovedBody = serviceSource.substring(
            taskRemovedIdx,
            (taskRemovedIdx + 400).coerceAtMost(serviceSource.length),
        )
        assertTrue(
            "TtsForegroundService.onTaskRemoved must invoke callbacks?.onTaskRemoved() " +
                "— the service must not call stopSelf() directly, the VM owns cleanup " +
                "(ADR-AND-W)",
            taskRemovedBody.contains("callbacks?.onTaskRemoved()"),
        )
        val callbacksSource = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/service/TtsServiceCallbacks.kt").readText(),
        )
        assertTrue(
            "TtsServiceCallbacks must declare onTaskRemoved() as part of the binder " +
                "contract (ADR-AND-W)",
            callbacksSource.contains("fun onTaskRemoved()"),
        )
        val vmSource = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderViewModel.kt").readText(),
        )
        val callbacksImplIdx = vmSource.indexOf("object : TtsServiceCallbacks")
        assertTrue(
            "EpubReaderViewModel must implement TtsServiceCallbacks (ADR-AND-W)",
            callbacksImplIdx >= 0,
        )
        val implWindow = vmSource.substring(
            callbacksImplIdx,
            (callbacksImplIdx + 800).coerceAtMost(vmSource.length),
        )
        assertTrue(
            "EpubReaderViewModel.TtsServiceCallbacks.onTaskRemoved must route to " +
                "stopTts() — the VM is the single cleanup path (ADR-AND-W)",
            implWindow.contains("override fun onTaskRemoved()") &&
                implWindow.contains("stopTts()"),
        )
    }

    // ── V2.9-A adversarial fixes (ADR-AND-W lockscreen + FGS + lazy prompt) ──

    /**
     * V2.9-A fix #1: notifications use VISIBILITY_PRIVATE and the rich
     * notification ships a redacted public version (app name + "Reading",
     * no book title, no chapter). Lockscreen has a wider attacker model
     * than the unlocked notification shade (physical access, no auth) so
     * the proportional-exposure argument from ADR-AND-W §Threat model
     * does NOT extend there. Per ADR-AND-W §FGS notification visibility
     * policy (amendment 2026-05-25).
     */
    @Test
    fun platform_serviceNotification_visibilityPrivate_andPublicRedacted() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/service/TtsNotificationBuilder.kt").readText(),
        )
        assertFalse(
            "TtsNotificationBuilder must NOT call setVisibility(VISIBILITY_PUBLIC) on " +
                "the private notification — title/chapter would leak on the lockscreen " +
                "(ADR-AND-W §FGS notification visibility)",
            source.contains("setVisibility(NotificationCompat.VISIBILITY_PUBLIC)") &&
                !source.contains("buildPublicRedacted"),
        )
        assertTrue(
            "TtsNotificationBuilder.buildDefault must use VISIBILITY_PRIVATE " +
                "(ADR-AND-W §FGS notification visibility)",
            source.contains("setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"),
        )
        assertTrue(
            "TtsNotificationBuilder must define a buildPublicRedacted helper for the " +
                "lockscreen-safe public version (ADR-AND-W §FGS notification visibility)",
            source.contains("fun buildPublicRedacted("),
        )
        assertTrue(
            "TtsNotificationBuilder.buildRich must call setPublicVersion(buildPublicRedacted(...)) " +
                "so the system substitutes the redacted notification on the lockscreen " +
                "(ADR-AND-W §FGS notification visibility)",
            source.contains(".setPublicVersion(buildPublicRedacted("),
        )
    }

    /**
     * V2.9-A fix #3: startForegroundService is wrapped in try/catch for
     * ForegroundServiceStartNotAllowedException (API 31+). On catch the VM
     * falls back to V1 foreground-only TTS and surfaces a Snackbar via
     * backgroundPlaybackUnavailableEvents so the user knows. Per ADR-AND-W
     * §FGS start exception policy.
     */
    @Test
    fun platform_startForegroundService_exceptionGuarded() {
        val source = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderViewModel.kt").readText(),
        )
        val startIdx = source.indexOf("ContextCompat.startForegroundService(")
        assertTrue(
            "ContextCompat.startForegroundService call must exist in EpubReaderViewModel " +
                "(V2.9 service-start path)",
            startIdx >= 0,
        )
        // The catch must appear within a narrow window after the call so the
        // try block actually wraps the start. 400-char window covers a try
        // block and a single specific exception catch.
        val window = source.substring(
            (startIdx - 50).coerceAtLeast(0),
            (startIdx + 400).coerceAtMost(source.length),
        )
        assertTrue(
            "ContextCompat.startForegroundService must be inside a try { ... } block " +
                "(ADR-AND-W §FGS start exception policy)",
            window.contains("try {"),
        )
        assertTrue(
            "EpubReaderViewModel must catch IllegalStateException around startForegroundService " +
                "— ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException " +
                "(ADR-AND-W §FGS start exception policy)",
            window.contains("catch (e: IllegalStateException)") ||
                window.contains("catch (_: IllegalStateException)"),
        )
        assertTrue(
            "EpubReaderViewModel must expose backgroundPlaybackUnavailableEvents so the UI " +
                "can surface a Snackbar when the FGS start is rejected (ADR-AND-W §FGS start " +
                "exception policy)",
            source.contains("val backgroundPlaybackUnavailableEvents"),
        )
        assertTrue(
            "EpubReaderViewModel must emit a backgroundPlaybackUnavailableEvents Unit on the " +
                "FGS-rejection path so the Snackbar fires (ADR-AND-W §FGS start exception policy)",
            source.contains("_backgroundPlaybackUnavailableEvents.trySend(Unit)"),
        )
    }

    /**
     * V2.9-A fix #2 + #4: POST_NOTIFICATIONS prompt is LAZY (triggered on the
     * first startTts() attempt that needs it, not on Fragment.onCreate). The
     * denied-Snackbar consumes one-shot events (Channel-backed Flow), so every
     * startTts() attempt without permission fires the Snackbar — not just the
     * first deny. Per ADR-AND-W §Lazy permission policy.
     */
    @Test
    fun audhd_postNotifications_prompt_lazyAndOneShot() {
        val fragmentSrc = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderFragment.kt").readText(),
        )
        val vmSrc = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderViewModel.kt").readText(),
        )
        // Lazy: Fragment.onCreate must NOT trigger the launcher directly.
        val onCreateIdx = fragmentSrc.indexOf("override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue("EpubReaderFragment.onCreate must exist", onCreateIdx >= 0)
        // Bound the onCreate body at the next function declaration so we don't
        // overlap helper functions that legitimately reference the launcher.
        val afterOnCreate = onCreateIdx + "override fun onCreate(savedInstanceState: Bundle?)".length
        val nextFunIdx = listOf(
            fragmentSrc.indexOf("    override fun ", afterOnCreate),
            fragmentSrc.indexOf("    private fun ", afterOnCreate),
            fragmentSrc.indexOf("    fun ", afterOnCreate),
        ).filter { it >= 0 }.minOrNull() ?: fragmentSrc.length
        val onCreateBody = fragmentSrc.substring(onCreateIdx, nextFunIdx)
        assertFalse(
            "EpubReaderFragment.onCreate must NOT launch postNotificationsLauncher directly " +
                "— the prompt is lazy, triggered on first startTts() (ADR-AND-W §Lazy permission)",
            onCreateBody.contains("postNotificationsLauncher.launch("),
        )
        assertFalse(
            "EpubReaderFragment.onCreate must NOT call requestPostNotificationsIfNeeded — that " +
                "API was removed; the VM emits permissionRequestEvents lazily (ADR-AND-W §Lazy permission)",
            onCreateBody.contains("requestPostNotificationsIfNeeded("),
        )
        // VM emits the request event; Fragment forwards to the launcher.
        assertTrue(
            "EpubReaderViewModel must expose permissionRequestEvents so the Fragment can " +
                "trigger the system dialog lazily (ADR-AND-W §Lazy permission)",
            vmSrc.contains("val permissionRequestEvents"),
        )
        assertTrue(
            "EpubReaderFragment must observe permissionRequestEvents and forward to " +
                "postNotificationsLauncher.launch(...) (ADR-AND-W §Lazy permission)",
            fragmentSrc.contains("viewModel.permissionRequestEvents.collect") &&
                fragmentSrc.contains("postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)"),
        )
        // One-shot: events flow, not StateFlow<Boolean>. Channel guarantees per-attempt firing.
        assertTrue(
            "EpubReaderViewModel must expose permissionDeniedEvents as a Flow (Channel-backed) " +
                "so the Snackbar fires every attempt, not just first deny (ADR-AND-W §Lazy permission)",
            vmSrc.contains("val permissionDeniedEvents: kotlinx.coroutines.flow.Flow<Unit>"),
        )
        assertFalse(
            "EpubReaderViewModel must NOT expose notificationPermissionDenied as a " +
                "StateFlow<Boolean> — the prior shape only fired Snackbar on first false→true " +
                "transition (ADR-AND-W §Lazy permission)",
            vmSrc.contains("val notificationPermissionDenied: StateFlow<Boolean>"),
        )
        assertTrue(
            "EpubReaderViewModel must emit _permissionDeniedEvents.trySend(Unit) on the " +
                "denial path (ADR-AND-W §Lazy permission)",
            vmSrc.contains("_permissionDeniedEvents.trySend(Unit)"),
        )
    }

    companion object {
        private const val MAX_ANIMATION_MS = 200

        // tween(200) or tween( 200 , ...) — positional first integer argument
        private val TWEEN_POSITIONAL_RE = Regex("""tween\s*\(\s*(\d+)""")
        // tween(durationMillis = 200) — named parameter form
        private val TWEEN_NAMED_RE = Regex("""durationMillis\s*=\s*(\d+)""")

        // Mirrors scripts/check_banned_strings.sh banned list exactly (case-insensitive).
        private val BANNED_COPY_TERMS = listOf(
            "streak", "consecutive", "wrong", "incorrect", "failed", "missed",
            "great job", "keep it up", "daily goal", "🔥", "🏆", "⭐",
        )
    }
}
