package com.straysouth.lectern.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security regression tests for Group I (coroutine / threading model) and
 * Group J (gaze-data privacy — JVM-testable subset).
 *
 * Covers JVM-testable properties:
 *   I.1 — GazeProviderImpl uses Dispatchers.Default.limitedParallelism(1) for all
 *           gaze processing; calibrate() runs withContext(confined) — no unbounded
 *           thread pool that could produce out-of-order frame analysis
 *   I.2 — AppDatabase does not permit main-thread queries; allowMainThreadQueries()
 *           absent from the database builder
 *   I.3 — EpubReaderViewModel.cleanUpTts() cancels _ttsCollectionJob directly (not
 *           inside a launch {}); a sibling coroutine would race the cancel on Activity
 *           finish, leaving the TTS navigator open past onCleared()
 *   J.1 — CalibrationRepository.save() stores only weightsX / weightsY; raw iris
 *           UV coordinates (irisU, irisV) are never persisted to DataStore
 *
 * Deferred (instrumented):
 *   I.4 — verify coroutine scope cancellation propagates correctly to TTS navigator
 *           on low-memory process kill (DeathRecipient / onTrimMemory)
 *   I.5 — verify analysisExecutor.shutdown() is called before process exit under
 *           thread-leak detection (StrictMode / LeakCanary)
 *   J.2 — runtime logcat capture: no irisU/irisV in any log output during live
 *           calibration (covered structurally by GroupDSecurityTest.D.3)
 *   J.3 — verify ARFaceAnchor transform matrices are never written to disk or network
 *   J.4 — verify gaze heatmap data is in-memory only, dropped on Activity finish
 *
 * See docs/security/RED-TEAM.md §I and §J for full attack descriptions.
 *
 * Working-directory assumption: file paths resolve relative to the `app/` module
 * directory, which is the default CWD for `./gradlew testDebugUnitTest`.
 */
class GroupIJSecurityTest {

    // ── I.1 — Gaze pipeline: single-threaded confined dispatcher ─────────────

    /**
     * [GazeProviderImpl] must confine gaze frame analysis to a single thread via
     * [Dispatchers.Default.limitedParallelism(1)]. Without this:
     *   - MediaPipe callbacks arriving on different threads race for the confined scope
     *   - Out-of-order frame processing produces incorrect gaze predictions
     *   - [CalibrationResult] could be read mid-write without the serial ordering guarantee
     *
     * [withContext(confined)] in [calibrate()] confirms the confined dispatcher is
     * actually used for the security-critical calibration path, not just declared.
     */
    @Test
    fun coroutines_gazeProvider_confinedSingleThreadDispatcher() {
        val codeLines = stripComments(sourceFile("gaze/GazeProviderImpl.kt"))
        assertTrue(
            "GazeProviderImpl must use Dispatchers.Default.limitedParallelism(1) to " +
                "confine gaze frame analysis to a single thread — unbounded parallelism " +
                "allows out-of-order frame delivery and calibration data races (I.1)",
            codeLines.contains("limitedParallelism(1)"),
        )
        assertTrue(
            "GazeProviderImpl.calibrate() must switch to the confined dispatcher via " +
                "withContext(confined) — declaring the dispatcher without using it for " +
                "calibration leaves the security-critical path unconfined (I.1)",
            codeLines.contains("withContext(confined)"),
        )
    }

    // ── I.2 — Room: no main-thread queries permitted ──────────────────────────

    /**
     * [AppDatabase] must never call [allowMainThreadQueries()]. Main-thread database
     * access produces ANRs on large libraries and, more critically, bypasses the
     * coroutine-structured-concurrency model — a query on the main thread cannot be
     * cancelled and may read stale data after the ViewModel scope is cancelled.
     *
     * This is also a lint/StrictMode violation in production builds.
     */
    @Test
    fun coroutines_room_noMainThreadQueries() {
        assertFalse(
            "AppDatabase must not call allowMainThreadQueries() — main-thread Room " +
                "access produces ANRs and bypasses coroutine cancellation (I.2)",
            sourceFile("data/db/AppDatabase.kt").contains("allowMainThreadQueries"),
        )
    }

