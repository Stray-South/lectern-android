package com.straysouth.lectern.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security regression tests for Group A (WebView isolation) and Group B.8 (HTTP client).
 *
 * Covers JVM-testable properties:
 *   A.1 — android:debuggable absent from source AndroidManifest.xml; the accepted JS
 *           logError risk is documented in EpubReaderFragment with a SECURITY A.1 comment
 *   A.2 — EpubBlockingWebViewClient predicate: host != null && host != "readium";
 *           blockingCallbackRegistered guard: read before register, set after
 *   A.3 — EpubReaderFragment does not implement EpubNavigatorFragment.Listener;
 *           createFragmentFactory() receives no listener argument;
 *           EpubBlockingWebViewClient.shouldOverrideUrlLoading() has an explicit
 *           scheme allowlist blocking intent://, market://, etc. (defence-in-depth)
 *   A.4 — ComposeView overlay is added to FrameLayout AFTER navigatorContainer in
 *           onCreateView() — FrameLayout insertion order is the compositing-z guarantee
 *   A.5 — allowContentAccess = false applied unconditionally in wrapWebViewsIn(),
 *           outside the when {} client-wrap branch (all WebViews protected)
 *   A.6 — PublicationRepository.open() surfaces Readium failures via Result.failure()
 *           (never throws); LibraryViewModel emits _importError and import_error_epub_open
 *   A.7 — No main source calls evaluateJavascript() directly; LocatorRepository and
 *           AnchorRepository serialize Locator via toJSON() (JSONObject-escaped), not
 *           string interpolation
 *   B.8 — PublicationRepository wires BlockingHttpClient; no DefaultHttpClient usage
 *
 * Deferred (instrumented or architectural):
 *   A.1 runtime — logcat capture confirming logError output is absent on release APK
 *   A.2 runtime — verify WebView host blocking fires for a real cross-origin request
 *   A.4 GPU     — GPU profiler layer capture confirming CSS z-index cannot lift EPUB
 *                  canvas above the Compose overlay at render time
 *   A.6 runtime — Readium parse of a deliberately malformed EPUB produces Snackbar,
 *                  no crash, and no ANR (requires DefaultPublicationParser + Context)
 *   A.7 runtime — inject a CFI containing script payload and verify no execution
 *                  (requires a live WebView and Readium navigation round-trip)
 *
 * Working-directory assumption: file paths resolve relative to the app/ module directory,
 * which is the default CWD for ./gradlew testDebugUnitTest.
 *
 * See docs/security/RED-TEAM.md sections A and B for full attack descriptions.
 */
class GroupASecurityTest {

    // ── A.1 — android:debuggable absent; accepted JS risk documented ──────────

    /**
     * AGP automatically sets android:debuggable="true" for debug builds and omits it
     * (defaulting to false) for release. An explicit declaration in the source manifest
     * would override AGP and could bless debug mode on release APKs, allowing co-installed
     * apps to read Logcat output including the JS logError interface exposed by Readium.
     */
    @Test
    fun epub_manifest_debuggableFlag_absent() {
        assertFalse(
            "AndroidManifest.xml must not declare android:debuggable — AGP sets this " +
                "automatically per build variant; an explicit declaration would enable " +
                "debug mode on release builds and expose Logcat to co-installed apps (A.1)",
            manifestXml().contains("android:debuggable"),
        )
    }

    /**
     * The SECURITY A.1 comment in EpubReaderFragment documents that JS is intentionally
     * enabled (required by Readium for decorations and MathML) and that the logError
     * risk is accepted on release builds. Pinning the comment prevents a future refactor
     * from silently removing the documentation of an accepted risk.
     */
    @Test
    fun epub_manifest_securityA1_acceptedRiskDocumented() {
        assertTrue(
            "EpubReaderFragment must contain a SECURITY A.1 comment — documents that " +
                "JS is intentionally enabled by Readium and that the logError accepted " +
                "risk has been reviewed; removing the comment hides a known surface (A.1)",
            sourceFile("ui/reader/EpubReaderFragment.kt").contains("SECURITY A.1"),
        )
    }

    // ── A.2 — Blocking WebView client predicate and registration guard ────────

