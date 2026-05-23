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