    // ── I.3 — TTS teardown: cancel must not race ──────────────────────────────

    /**
     * [EpubReaderViewModel.cleanUpTts] must cancel [_ttsCollectionJob] with a direct
     * call, not inside a [launch {}] block. Rationale: [cleanUpTts()] is called from
     * [onCleared()], which fires when the ViewModel scope is already cancelled. A sibling
     * [launch {}] in a cancelled scope is never scheduled — the cancel becomes a no-op,
     * leaving the TTS navigator open and its background coroutine running past
     * Activity finish.
     *
     * Test strategy: assert the cancel call precedes any [launch {] in the function body.
     * The [viewModelScope.launch] that saves the anchor AFTER the cancel is an expected
     * follow-on operation and is correctly excluded by the ordering assertion.
     */
    @Test
    fun coroutines_ttsCleanUp_cancelsJobDirectly_notInsideLaunch() {
        val source = sourceFile("ui/reader/EpubReaderViewModel.kt")
        val fnIdx = source.indexOf("private fun cleanUpTts()")
        assertTrue("cleanUpTts() not found in EpubReaderViewModel.kt", fnIdx >= 0)
        val nextFunIdx = nextClassMemberIndex(source, fnIdx)
        val body = source.substring(fnIdx, nextFunIdx)

        assertTrue(
            "cleanUpTts() must cancel _ttsCollectionJob — TTS navigator close depends " +
                "on job cancellation completing before the scope is torn down (I.3)",
            body.contains("_ttsCollectionJob?.cancel()"),
        )

        val cancelIdx = body.indexOf("_ttsCollectionJob?.cancel()")
        // Match all launch invocation forms: `launch {`, `launch{`, `launch(...) {`.
        // Using a regex avoids a false negative if the space is omitted or a parameter
        // list is added (e.g. launch(start = CoroutineStart.UNDISPATCHED) {).
        val launchRegex = Regex("""\blaunch\s*(\([^)]*\)\s*)?\{""")
        val firstLaunchIdx = launchRegex.find(body)?.range?.first ?: -1
        // Cancel must appear before any launch in the body; a launch before the cancel
        // means the cancel is wrapped in a coroutine that races scope teardown.
        assertTrue(
            "cleanUpTts() must cancel _ttsCollectionJob before any launch {} — wrapping " +
                "the cancel in a coroutine races the ViewModel scope teardown on " +
                "onCleared() and may leave the TTS navigator running (I.3)",
            firstLaunchIdx == -1 || cancelIdx < firstLaunchIdx,
        )
    }

    // ── J.1 — Calibration storage: weights only, no raw iris coordinates ─────

    /**
     * [CalibrationRepository.save()] must store only [weightsX] and [weightsY].
     * Raw iris UV coordinates ([irisU], [irisV]) must never be written to DataStore:
     *   - Persisting iris UV makes gaze re-identification possible from a backup
     *   - Iris UV is device-geometry-specific; transferring it to a new device would
     *     produce silent miscalibration identical to the D.1 attack surface
     *   - ADR-J: ARFaceAnchor / gaze data is ephemeral in-memory only
     *
     * Scanning the entire [CalibrationRepository] source is correct: this class is
     * the sole DataStore write path for calibration data. Any iris term here means
     * a persistence call was added outside the reviewed save() function.
     */
    @Test
    fun gaze_calibrationRepository_storesOnlyWeights_noRawIrisCoordinates() {
        val source = sourceFile("data/repository/CalibrationRepository.kt")
        val codeLines = stripComments(source)
        assertTrue(
            "CalibrationRepository must store weightsX — absent suggests a refactor " +
                "that may have changed what calibration data is persisted (J.1)",
            codeLines.contains("weightsX"),
        )
        assertTrue(
            "CalibrationRepository must store weightsY (J.1)",
            codeLines.contains("weightsY"),
        )
        // Note: stripComments() prevents a comment such as "// never write irisU" from
        // producing a false pass. Identifier renames (e.g. rawU) are not covered — a
        // future reviewer must assess CalibrationResult fields if the model changes.
        assertFalse(
            "CalibrationRepository must never persist irisU — raw iris UV coordinates " +
                "enable gaze re-identification; only trained weights may be stored (J.1)",
            codeLines.contains("irisU"),
        )
        assertFalse(
            "CalibrationRepository must never persist irisV (J.1)",
            codeLines.contains("irisV"),
        )
    }