    /**
     * EpubBlockingWebViewClient.shouldInterceptRequest() must use the strict predicate
     * host != null && host != "readium". The null guard is critical: Readium injects
     * CSS and polyfills via data: URIs whose host is null. Blocking null hosts would
     * prevent page rendering. The "readium" check blocks all third-party origins.
     */
    @Test
    fun epub_blockingWebViewClient_predicate_strictHostCheck() {
        val source = sourceFile("ui/reader/EpubBlockingWebViewClient.kt")
        assertTrue(
            "EpubBlockingWebViewClient must use predicate: host != null && host != \"readium\" " +
                "— null guard passes data: URI injections (CSS, polyfills) through; " +
                "the readium check blocks all third-party origins (A.2)",
            source.contains("host != null && host != \"readium\""),
        )
    }

    /**
     * EpubReaderFragment must read blockingCallbackRegistered before registering the
     * FragmentLifecycleCallbacks, and set it to true after. The guard prevents re-registration
     * on every STOPPED->STARTED transition in repeatOnLifecycle, which would accumulate
     * redundant callbacks and leak the anonymous object closures.
     */
    @Test
    fun epub_blockingWebViewClient_registrationGuard_setsAfterRegister() {
        val source = sourceFile("ui/reader/EpubReaderFragment.kt")
        val readGuard = "if (!blockingCallbackRegistered)"
        val setFlag = "blockingCallbackRegistered = true"
        // stripComments() required before indexOf: a SECURITY A.2 comment that mentions
        // either string verbatim would return a comment-line offset, making the ordering
        // assertion pass regardless of the actual code order.
        val codeLines = stripComments(source)
        val readIdx = codeLines.indexOf(readGuard)
        val setIdx = codeLines.indexOf(setFlag)
        assertTrue(
            "EpubReaderFragment must read blockingCallbackRegistered before registering " +
                "the FragmentLifecycleCallbacks (A.2)",
            readIdx >= 0,
        )
        assertTrue(
            "EpubReaderFragment must set blockingCallbackRegistered = true after registering " +
                "the FragmentLifecycleCallbacks (A.2)",
            setIdx >= 0,
        )
        assertTrue(
            "blockingCallbackRegistered must be read before it is set — the guard check " +
                "must precede the set assignment so every repeat cycle is guarded (A.2)",
            readIdx < setIdx,
        )
    }

    // ── A.3 — No external link listener ──────────────────────────────────────

    /**
     * EpubReaderFragment must not implement EpubNavigatorFragment.Listener. Implementing
     * the interface would expose onExternalLinkActivated(), which fires for intent://,
     * market://, and other non-http scheme links in EPUB content. Without the interface,
     * the callback is never routed to our code and no startActivity() can fire from EPUB.
     *
     * Two properties verified:
     *   1. The class declaration does not include .Listener in its supertype list.
     *   2. createFragmentFactory() is called without a listener argument — no null listener
     *      that would be replaced in a future commit.
     */
    @Test
    fun epub_noExternalLinkListener_classDeclaresNoListenerInterface() {
        val source = sourceFile("ui/reader/EpubReaderFragment.kt")
        // stripComments() required: EpubReaderFragment has a SECURITY A.3 comment that
        // mentions EpubNavigatorFragment.Listener by name. Without stripping, the
        // assertFalse would fail because the term appears in a documentation comment,
        // not in an implements clause or function argument.
        val codeLines = stripComments(source)
        assertFalse(
            "EpubReaderFragment must not implement EpubNavigatorFragment.Listener — " +
                "the interface exposes onExternalLinkActivated() which fires for " +
                "intent:// and market:// EPUB links and could launch arbitrary Intents (A.3)",
            codeLines.contains("EpubNavigatorFragment.Listener"),
        )
        assertTrue(
            "createFragmentFactory() must not pass a listener argument — a null or stub " +
                "listener stub could be replaced in future, restoring the Intent launch surface; " +
                "absence of the parameter is the correct V1 posture (A.3)",
            codeLines.contains("createFragmentFactory(") &&
                !codeLines.contains("createFragmentFactory(listener"),
        )
    }

