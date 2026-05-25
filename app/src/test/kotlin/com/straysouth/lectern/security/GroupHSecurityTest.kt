package com.straysouth.lectern.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security regression tests for Group H (Android platform security).
 *
 * Covers JVM-testable properties:
 *   H.1 — network_security_config.xml exists with cleartextTrafficPermitted="false";
 *           AndroidManifest.xml references it
 *   H.2 — INTERNET permission declared but no real outbound network API usage in
 *           main sources; DefaultHttpClient (B.8 fix) not reverted
 *   H.3 — android.hardware.camera.front android:required="false" (Play Store filter);
 *           GazeViewModel catches IOException and disables gaze on camera start failure
 *   H.4 — No deep-link <data> element in intent-filter; MainActivity does not access
 *           Intent extras
 *   H.5 — No <provider> element in Manifest; Room and DataStore are process-private
 *   H.6 — FLAG_SECURE absent from all main sources (intentional — accessibility tools
 *           require unobstructed screen renders; revisit if auth/annotation ships in V2)
 *
 * Deferred (instrumented):
 *   H.2 runtime — Android Network Profiler: zero outbound connections during full V1 session
 *   H.3 runtime — install on device without front camera, verify graceful degradation
 *
 * See docs/security/RED-TEAM.md §H for full attack descriptions and pass criteria.
 *
 * Working-directory assumption: file paths resolve relative to the `app/` module
 * directory, which is the default CWD for `./gradlew testDebugUnitTest`.
 */
class GroupHSecurityTest {

    // ── H.1 — Network security config ────────────────────────────────────────

    /**
     * Android 9+ blocks cleartext HTTP by default, but minSdk 26 (Android 8) does not.
     * An explicit [network_security_config.xml] with [cleartextTrafficPermitted="false"]
     * extends the block to Android 8 and makes the policy explicit and auditable.
     */
    @Test
    fun platform_networkSecurityConfig_cleartext_blocked() {
        assertTrue(
            "network_security_config.xml must set cleartextTrafficPermitted=\"false\" — " +
                "Android 9+ default blocks cleartext but minSdk 26 (Android 8) does not (H.1)",
            networkSecurityConfigXml().contains("cleartextTrafficPermitted=\"false\""),
        )
    }

    /**
     * The config file is only active if referenced from [android:networkSecurityConfig]
     * in the [<application>] element. A file that exists but is not referenced has no effect.
     */
    @Test
    fun platform_manifest_referencesNetworkSecurityConfig() {
        assertTrue(
            "AndroidManifest.xml must reference android:networkSecurityConfig to activate " +
                "the cleartext block on Android 8 (H.1)",
            manifestXml().contains("networkSecurityConfig"),
        )
    }

    // ── H.2 — INTERNET permission unused in V1 ───────────────────────────────