    // ── I.4 — finishCalibration() routes both exception types to CalibrationError ──

    /**
     * [GazeViewModel.finishCalibration()] calls [GazeProviderImpl.calibrate()], which
     * throws two distinct exception types on bad data:
     *   - [IllegalArgumentException] from [requireNotNull(provider)] if the provider
     *     is null at calibration time.
     *   - [IllegalStateException] from [ridge()]'s `check(solver.setA(xtx))` guard if
     *     the calibration matrix is degenerate (not positive-definite).
     *
     * Both must be caught and routed to [CalibrationUiState.CalibrationError].
     * A catch clause that only handles one type will let the other propagate to
     * [viewModelScope]'s uncaught-exception handler — crashing the calibration overlay
     * and leaving [GazeProviderImpl] in an undefined state.
     *
     * Test strategy: extract the [finishCalibration()] body and assert all four tokens.
     */
    @Test
    fun coroutines_finishCalibration_catchesBothExceptionTypes_toCalibrationError() {
        val source = sourceFile("ui/gaze/GazeViewModel.kt")
        val fnIdx = source.indexOf("private fun finishCalibration()")
        assertTrue("finishCalibration() not found in GazeViewModel.kt (I.4)", fnIdx >= 0)
        val fnEnd = nextClassMemberIndex(source, fnIdx)
        val body = source.substring(fnIdx, fnEnd)

        assertTrue(
            "finishCalibration() must catch IllegalArgumentException — thrown by " +
                "requireNotNull(provider) when the gaze provider is null at calibration " +
                "time; uncaught, it crashes the overlay (I.4)",
            body.contains("catch (e: IllegalArgumentException)"),
        )
        assertTrue(
            "finishCalibration() must catch IllegalStateException — thrown by " +
                "ridge()'s check(solver.setA(xtx)) guard on degenerate calibration data; " +
                "uncaught, it leaves GazeProviderImpl in an undefined state (I.4)",
            body.contains("catch (e: IllegalStateException)"),
        )
        assertTrue(
            "finishCalibration() must route IllegalArgumentException to " +
                "CalibrationUiState.CalibrationError — without this, the catch is a " +
                "silent swallow that hides calibration failures (I.4)",
            body.contains("CalibrationUiState.CalibrationError"),
        )
        // Both catch blocks must lead to CalibrationError — count occurrences to confirm
        // a single shared assignment is not placed outside both catch clauses.
        val errorOccurrences = body.split("CalibrationUiState.CalibrationError").size - 1
        assertTrue(
            "finishCalibration() must reference CalibrationUiState.CalibrationError in " +
                "both catch blocks — a single reference outside the catches would leave " +
                "one exception type unhandled (I.4)",
            errorOccurrences >= 2,
        )
    }

    // ── I.5 — GazeProviderImpl.stop() shuts down the analysis executor ────────