    /**
     * Defence-in-depth for A.3: [EpubBlockingWebViewClient.shouldOverrideUrlLoading]
     * must explicitly block non-http/https schemes before delegating to Readium.
     * The null-listener pattern prevents [onExternalLinkActivated] from firing for
     * `<a href="intent://...">` clicks, but JS-initiated top-frame navigations
     * (`window.location = "intent://..."`) may not reach that path on all Android
     * versions. An explicit scheme allowlist closes this gap independently.
     *
     * Two properties verified:
     *   1. [ALLOWED_NAVIGATION_SCHEMES] constant is defined containing "https" and "http".
     *   2. [shouldOverrideUrlLoading] body contains the scheme guard and [return true].
     */
    @Test
    fun epub_blockingWebViewClient_shouldOverrideUrlLoading_schemeDenylist() {
        val source = sourceFile("ui/reader/EpubBlockingWebViewClient.kt")
        assertTrue(
            "EpubBlockingWebViewClient must define ALLOWED_NAVIGATION_SCHEMES " +
                "containing \"https\" — all Readium internal links use https://readium/... (A.3)",
            source.contains("ALLOWED_NAVIGATION_SCHEMES") &&
                source.contains("\"https\""),
        )
        assertTrue(
            "EpubBlockingWebViewClient must define ALLOWED_NAVIGATION_SCHEMES " +
                "containing \"http\" (A.3)",
            source.contains("\"http\""),
        )
        val fnIdx = source.indexOf("override fun shouldOverrideUrlLoading(")
        assertTrue("shouldOverrideUrlLoading not found in EpubBlockingWebViewClient.kt", fnIdx >= 0)
        val nextIdx = nextClassMemberIndex(source, fnIdx)
        val body = source.substring(fnIdx, nextIdx)
        assertTrue(
            "shouldOverrideUrlLoading must check scheme !in ALLOWED_NAVIGATION_SCHEMES " +
                "to block intent://, market://, javascript: and other non-http schemes (A.3)",
            body.contains("scheme !in ALLOWED_NAVIGATION_SCHEMES"),
        )
        assertTrue(
            "shouldOverrideUrlLoading must return true (consumed) for blocked schemes — " +
                "returning false would allow the WebView's default handler to process the URL (A.3)",
            body.contains("return true"),
        )
    }

    // ── A.4 — ComposeView overlay added after navigatorContainer ─────────────

    /**
     * FrameLayout draws children in insertion order (last child on top). The ComposeView
     * overlay (toolbar, TTS bar) must be added AFTER the navigatorContainer (which hosts
     * the Readium WebView). This ensures the toolbar always renders above EPUB content
     * regardless of any CSS z-index or position: fixed rule in the EPUB stylesheet.
     *
     * Uses lastIndexOf for both targets so that a hypothetical second addView call (e.g.,
     * in a helper that is extracted in a future refactor) does not produce a false pass.
     */
    @Test
    fun epub_overlay_addedAfterNavigatorContainer_zOrderCorrect() {
        val source = sourceFile("ui/reader/EpubReaderFragment.kt")
        val navigatorToken = "addView(navigatorContainer"
        val overlayToken = "addView(overlay"
        val navigatorIdx = source.lastIndexOf(navigatorToken)
        val overlayIdx = source.lastIndexOf(overlayToken)
        assertTrue(
            "addView(navigatorContainer) not found in EpubReaderFragment (A.4)",
            navigatorIdx >= 0,
        )
        assertTrue(
            "addView(overlay) not found in EpubReaderFragment (A.4)",
            overlayIdx >= 0,
        )
        assertTrue(
            "ComposeView overlay must be added to FrameLayout AFTER navigatorContainer — " +
                "FrameLayout draws children in insertion order; overlay added last renders " +
                "above the WebView, making CSS z-index elevation from EPUB ineffective (A.4)",
            navigatorIdx < overlayIdx,
        )
    }

    // ── A.5 — allowContentAccess = false unconditional ───────────────────────

