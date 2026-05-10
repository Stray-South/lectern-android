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