    /**
     * [GazeProviderImpl.stop()] must call [analysisExecutor.shutdown()] to drain and
     * terminate the CameraX analysis thread. Without an explicit shutdown:
     *   - The executor stays alive after the gaze session ends.
     *   - CameraX may continue delivering frames to a closed [ImageAnalysis].
     *   - Thread-leak detectors (StrictMode, LeakCanary) will flag the runaway thread
     *     in any Activity that finishes while gaze is active.
     *
     * [scope.cancel()] is also asserted — it terminates any in-flight coroutine
     * launched by [calibrate()] or [bindCamera()]. Without it, a suspend point inside
     * [bindCamera()] after [stop()] has been called resumes on a dead scope, producing
     * a [JobCancellationException] propagated to an unattached ViewModel.
     */
    @Test
    fun coroutines_gazeProvider_stop_shutsDownAnalysisExecutor() {
        val source = sourceFile("gaze/GazeProviderImpl.kt")
        val fnIdx = source.indexOf("override suspend fun stop()")
        assertTrue("stop() not found in GazeProviderImpl.kt (I.5)", fnIdx >= 0)
        val fnEnd = nextClassMemberIndex(source, fnIdx)
        val body = source.substring(fnIdx, fnEnd)

        assertTrue(
            "GazeProviderImpl.stop() must call analysisExecutor.shutdown() — without " +
                "explicit shutdown the CameraX analysis thread keeps running after the " +
                "gaze session ends, causing thread leaks under StrictMode / LeakCanary (I.5)",
            body.contains("analysisExecutor.shutdown()"),
        )
        assertTrue(
            "GazeProviderImpl.stop() must call scope.cancel() — without cancellation " +
                "any in-flight suspend in bindCamera() or calibrate() resumes on a dead " +
                "scope and produces an unhandled JobCancellationException (I.5)",
            body.contains("scope.cancel()"),
        )
    }

    // ── J.3 — ridge() guard against degenerate calibration matrix ────────────

    /**
     * [GazeProviderImpl.ridge()] assembles the XᵀX gram matrix from calibration
     * landmark vectors and solves a ridge-regression system. If fewer than 4 distinct
     * calibration points are collected, the matrix is rank-deficient (not
     * positive-definite). An unchecked solve produces a numerically invalid weight
     * vector that silently miscalibrates the gaze model — identical in appearance to
     * the D.1 calibration-poisoning attack.
     *
     * The guard `check(solver.setA(xtx))` in [ridge()] rejects the solve early,
     * throwing [IllegalStateException] which [finishCalibration()] routes to
     * [CalibrationUiState.CalibrationError] (see I.4).
     */
    @Test
    fun gaze_ridge_degenerateCalibrationData_checksPositiveDefinite() {
        val source = sourceFile("gaze/GazeProviderImpl.kt")
        val fnIdx = source.indexOf("private fun ridge(")
        assertTrue("ridge() not found in GazeProviderImpl.kt (J.3)", fnIdx >= 0)
        val fnEnd = nextClassMemberIndex(source, fnIdx)
        val body = stripComments(source.substring(fnIdx, fnEnd))

        assertTrue(
            "ridge() must call check(solver.setA(xtx)) to reject a degenerate " +
                "calibration matrix — without this guard a rank-deficient solve produces " +
                "a silent miscalibration indistinguishable from the D.1 attack (J.3)",
            body.contains("check(solver.setA(xtx))"),
        )
    }

    // ── J.5a — Thermal throttle pauses analysis for all four severe statuses ──