    /**
     * wrapWebViewsIn() must set allowContentAccess = false on every WebView regardless of
     * which branch of the when {} client-wrap block runs. The assignment must appear AFTER
     * the when {} block so it executes even when the client type is unexpected and the
     * else branch only logs a warning without wrapping.
     *
     * Ordering check: the EpubBlockingWebViewClient branch token (inside the when block)
     * must precede the allowContentAccess assignment, confirming the when block appears
     * before the unconditional security setting.
     */
    @Test
    fun epub_wrapWebViews_allowContentAccess_false_unconditional() {
        val source = sourceFile("ui/reader/EpubReaderFragment.kt")
        val accessToken = "root.settings.allowContentAccess = false"
        val whenBranchToken = "is EpubBlockingWebViewClient"
        assertTrue(
            "wrapWebViewsIn() must set root.settings.allowContentAccess = false (A.5)",
            source.contains(accessToken),
        )
        // stripComments() required before indexOf: a KDoc or inline comment mentioning
        // "is EpubBlockingWebViewClient" earlier in the file would return a comment-line
        // offset, making the ordering assertion pass regardless of actual code order.
        val codeLines = stripComments(source)
        val whenIdx = codeLines.indexOf(whenBranchToken)
        val accessIdx = codeLines.indexOf(accessToken)
        assertTrue(
            "is EpubBlockingWebViewClient branch not found in EpubReaderFragment (A.5)",
            whenIdx >= 0,
        )
        assertTrue(
            "allowContentAccess = false must appear AFTER the when {} block in wrapWebViewsIn() " +
                "— placement inside a when branch would skip the setting when the client type " +
                "does not match, leaving content:// access enabled on unexpected Readium builds (A.5)",
            whenIdx < accessIdx,
        )
    }

    // ── A.6 — EPUB parse errors surfaced via Result, not exceptions ───────────

    /**
     * PublicationRepository.open() must handle all Readium failures via Result.failure()
     * so the caller (LibraryViewModel) never receives an uncaught exception from the
     * Readium parse pipeline. An unhandled exception would crash the import coroutine and
     * leave the UI stuck on the loading spinner with no error message.
     *
     * Two properties verified:
     *   1. Result.failure( is present — both fold branches return failure, not throw.
     *   2. No bare throw statement as a top-level line — all Kotlin checked paths use
     *      the Result monad.
     */
    @Test
    fun epub_publicationRepository_openFailure_returnsResultFailure_notThrows() {
        val source = sourceFile("data/repository/PublicationRepository.kt")
        assertTrue(
            "PublicationRepository.open() must surface Readium failures via Result.failure() " +
                "— an uncaught exception crashes the import coroutine and leaves no UI feedback " +
                "for AuDHD users who cannot tell whether the import failed (A.6)",
            source.contains("Result.failure("),
        )
        val codeLines = stripComments(source)
        assertFalse(
            "PublicationRepository must not use bare throw — all failures must be " +
                "Result.failure() so the caller can handle them without try-catch (A.6)",
            codeLines.lines().any { it.trimStart().startsWith("throw ") },
        )
    }

    /**
     * LibraryViewModel must emit _importError state on EPUB open failure and must use
     * the import_error_epub_open string resource. A missing _importError emission means
     * the user sees no feedback on import failure — a silent no-op that is especially
     * harmful for AuDHD users who may not notice the book did not appear in the library.
     */
    @Test
    fun epub_libraryViewModel_importError_emittedOnFailure() {
        val source = sourceFile("ui/library/LibraryViewModel.kt")
        assertTrue(
            "LibraryViewModel must declare and emit _importError on EPUB open failure — " +
                "silent failure with no UI feedback traps AuDHD users who may not notice " +
                "the book failed to import (A.6)",
            source.contains("_importError"),
        )
        assertTrue(
            "LibraryViewModel must use import_error_epub_open string resource on EPUB open " +
                "failure — the error message must be specific, not a generic fallback (A.6)",
            source.contains("import_error_epub_open"),
        )
    }

    // ── A.7 — No direct evaluateJavascript; locator serialization via toJSON() ─