    /**
     * [INTERNET] is declared for a future Supabase sync (V2). No real network calls
     * must be made in V1 main sources. Scanning for concrete outbound-connection APIs
     * catches accidental network use; [DefaultHttpClient] inclusion would silently
     * revert the B.8 fix that replaced it with [BlockingHttpClient].
     *
     * Terms NOT scanned (would produce false positives):
     *   - "HttpClient" — matches BlockingHttpClient (the security control itself)
     *   - "URL(" — matches AbsoluteUrl( from Readium
     *   - "HttpURLConnection" — class name import, not a call
     */
    @Test
    fun platform_internetPermission_noActualNetworkCalls_inMainSources() {
        val mainSources = File("src/main/kotlin")
        assertTrue(
            "src/main/kotlin not found (working dir: ${System.getProperty("user.dir")})",
            mainSources.exists(),
        )
        val networkApis = listOf(
            "openConnection(",
            "OkHttpClient(",
            "Retrofit.Builder(",
            "DefaultHttpClient",
        )
        val violations = mainSources.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                // Strip comment lines before checking — prevents false positives from
                // comments that reference the API name without calling it (e.g.,
                // BlockingHttpClient.kt KDoc mentions DefaultHttpClient by name).
                // Covers: line comments (//), KDoc body lines (*), and block-comment
                // openers (/* and /**) which are not caught by the '*' prefix filter.
                val codeLines = file.readText().lines()
                    .filterNot {
                        val t = it.trimStart()
                        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                    }
                    .joinToString("\n")
                networkApis
                    .filter { api -> codeLines.contains(api) }
                    .map { api -> "${file.name}: $api" }
            }
            .toList()
        assertTrue(
            "INTERNET permission is declared for V2 only — no real network calls must " +
                "exist in V1 main sources; DefaultHttpClient present means B.8 fix was " +
                "reverted (H.2):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    // ── H.3 — Camera permission: runtime-only, graceful degradation ──────────

    /**
     * [android.hardware.camera.front] with [required="false"] prevents tablets without
     * a front camera from being filtered out of the Play Store. The app must remain
     * fully functional without gaze (camera is a progressive enhancement).
     *
     * Line extraction is used rather than a compound substring to be robust against
     * XML attribute reordering by Android Studio's formatter.
     */
    @Test
    fun platform_frontCamera_notRequiredAtInstall() {
        val frontCameraLine = manifestXml().lines()
            .firstOrNull { "android.hardware.camera.front" in it }
        assertNotNull(
            "android.hardware.camera.front uses-feature not found in AndroidManifest.xml (H.3)",
            frontCameraLine,
        )
        assertTrue(
            "android.hardware.camera.front must be android:required=\"false\" — " +
                "tablets without a front camera must not be filtered from Play Store (H.3)",
            frontCameraLine!!.contains("required=\"false\""),
        )
    }

    /**
     * Camera permission can be revoked at runtime (Android 11+, Settings → Permissions).
     * [GazeViewModel.startGazeInternal] must catch the resulting [IOException] from
     * [GazeProvider.start] and set [_gazeEnabled] to false — gaze is cleanly disabled,
     * not left in an indeterminate state that crashes the next frame delivery.
     *
     * A 300-char window from the IOException token safely covers the 3-line catch block
     * (confirmed from source: IOException → Log.e → _gazeEnabled.value = false).
     */
    @Test
    fun platform_gazeViewModel_catchesIoException_disablesGaze() {
        val source = sourceFile("ui/gaze/GazeViewModel.kt")
        val exceptionIdx = source.indexOf("java.io.IOException")
        assertTrue(
            "GazeViewModel must catch java.io.IOException from provider.start() (H.3)",
            exceptionIdx >= 0,
        )
        val window = source.substring(exceptionIdx, (exceptionIdx + 300).coerceAtMost(source.length))
        assertTrue(
            "GazeViewModel IOException handler must set _gazeEnabled.value = false — " +
                "camera permission revoked at runtime must cleanly disable gaze (H.3)",
            window.contains("_gazeEnabled.value = false"),
        )
    }

    // ── H.4 — No deep-link injection surface ─────────────────────────────────

    /**
     * A [<data>] element inside an [<intent-filter>] registers the Activity as a
     * deep-link handler, allowing external apps or web pages to craft URIs targeting
     * [MainActivity]. With no Intent-extras guard, any data passed via the deep link
     * could reach the app.
     *
     * Two vectors checked: scheme-based ([android:scheme=]) and host-based
     * ([android:host=]). Both must be absent from the Manifest.
     */
    @Test
    fun platform_mainActivity_noDeepLinkIntentFilter() {
        val manifest = manifestXml()
        assertFalse(
            "AndroidManifest.xml must not contain a scheme-based deep link — " +
                "URI scheme intent-filters expose MainActivity to crafted-URI injection (H.4)",
            manifest.contains("<data android:scheme="),
        )
        assertFalse(
            "AndroidManifest.xml must not contain a host-based deep link (H.4)",
            manifest.contains("<data android:host="),
        )
    }

    /**
     * Even if a deep link were accidentally added, [MainActivity] deriving all state
     * from ViewModels (not [intent.extras]) limits blast radius. This test pins the
     * no-extras-access invariant as an independent defence-in-depth layer.
     */
    @Test
    fun platform_mainActivity_doesNotAccessIntentExtras() {
        val source = sourceFile("MainActivity.kt")
        val intentExtraApis = listOf(
            "intent.extras",
            "intent.data",
            "getStringExtra(",
            "getIntExtra(",
            "getBundleExtra(",
            "getParcelableExtra(",
        )
        val violations = intentExtraApis.filter { source.contains(it) }
        assertTrue(
            "MainActivity must not access Intent extras — all state is ViewModel-derived; " +
                "crafted extras from external Intents must have no effect (H.4):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── H.5 — No exported ContentProvider ────────────────────────────────────

    /**
     * An exported [ContentProvider] would allow other installed apps to query or modify
     * book data, DataStore preferences, and Room records without permission. No provider
     * is needed — Room and DataStore are process-private by design.
     */
    @Test
    fun platform_noContentProviderExported() {
        assertFalse(
            "AndroidManifest.xml must contain no <provider> element — Room and DataStore " +
                "are process-private; an exported provider would expose book data to other " +
                "apps (H.5)",
            manifestXml().contains("<provider"),
        )
    }

    // ── H.5b — Only MainActivity is exported ─────────────────────────────────

    /**
     * Stronger version of H.5 (no exported ContentProvider): the launcher
     * MainActivity is the only declared component with android:exported="true".
     * Any future Service / BroadcastReceiver / additional Activity must be
     * added with android:exported="false" and an explicit permission gate,
     * with this test relaxed to allow the new exported component only after
     * an ADR documents the entrypoint.
     *
     * Source-manifest only; AAR-contributed components on the merged manifest
     * are not covered here (consistent with supply_mediapipe_sourceManifest_
     * internetDeclaredOnce discipline — see GroupEFSecurityTest F.5).
     */
    @Test
    fun platform_onlyMainActivityIsExported() {
        val manifest = manifestXml()
        // Match any component element (activity/service/receiver/provider)
        // that carries android:exported="true". [^>]* spans newlines because
        // newline ≠ '>' — Android manifests routinely declare attrs on
        // separate lines.
        val exportedComponentPattern = Regex(
            "<(activity|service|receiver|provider)\\b[^>]*android:exported=\"true\"[^>]*>",
        )
        val exported = exportedComponentPattern.findAll(manifest).toList()
        assertEquals(
            "AndroidManifest.xml must declare exactly one exported component " +
                "(the launcher MainActivity). Found ${exported.size}: " +
                exported.joinToString("\n") { it.value.lineSequence().first().trim() },
            1,
            exported.size,
        )
        val element = exported.single().value
        assertTrue(
            "The single exported component must be the .MainActivity activity. " +
                "Got: ${element.take(120)}",
            element.contains(".MainActivity"),
        )
    }

    // ── H.7 — PendingIntent: always FLAG_IMMUTABLE, only in service module ──

    /**
     * V2.9 replaces the V1 fail-closed `platform_noPendingIntent_inMainSources`
     * gate per v2-scope.md Convention 3. The foreground-service module
     * (ADR-AND-W) legitimately constructs PendingIntents for its
     * notification's content tap and action buttons. Two invariants pin the
     * surface:
     *
     *   (a) Every PendingIntent factory call in main sources co-locates
     *       `FLAG_IMMUTABLE` in the same file. API 31+ requires the
     *       mutability flag; without IMMUTABLE a third-party app can
     *       hijack the intent.
     *   (b) PendingIntent construction appears ONLY inside
     *       `com/straysouth/lectern/service/` — no PendingIntents leak
     *       into ViewModel / Compose / Fragment surfaces.
     *
     * Pattern targets the five factory methods: getActivity, getActivities,
     * getBroadcast, getService, getForegroundService.
     */
    @Test
    fun platform_pendingIntent_alwaysImmutable_andOnlyInServiceModule() {
        val mainSources = File("src/main/kotlin")
        assertTrue(
            "src/main/kotlin not found (working dir: ${System.getProperty("user.dir")})",
            mainSources.exists(),
        )
        val factoryCalls = listOf(
            "PendingIntent.getActivity(",
            "PendingIntent.getActivities(",
            "PendingIntent.getBroadcast(",
            "PendingIntent.getService(",
            "PendingIntent.getForegroundService(",
        )
        val constructors = mainSources.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file ->
                val stripped = stripComments(file.readText())
                factoryCalls.any { stripped.contains(it) }
            }
            .toList()
        val nonServiceCallers = constructors
            .filterNot { it.path.replace('\\', '/').contains("/service/") }
            .map { it.name }
        assertTrue(
            "PendingIntent construction must be confined to the service module " +
                "(com/straysouth/lectern/service/). ADR-AND-W limits the V2.9 " +
                "foreground-service notification to the sole PendingIntent surface " +
                "in V2:\n${nonServiceCallers.joinToString("\n")}",
            nonServiceCallers.isEmpty(),
        )
        val missingImmutable = constructors
            .filterNot { stripComments(it.readText()).contains("FLAG_IMMUTABLE") }
            .map { it.name }
        assertTrue(
            "Every file constructing a PendingIntent must co-locate " +
                "PendingIntent.FLAG_IMMUTABLE — API 31+ requirement, prevents " +
                "third-party intent hijack (ADR-AND-W §Security):\n" +
                missingImmutable.joinToString("\n"),
            missingImmutable.isEmpty(),
        )
    }

    // ── H.6 — FLAG_SECURE write-locality (V2.2 amendment) ──────────────────

    /**
     * V2.2.1 — `SecureWindow()` IS invoked in production code (from
     * `ReaderScreen.kt`) when an EPUB is open. Source-pin the call site so
     * a refactor that removes the SecureWindow claim doesn't silently
     * regress V2.2's FLAG_SECURE coverage.
     *
     * Per ADR-AND-R 2026-05-22 amendment.
     */
    @Test
    fun platform_secureWindow_calledFromReaderScreen() {
        val readerScreen = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/reader/ReaderScreen.kt").readText(),
        )
        assertTrue(
            "ReaderScreen.kt must call SecureWindow() so the EPUB reader claims " +
                "FLAG_SECURE while in composition (ADR-AND-R 2026-05-22 amendment, " +
                "ADR-AND-T V2.2). Removing the call would silently re-open the " +
                "screenshot surface on user-authored highlights.",
            readerScreen.contains("SecureWindow()"),
        )
    }

    /**
     * V2.3 (cross-feature audit sev-1): `ReviewScreen` renders user-authored
     * annotation body text via `ann.body` in a Compose Text. Same ADR-AND-R
     * trigger 1 rationale as the EPUB reader — private user content on a
     * non-EPUB surface must claim FLAG_SECURE.
     */
    @Test
    fun platform_secureWindow_calledFromReviewScreen() {
        val reviewScreen = stripComments(
            File("src/main/kotlin/com/straysouth/lectern/ui/review/ReviewScreen.kt").readText(),
        )
        assertTrue(
            "ReviewScreen.kt must call SecureWindow() so the review queue " +
                "claims FLAG_SECURE while annotation body text is on screen " +
                "(ADR-AND-R V2 trigger 1; cross-feature audit 2026-05-23).",
            reviewScreen.contains("SecureWindow()"),
        )
    }

    // ── H.6 (V2.2 amendment) — FLAG_SECURE write-locality ──────────────────

    /**
     * V2.2 ships user-authored highlights (ADR-AND-T), which fire ADR-AND-R
     * reconsideration trigger 1. FLAG_SECURE IS claimed in production via
     * `SecureWindow()` in `ReaderScreen` (EPUB format only). The amendment
     * preserves the V1 stance everywhere else (Library, Calibration, etc.)
     * — accessibility tools still need unobstructed screen renders there.
     *
     * This test pins **write-locality**: the literal `FLAG_SECURE` constant
     * may appear only in `WindowSecurityController.kt`. All other code must
     * reach the flag via `SecureWindow()` (the Composable wrapper) — no
     * direct `window.setFlags(FLAG_SECURE, ...)` calls in feature code.
     *
     * Replaces V1's `platform_flagSecureAbsent_screenshotsPermitted` per
     * v2-scope.md Convention 3 (test-gate replacement, not relaxation).
     */
    @Test
    fun platform_flagSecure_writtenOnlyViaController() {
        val mainSources = File("src/main/kotlin")
        assertTrue(
            "src/main/kotlin not found (working dir: ${System.getProperty("user.dir")})",
            mainSources.exists(),
        )
        // Per ADR-AND-R 2026-05-22 amendment (V2.2 fires trigger 1 — annotations):
        // FLAG_SECURE is now claimed in production via SecureWindow() in ReaderScreen,
        // but the LITERAL flag write must still happen only inside the controller.
        // Other files may reference SecureWindow / WindowSecurityController by name
        // without touching the FLAG_SECURE constant directly.
        //
        // Strip comments before pattern-matching: docstrings that reference FLAG_SECURE
        // by name (e.g. MainActivity's CompositionLocal-provider documentation, or
        // ReaderScreen's SecureWindow rationale) must not trigger this test. Only
        // actual code-level use of the flag is the threat surface.
        //
        // Replaces the V1 `platform_flagSecureAbsent_screenshotsPermitted` fail-closed
        // test per v2-scope.md Convention 3 (test-gate replacement, not relaxation).
        val violations = mainSources.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.name != "WindowSecurityController.kt" }
            .filter { stripComments(it.readText()).contains("FLAG_SECURE") }
            .map { it.name }
            .toList()
        assertTrue(
            "FLAG_SECURE write must be confined to WindowSecurityController.kt — " +
                "other files invoke SecureWindow() instead of writing the flag directly. " +
                "Per ADR-AND-R 2026-05-22 amendment.\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun manifestXml(): String {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue(
            "AndroidManifest.xml not found (working dir: ${System.getProperty("user.dir")})",
            file.exists(),
        )
        return file.readText()
    }

    private fun networkSecurityConfigXml(): String {
        val file = File("src/main/res/xml/network_security_config.xml")
        assertTrue(
            "network_security_config.xml not found " +
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

    // Strips single-line comments (//), KDoc body lines (*), and block-comment
    // openers (/*) from source before string checks. Mirrors the helper in
    // GroupASecurityTest. Also strips inline comment tails via
    // substringBefore("//") so that a token appearing only after a // on a
    // code line does not count.
    private fun stripComments(source: String): String =
        source.lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .map { it.substringBefore("//") }
            .joinToString("\n")
}