    /**
     * [GazeProviderImpl]'s [thermalListener] must call [pauseAnalysis()] for every
     * SEVERE thermal status. Android defines four statuses above the MODERATE threshold:
     *   [PowerManager.THERMAL_STATUS_SEVERE], [THERMAL_STATUS_CRITICAL],
     *   [THERMAL_STATUS_EMERGENCY], [THERMAL_STATUS_SHUTDOWN].
     *
     * Missing any one of the four allows [ImageAnalysis] to keep delivering frames at
     * peak temperature — risking device throttle, frame drops, or process kill. The
     * test verifies all four constant names appear in the [thermalListener] body and
     * that [pauseAnalysis()] is called, confirming the listener is not a no-op stub.
     */
    @Test
    fun gaze_thermalThrottle_pausesAnalysisForAllSevereStatuses() {
        val source = sourceFile("gaze/GazeProviderImpl.kt")
        // thermalListener is a property (val), not a fun — locate it directly.
        val fnIdx = source.indexOf("private val thermalListener")
        assertTrue("thermalListener not found in GazeProviderImpl.kt (J.5)", fnIdx >= 0)
        val fnEnd = nextClassMemberIndex(source, fnIdx)
        val body = source.substring(fnIdx, fnEnd)

        listOf(
            "THERMAL_STATUS_SEVERE",
            "THERMAL_STATUS_CRITICAL",
            "THERMAL_STATUS_EMERGENCY",
            "THERMAL_STATUS_SHUTDOWN",
        ).forEach { status ->
            assertTrue(
                "thermalListener must handle $status — missing this status allows " +
                    "frame analysis to continue at dangerous device temperatures (J.5)",
                body.contains(status),
            )
        }
        assertTrue(
            "thermalListener must call pauseAnalysis() — without it the thermal " +
                "listener is a no-op stub that provides no actual throttle protection (J.5)",
            body.contains("pauseAnalysis()"),
        )
    }

    // ── J.5b — pauseAnalysis() clears the CameraX analyzer, not just sets state ─

    /**
     * [GazeProviderImpl.pauseAnalysis()] must call [imageAnalysis?.clearAnalyzer()] to
     * detach the [ImageAnalysis.Analyzer] callback from the CameraX pipeline.
     *
     * Simply setting an internal `isPaused` flag and returning early from the analyzer
     * callback is insufficient:
     *   - CameraX continues acquiring and buffering frames from the sensor.
     *   - Under thermal throttle the device heats up further while the app idles.
     *   - A flag-only guard is a single mutable boolean race — the analyzer callback
     *     may read `isPaused = false` before the write propagates across threads.
     *
     * [clearAnalyzer()] is the only safe pause: it stops sensor acquisition entirely.
     */
    @Test
    fun gaze_pauseAnalysis_clearsAnalyzer_notJustSetsState() {
        val source = sourceFile("gaze/GazeProviderImpl.kt")
        val fnIdx = source.indexOf("override fun pauseAnalysis()")
        assertTrue("pauseAnalysis() not found in GazeProviderImpl.kt (J.5)", fnIdx >= 0)
        val fnEnd = nextClassMemberIndex(source, fnIdx)
        val body = source.substring(fnIdx, fnEnd)

        assertTrue(
            "pauseAnalysis() must call imageAnalysis?.clearAnalyzer() — a flag-only " +
                "pause leaves the CameraX sensor running under thermal throttle and is " +
                "subject to cross-thread visibility races on the flag value (J.5)",
            body.contains("imageAnalysis?.clearAnalyzer()"),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the index of the next class-member declaration after [afterIdx], or
     * [source.length] if none exists. Matches function modifiers (plain, private,
     * override, internal), property declarations (val, var), and annotation-prefixed
     * members (@…) to prevent the extracted body from silently growing past a property
     * or annotated member that follows the target function.
     */
    private fun nextClassMemberIndex(source: String, afterIdx: Int): Int =
        listOf(
            "\n    fun ", "\n    private fun ", "\n    override fun ", "\n    internal fun ",
            "\n    override suspend fun ", "\n    private suspend fun ", "\n    suspend fun ",
            "\n    val ", "\n    var ", "\n    private val ", "\n    private var ",
            "\n    @",
        )
            .mapNotNull { pattern ->
                source.indexOf(pattern, afterIdx + 1).takeIf { it > afterIdx }
            }
            .minOrNull() ?: source.length

    // Strips single-line comments (//), KDoc body lines (*), and block-comment
    // openers (slash-star) from [source] before string checks. Prevents false positives
    // where a term appears only in a comment from making a security assertion pass.
    private fun stripComments(source: String): String =
        source.lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
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
}