    /**
     * Our main sources must never call evaluateJavascript() directly. All JS execution
     * is routed through Readium internals which use JSONObject escaping. A direct call
     * that interpolates a Locator CFI string into a JS snippet would bypass the JSONObject
     * escaping and create an injection surface exploitable by a crafted CFI payload.
     */
    @Test
    fun epub_noDirectEvaluateJavascript_inMainSources() {
        val mainSources = File("src/main/kotlin")
        assertTrue(
            "src/main/kotlin not found (working dir: ${System.getProperty("user.dir")})",
            mainSources.exists(),
        )
        val violations = mainSources.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file -> stripComments(file.readText()).contains("evaluateJavascript(") }
            .map { it.name }
            .toList()
        assertTrue(
            "No main-source file must call evaluateJavascript() directly — all JS execution " +
                "must go through Readium internals which use JSONObject escaping; a direct " +
                "call with locator data would create a CFI injection surface (A.7):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * LocatorRepository and AnchorRepository must serialize Locator values via toJSON()
     * (Readium's safe serialization path that escapes injection characters via JSONObject)
     * and must not use Kotlin string templates with locator variables. A string template
     * such as "$locator" or "${locator." written directly into a DataStore value that is
     * later re-used in a JS context would bypass JSONObject escaping.
     *
     * Both repositories are checked: each is a DataStore write path for Locator data.
     */
    @Test
    fun epub_locatorSerialization_usesToJson_notStringInterpolation() {
        val locatorSource = sourceFile("data/repository/LocatorRepository.kt")
        val anchorSource = sourceFile("data/repository/AnchorRepository.kt")

        assertTrue(
            "LocatorRepository must serialize Locator via toJSON() — JSONObject.toString() " +
                "escapes injection characters; string interpolation of locator data would " +
                "bypass escaping if the value is later used in a JS context (A.7)",
            locatorSource.contains("toJSON()"),
        )
        assertFalse(
            "LocatorRepository must not use string interpolation with locator variable (A.7)",
            locatorSource.contains("\$locator") || locatorSource.contains("\${locator"),
        )

        assertTrue(
            "AnchorRepository must serialize Locator via toJSON() (A.7)",
            anchorSource.contains("toJSON()"),
        )
        assertFalse(
            "AnchorRepository must not use string interpolation with locator variable (A.7)",
            anchorSource.contains("\$locator") || anchorSource.contains("\${locator"),
        )
    }

    // ── B.8 — BlockingHttpClient: no DefaultHttpClient in PublicationRepository ─

    /**
     * PublicationRepository must wire BlockingHttpClient as the HTTP implementation
     * so Readium cannot make outbound HTTPS requests when parsing EPUBs that reference
     * remote OPF URLs. If DefaultHttpClient (Readium's bundled HTTP implementation) were
     * used, a crafted EPUB could silently trigger network calls on import.
     *
     * stripComments() is applied before the DefaultHttpClient absence check: BlockingHttpClient
     * itself has a KDoc that compares against DefaultHttpClient by name. If the repository
     * source ever imports BlockingHttpClient's file, the comment must not produce a false fail.
     */
    @Test
    fun epub_publicationRepository_blockingHttpClient_noDefaultHttpClient() {
        val source = sourceFile("data/repository/PublicationRepository.kt")
        assertTrue(
            "PublicationRepository must wire BlockingHttpClient — Readium's DefaultHttpClient " +
                "would allow outbound HTTPS requests from crafted EPUB OPF references (B.8)",
            source.contains("BlockingHttpClient"),
        )
        assertFalse(
            "PublicationRepository must not reference DefaultHttpClient — its presence means " +
                "Readium can make outbound HTTP calls during EPUB import (B.8)",
            stripComments(source).contains("DefaultHttpClient"),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Returns the index of the next class-member declaration after [afterIdx], used to
    // bound the extracted body of a function for targeted source assertions.
    // Patterns cover the 4-space-indented member forms present in production classes.
    // companion object is included so the boundary is found even when it follows the
    // target function (e.g. EpubBlockingWebViewClient.shouldOverrideUrlLoading →
    // onPageFinished, which is an override fun and found first; companion object is
    // still listed for completeness when the target is the last override).
    private fun nextClassMemberIndex(source: String, afterIdx: Int): Int =
        listOf(
            "\n    fun ", "\n    private fun ", "\n    override fun ",
            "\n    internal fun ", "\n    companion object", "\n    private val ",
            "\n    private var ",
        )
            .mapNotNull { pattern ->
                source.indexOf(pattern, afterIdx + 1).takeIf { it > afterIdx }
            }
            .minOrNull() ?: source.length

    // Strips single-line comments (//), KDoc body lines (*), and block-comment openers
    // from source before string checks. Prevents comment-only matches from producing
    // false positives or false negatives in security assertions.
    // Also strips inline comment tails via substringBefore("//") so that a token
    // appearing only after a // on a code line does not count.
    private fun stripComments(source: String): String =
        source.lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .map { it.substringBefore("//") }
            .joinToString("\n")

    private fun manifestXml(): String {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue(
            "AndroidManifest.xml not found (working dir: ${System.getProperty("user.dir")})",
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
