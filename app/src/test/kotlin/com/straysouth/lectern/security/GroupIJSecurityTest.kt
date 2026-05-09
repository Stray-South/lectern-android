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
