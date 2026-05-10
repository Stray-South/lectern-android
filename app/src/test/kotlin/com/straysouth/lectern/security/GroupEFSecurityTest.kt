package com.straysouth.lectern.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security regression tests for Group E (TTS privacy) and Group F (supply chain).
 *
 * Covers JVM-testable properties:
 *   E.1 — EpubReaderViewModel.onCleared() calls cleanUpTts(); no onPause() TTS stop exists
 *           (background-stop gap documented — deferred to instrumented)
 *   E.2 — No annotation Room entity in V1; TTS reads Publication content only
 *   E.3 — RECORD_AUDIO and MODIFY_AUDIO_SETTINGS absent from Manifest
 *   E.4 — TtsUiState.EngineUnavailable emitted in ≥ 2 paths in startTts(); TtsBar
 *           shows message + dismiss — no silent no-op when engine missing
 *   F.1 — All 5 external deps (Readium, zip4j, junrar, EJML, MediaPipe) pinned to
 *           exact versions in libs.versions.toml; no floating "+" or "latest." constraints
 *   F.2 — CVE-2021-40870: PublicationRepository delegates entirely to Readium's
 *           AssetRetriever/PublicationOpener; no ZipFile/ZipInputStream/File( call
 *           present that could reintroduce a disk-write extraction path
 *   F.3 — BlockingHttpClient is wired into both AssetRetriever and
 *           DefaultPublicationParser; mere presence is insufficient — both Readium
 *           network entry points must receive the blocking implementation
 *   F.4 — zip4j extraction APIs (extractFile, extractAll, extractEntry) never called
 *           in main sources; entries served via getInputStream() only
 *
 * Deferred (instrumented):
 *   E.1 runtime — TTS audio-focus release when app is backgrounded
 *   E.3 runtime — verify no RECORD_AUDIO permission is requested at runtime
 *
 * F.6 — gradle/verification-metadata.xml absent (⚠️ known V1 gap; exact pins + CI
 *         accepted as sufficient; add checksums before V2 public beta). No test written:
 *         a assertTrue would permanently red CI; assertFalse would bless a missing control.
 *
 * See docs/security/RED-TEAM.md §E and §F for full attack descriptions.
 *
 * Working-directory assumption: file paths resolve relative to the `app/` module
 * directory, which is the default CWD for `./gradlew testDebugUnitTest`.
 */
class GroupEFSecurityTest {

    // ── E.1 — TTS teardown on ViewModel clear ────────────────────────────────

    /**
     * [EpubReaderFragment] has no [onPause] override that stops TTS — a background-stop
     * gap deferred to instrumented tests. [EpubReaderViewModel.onCleared] is therefore
     * the last-resort TTS teardown: it fires when the Activity finishes (back-press,
     * swipe-away from Recents) and closes the TTS navigator. Pinning this prevents a
     * future refactor from accidentally omitting the call.
     */
    @Test
    fun tts_onCleared_callsCleanUpTts() {
        val source = sourceFile("ui/reader/EpubReaderViewModel.kt")
        val fnIdx = source.indexOf("override fun onCleared()")
        assertTrue("onCleared() not found in EpubReaderViewModel.kt", fnIdx >= 0)
        val nextFunIdx = nextClassMemberIndex(source, fnIdx)
        val body = source.substring(fnIdx, nextFunIdx)
        assertTrue(
            "EpubReaderViewModel.onCleared() must call cleanUpTts() to close the TTS " +
                "navigator on Activity finish — no onPause() hook stops TTS on background; " +
                "this is the last-resort teardown (E.1)",
            body.contains("cleanUpTts()"),
        )
    }

    // ── E.2 — No annotation feature in V1 ────────────────────────────────────

    /**
     * [TtsNavigator] reads [Publication] content only. A future V2 annotation feature
     * must not accidentally cause TTS to speak user-written text. Pinning the
     * [AppDatabase] entity registry ensures any annotation entity added there triggers
     * a test failure, prompting a deliberate TTS-routing review.
     *
     * Note: [AnchorRepository] stores a navigation [Locator] (return-to position), not
     * user-written text — it is not an annotation feature and is correctly excluded here.
     */
    @Test
    fun tts_noAnnotationFeatureInV1() {
        val source = sourceFile("data/db/AppDatabase.kt")
        val annotationTerms = listOf("Annotation", "Highlight", "UserNote")
        val violations = annotationTerms.filter { term -> source.contains(term) }
        assertTrue(
            "AppDatabase must not register annotation-related Room entities in V1 — " +
                "TTS reads Publication content only; no annotation text path must exist (E.2):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── E.3 — No microphone permission ───────────────────────────────────────

    /**
     * TTS is output-only. Neither [RECORD_AUDIO] nor [MODIFY_AUDIO_SETTINGS] should
     * be declared — their presence would be an over-privileged permission grant and
     * would enable microphone access not required by any V1 feature.
     */
    @Test
    fun tts_noMicrophonePermissionRequested() {
        val manifest = manifestXml()
        assertFalse(
            "AndroidManifest.xml must not declare RECORD_AUDIO — TTS is output-only; " +
                "microphone access would be a privacy regression (E.3)",
            manifest.contains("RECORD_AUDIO"),
        )
        assertFalse(
            "AndroidManifest.xml must not declare MODIFY_AUDIO_SETTINGS (E.3)",
            manifest.contains("MODIFY_AUDIO_SETTINGS"),
        )
    }

    // ── E.4 — Engine unavailable: no silent no-op ────────────────────────────

    /**
     * [EpubReaderViewModel.startTts] must emit [TtsUiState.EngineUnavailable] in at
     * least two defensive paths:
     *   1. [ttsFactory == null] — no TTS engine installed at all (e.g., Samsung One UI 7+)
     *   2. [createNavigator().onFailure] — engine initialisation race or device-level failure
     *
     * Pinning occurrence count ≥ 2 guards both paths simultaneously.
     */
    @Test
    fun tts_engineUnavailable_viewModel_emitsState() {
        val source = sourceFile("ui/reader/EpubReaderViewModel.kt")
        val fnIdx = source.indexOf("fun startTts(")
        assertTrue("startTts() not found in EpubReaderViewModel.kt", fnIdx >= 0)
        val nextFunIdx = nextClassMemberIndex(source, fnIdx)
        val body = source.substring(fnIdx, nextFunIdx)
        val occurrences = body.split("TtsUiState.EngineUnavailable").size - 1
        assertTrue(
            "EpubReaderViewModel.startTts() must emit TtsUiState.EngineUnavailable in " +
                "at least two paths: factory == null AND createNavigator() onFailure — " +
                "both paths must surface the state, not silently no-op (E.4)",
            occurrences >= 2,
        )
    }

    /**
     * [TtsBar] must explicitly handle [TtsUiState.EngineUnavailable] with a visible
     * message and a dismiss action. Silent no-op (e.g., play button that does nothing)
     * would be invisible to AuDHD users who may assume TTS is broken by their settings.
     */
    @Test
    fun tts_engineUnavailable_ttsBar_showsMessageNotSilentNoOp() {
        val source = sourceFile("ui/reader/TtsBar.kt")
        val branchIdx = source.indexOf("TtsUiState.EngineUnavailable")
        assertTrue(
            "TtsBar must explicitly branch on TtsUiState.EngineUnavailable (E.4)",
            branchIdx >= 0,
        )
        // Extract a window from the EngineUnavailable token — covers the branch body.
        // The parameter declaration (onDismissUnavailable) appears BEFORE this token in
        // the function signature, so this window exclusively targets the branch interior.
        val branchWindow = source.substring(branchIdx, (branchIdx + 600).coerceAtMost(source.length))
        assertTrue(
            "TtsBar.EngineUnavailable branch must display tts_engine_unavailable string (E.4)",
            branchWindow.contains("tts_engine_unavailable"),
        )
        assertTrue(
            "TtsBar.EngineUnavailable branch must wire onDismissUnavailable to the dismiss " +
                "action — passing it as a parameter is insufficient (E.4)",
            branchWindow.contains("onDismissUnavailable"),
        )
    }

    // ── F.1 — External dependency version pins ────────────────────────────────

    /**
     * All five external dependencies with meaningful attack surface must be pinned to
     * exact versions — no floating "+" or "latest." constraints. A compromised transitive
     * upgrade could silently introduce a vulnerable version.
     *
     * Exact pins verified against MASVS-CODE-5. The floating-version guard additionally
     * catches any new dep added to the catalog with a non-exact pin.
     */
    @Test
    fun supply_allExternalDeps_versionPinned_notFloating() {
        val catalog = versionCatalog()
        listOf(
            "readium" to "3.1.2",
            "zip4j" to "2.11.6",
            "junrar" to "7.5.7",
            "ejml" to "0.44.0",
            "mediapipe" to "0.10.35",
        ).forEach { (dep, version) ->
            assertTrue(
                "$dep must be pinned to \"$version\" in libs.versions.toml (F.1)",
                catalog.contains("$dep = \"$version\""),
            )
        }
        // TOML comment character is '#'. Also filter '//' defensively (invalid TOML
        // but editorially plausible). Any non-comment line with '+' or 'latest.' is a
        // floating constraint.
        val floatingLines = catalog.lines()
            .filterNot { it.trimStart().startsWith("#") || it.trimStart().startsWith("//") }
            .filter { "+" in it || "latest." in it }
        assertTrue(
            "libs.versions.toml must contain no floating version constraints — " +
                "supply chain integrity requires exact pins (F.1):\n" +
                floatingLines.joinToString("\n"),
            floatingLines.isEmpty(),
        )
    }

    // ── F.3 — BlockingHttpClient wired into both Readium network entry points ──

    /**
     * [BlockingHttpClient] prevents Readium from making outbound HTTPS calls during
     * EPUB open. Its existence alone (tested by B.8) is insufficient — if it were
     * declared but not passed to [AssetRetriever] or [DefaultPublicationParser], both
     * would fall back to [DefaultHttpClient] and make outbound requests.
     *
     * Two wiring points must be confirmed in [PublicationRepository]:
     *   1. [AssetRetriever] receives [httpClient] — blocks the asset-retrieval layer.
     *   2. [DefaultPublicationParser] receives `httpClient = httpClient` — blocks the
     *      parser layer. Both must be blocked; a gap in either allows outbound requests
     *      on any EPUB that references a remote OPF or resource URL.
     *
     * Exact token strategy: assert the assignment `httpClient = BlockingHttpClient`
     * (private field initialisation) and the two call-site tokens that follow it.
     * Comment-stripped source prevents a KDoc or inline comment from satisfying the
     * assertions.
     */
    @Test
    fun supply_readium_blockingHttpClient_wiredIntoBothNetworkEntryPoints() {
        val source = stripComments(sourceFile("data/repository/PublicationRepository.kt"))

        // Field assignment — BlockingHttpClient is the implementation, not just an import.
        assertTrue(
            "PublicationRepository must assign httpClient = BlockingHttpClient — " +
                "BlockingHttpClient present as an import but not assigned provides no " +
                "network protection; Readium would use DefaultHttpClient (F.3)",
            source.contains("httpClient = BlockingHttpClient"),
        )
        // AssetRetriever wiring — first Readium network entry point.
        // Exact argument form asserted: a bare source.contains("httpClient") would be
        // satisfied by the field declaration and not catch AssetRetriever being unwired.
        assertTrue(
            "AssetRetriever must receive httpClient as its second argument — without this " +
                "wiring AssetRetriever uses DefaultHttpClient and can make outbound requests " +
                "to fetch remote assets referenced in EPUB OPF manifests (F.3)",
            source.contains("AssetRetriever(appContext.contentResolver, httpClient)"),
        )
        // DefaultPublicationParser wiring — second Readium network entry point.
        // The named-parameter form `httpClient = httpClient` confirms the argument is
        // passed to the parser, not just present somewhere in the file.
        assertTrue(
            "DefaultPublicationParser must receive httpClient = httpClient — without " +
                "this wiring the parser layer uses DefaultHttpClient and can fetch remote " +
                "resources during EPUB parsing (F.3)",
            source.contains("httpClient = httpClient"),
        )
    }

    // ── F.4 — zip4j: no extraction-to-disk API ───────────────────────────────

    /**
     * zip4j's [ZipFile.extractFile], [extractAll], and [extractEntry] write archive
     * entries to a caller-supplied directory. If called with an attacker-controlled
     * entry name containing "../", this enables path traversal (CWE-22). Lectern uses
     * only [ZipFile.getInputStream] — entries are served as streams, never extracted.
     *
     * This test is a global regression guard: any future use of the extraction API
     * anywhere in main sources must be a conscious, reviewed decision.
     */
    @Test
    fun supply_zip4j_noExtractionApiCalls_inMainSources() {
        val mainSources = File("src/main/kotlin")
        assertTrue(
            "src/main/kotlin not found (working dir: ${System.getProperty("user.dir")})",
            mainSources.exists(),
        )
        val extractionApis = listOf("extractFile(", "extractAll(", "extractEntry(")
        val violations = mainSources.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                extractionApis
                    .filter { api -> text.contains(api) }
                    .map { api -> "${file.name}: $api" }
            }
            .toList()
        assertTrue(
            "zip4j extraction APIs must never be called — entries are served via " +
                "getInputStream() only; extraction to disk enables path-traversal (F.4):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── F.2 — CVE-2021-40870: no zip extraction path in PublicationRepository ──

    /**
     * CVE-2021-40870 (Ralinktech / AnyConnect) exploited `ZipFile.extractAll()` with
     * attacker-controlled entry names containing `../`. The Readium Kotlin SDK 3.x is
     * not directly affected (different codebase), but an incorrect integration that
     * adds a direct `ZipFile`/`ZipInputStream` call inside [PublicationRepository]
     * would reintroduce the disk-write path.
     *
     * [PublicationRepository.open()] delegates entirely to Readium's [AssetRetriever]
     * and [PublicationOpener], which stream archive entries in-memory without disk
     * extraction. None of the six extraction-related API tokens must appear.
     *
     * Note: `File(` is checked as `" File("` and `"(File("` (space- or paren-anchored) to
     * avoid matching identifiers that end in `File` (e.g., `SourceFile(`), and via
     * comment-stripped source to avoid a comment such as `// never pass to File(...)` from
     * producing a false-pass.
     */
    @Test
    fun supply_readium_cve202140870_noExtractionApiInPublicationRepository() {
        val source = stripComments(sourceFile("data/repository/PublicationRepository.kt"))
        listOf(
            "ZipFile", "ZipInputStream", "ZipContainer",
            "extractAll", "extractFile", "extractEntry",
            " File(", "(File(",
        ).forEach { api ->
            assertFalse(
                "PublicationRepository must not use $api — CVE-2021-40870 exploited " +
                    "zip extraction APIs that wrote attacker-controlled entry names to " +
                    "disk; Readium 3.x AssetRetriever/PublicationOpener stream entries " +
                    "in-memory; direct archive access here would reintroduce the disk-write " +
                    "path (F.2)",
                source.contains(api),
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the index of the next class-member function declaration after [afterIdx],
     * or [source.length] if none exists. Matches all modifier prefixes (plain, private,
     * override, internal) to avoid silently expanding a function body when a
     * `private fun` or `override fun` follows the target function.
     */
    private fun nextClassMemberIndex(source: String, afterIdx: Int): Int =
        listOf("\n    fun ", "\n    private fun ", "\n    override fun ", "\n    internal fun ")
            .mapNotNull { pattern ->
                source.indexOf(pattern, afterIdx + 1).takeIf { it > afterIdx }
            }
            .minOrNull() ?: source.length

    private fun stripComments(source: String): String =
        source.lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    private fun manifestXml(): String {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue(
            "AndroidManifest.xml not found (working dir: ${System.getProperty("user.dir")})",
            file.exists(),
        )
        return file.readText()
    }

    private fun versionCatalog(): String {
        val file = File("../gradle/libs.versions.toml")
        assertTrue(
            "libs.versions.toml not found at ../gradle/ " +
                "(working dir: ${System.getProperty("user.dir")})",
            file.exists(),
        )
        return file.readText()
    }

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
