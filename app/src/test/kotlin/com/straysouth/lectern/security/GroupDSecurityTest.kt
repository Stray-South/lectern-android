package com.straysouth.lectern.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security regression tests for Group D (DataStore and local storage).
 *
 * Covers JVM-testable properties:
 *   D.1 — calibration_prefs and tts_prefs excluded from D2D transfer; DataStore names
 *           in source match the exclusion paths in data_extraction_rules.xml exactly
 *   D.2 — reading-position DataStore keys are URI-derived UUIDs, making D2D transfer
 *           orphan-harmless: keys are device-specific and never match on the new device
 *   D.3 — no calibration weights or iris coordinates emitted in Log calls
 *   D.4 — android:allowBackup="false"; cloud-backup block excludes all data types
 *   D.5 — book files stored in app-private storage only (filesDir / cacheDir);
 *           zero external storage API usage across all main-source Kotlin files
 *
 * Deferred (instrumented):
 *   D.1/D.4 ADB backup verification (physical device + adb backup)
 *   D.3 runtime logcat capture during a live calibration session
 *
 * See docs/security/RED-TEAM.md §D for full attack descriptions and pass criteria.
 *
 * Working-directory assumption: file paths resolve relative to the `app/` module
 * directory, which is the default CWD for `./gradlew testDebugUnitTest`.
 */
class GroupDSecurityTest {

    // ── D.1 — Calibration / TTS prefs excluded from D2D ──────────────────────

    /**
     * Calibration weights encode this device's camera geometry. If transferred to a
     * new device they produce wrong gaze predictions with no visible indication to the
     * user — silent miscalibration. The D2D exclusion prevents this.
     */
    @Test
    fun calibrationPrefs_excludedFromDeviceTransfer() {
        assertTrue(
            "calibration_prefs.preferences_pb must be excluded from device-to-device " +
                "transfer in data_extraction_rules.xml (D.1)",
            deviceTransferBlock().contains("calibration_prefs.preferences_pb"),
        )
    }

    /**
     * TTS speed is excluded because a new device may have a different TTS engine
     * with different latency characteristics — the preferred speed on device A may
     * produce unbearable pacing on device B.
     */
    @Test
    fun ttsPrefs_excludedFromDeviceTransfer() {
        assertTrue(
            "tts_prefs.preferences_pb must be excluded from device-to-device " +
                "transfer in data_extraction_rules.xml (D.1)",
            deviceTransferBlock().contains("tts_prefs.preferences_pb"),
        )
    }

    /**
     * The DataStore delegate name in [CalibrationRepository] must match the path in
     * [data_extraction_rules.xml] exactly. If the name is changed without updating
     * the XML, the exclusion silently stops applying and calibration weights start
     * transferring to new devices.
     */
    @Test
    fun calibrationRepository_datastoreName_matchesExclusionPath() {
        assertTrue(
            "CalibrationRepository must use preferencesDataStore(name = \"calibration_prefs\") " +
                "to match the data_extraction_rules.xml exclusion path (D.1)",
            sourceFile("data/repository/CalibrationRepository.kt")
                .contains("name = \"calibration_prefs\""),
        )
    }

    /** Same name-match guard for the TTS DataStore exclusion. */
    @Test
    fun ttsRepository_datastoreName_matchesExclusionPath() {
        assertTrue(
            "TtsRepository must use preferencesDataStore(name = \"tts_prefs\") " +
                "to match the data_extraction_rules.xml exclusion path (D.1)",
            sourceFile("data/repository/TtsRepository.kt")
                .contains("name = \"tts_prefs\""),
        )
    }

    // ── D.2 — Reading position D2D transfer is orphan-harmless ───────────────

    /**
     * The four reading-position DataStore files are NOT excluded from D2D transfer,
     * but the omission is harmless. All keys use [bookId] as discriminator, and
     * `bookId = UUID(content://URI)`. Content URIs are assigned by the file provider
     * on the source device and are device-specific — the same file re-imported on
     * the new device gets a different URI → different UUID → different key → the
     * transferred entry is permanently orphaned and never read.
     *
     * This test pins the key-discriminator pattern. If any repository is changed to
     * use a content-stable key (file hash, EPUB unique-identifier), this test will
     * fail — at that point add D2D exclusions to data_extraction_rules.xml.
     */
    @Test
    fun readingPositionRepositories_useBookIdAsKeyDiscriminator() {
        assertRepositoryKeyPattern(
            "data/repository/LocatorRepository.kt",
            "reader_prefs",
            "locator_\$bookId",
        )
        assertRepositoryKeyPattern(
            "data/repository/AnchorRepository.kt",
            "anchor_prefs",
            "anchor_\$bookId",
        )
        // ComicsPage and PdfPage intentionally share the key prefix "page_$bookId" — they are
        // separate DataStore files (comics_page_prefs vs pdf_page_prefs) and the DataStore
        // name assertion above already distinguishes them. The shared key prefix is safe
        // because two separate .preferences_pb files on disk are independent stores.
        assertRepositoryKeyPattern(
            "data/repository/ComicsPageRepository.kt",
            "comics_page_prefs",
            "page_\$bookId",
        )
        assertRepositoryKeyPattern(
            "data/repository/PdfPageRepository.kt",
            "pdf_page_prefs",
            "page_\$bookId",
        )
    }

    /**
     * [LibraryViewModel.bookCacheId] must derive its UUID from the URI string via
     * [UUID.nameUUIDFromBytes] — not from a stable content identifier such as a file
     * hash or EPUB unique-id.
     *
     * This is the mechanism that makes D2D transfer of reading-position DataStore
     * files harmless: keys are device-specific, so no transferred entry is ever
     * matched on the new device.
     *
     * If the implementation ever changes to a content-stable key, add D2D exclusions
     * for reader_prefs, anchor_prefs, comics_page_prefs, pdf_page_prefs.
     *
     * See also: [GroupBSecurityTest.bookCacheId_isKeyedOnFullUri_notFilenameSegment]
     * for the behavioural guarantee.
     */
    @Test
    fun bookCacheId_derivedFromUriString_d2dTransferIsOrphanHarmless() {
        val source = sourceFile("ui/library/LibraryViewModel.kt")
        assertTrue(
            "bookCacheId() must use nameUUIDFromBytes on the URI toByteArray — " +
                "content-stable key change requires adding D2D exclusions in " +
                "data_extraction_rules.xml (D.2)",
            source.contains("nameUUIDFromBytes") && source.contains("toByteArray"),
        )
    }

    // ── D.3 — Calibration weights never logged ────────────────────────────────

    /**
     * Raw iris UV coordinates and calibration weight arrays must never appear in
     * log calls. On debug builds Logcat is readable via USB; on release builds it
     * is not accessible to third parties, but log pollution remains a privacy
     * hygiene failure and a future-regression risk.
     *
     * Strategy: filter source lines containing "Log." or "Timber." before asserting,
     * so legitimate non-log references to weightsX etc. do not cause false positives.
     *
     * Single-line assumption: all Log calls in the gaze/calibration module are
     * single-line (verified in research). If multiline log calls are added,
     * extend the filter in [assertNoSensitiveTermsInLogLines].
     */
    @Test
    fun gazeProviderImpl_logLines_containNoWeightOrIrisData() {
        assertNoSensitiveTermsInLogLines(
            sourceFile("gaze/GazeProviderImpl.kt"),
            "GazeProviderImpl.kt",
        )
    }

    @Test
    fun gazeViewModel_logLines_containNoWeightOrIrisData() {
        assertNoSensitiveTermsInLogLines(
            sourceFile("ui/gaze/GazeViewModel.kt"),
            "GazeViewModel.kt",
        )
    }

    /**
     * [CalibrationRepository] writes and reads the raw weight arrays. It must contain
     * zero log calls — any log call here risks emitting calibration data.
     */
    @Test
    fun calibrationRepository_hasNoLogCalls() {
        val source = sourceFile("data/repository/CalibrationRepository.kt")
        assertFalse(
            "CalibrationRepository.kt must contain zero Log.* or Timber.* calls (D.3)",
            source.lines().any { "Log." in it || "Timber." in it },
        )
    }

    // ── D.4 — No sensitive data in Auto Backup ────────────────────────────────

    @Test
    fun manifest_allowBackupIsFalse() {
        assertTrue(
            "AndroidManifest.xml must declare android:allowBackup=\"false\" (D.4)",
            manifestXml().contains("android:allowBackup=\"false\""),
        )
    }

    /**
     * Belt-and-suspenders: even if [allowBackup] is accidentally re-enabled,
     * the cloud-backup block ensures no app data leaves the device via Auto Backup.
     * All three storage domains are covered by wildcard exclusions.
     */
    @Test
    fun dataExtractionRules_cloudBackup_excludesAllDataTypes() {
        val block = cloudBackupBlock()
        assertTrue(
            "cloud-backup must exclude domain=\"file\" path=\".\" (D.4)",
            block.contains("domain=\"file\""),
        )
        assertTrue(
            "cloud-backup must exclude domain=\"database\" path=\".\" (D.4)",
            block.contains("domain=\"database\" path=\".\""),
        )
        assertTrue(
            "cloud-backup must exclude domain=\"sharedpref\" path=\".\" (D.4)",
            block.contains("domain=\"sharedpref\" path=\".\""),
        )
    }

    // ── D.5 — Book content in app-private storage only ───────────────────────

    /**
     * Cover images must be written to [Context.filesDir]; CBZ/CBR cache blobs to
     * [Context.cacheDir]. Neither location is accessible to other apps without
     * root. External storage is world-readable without scoped storage (Android < 10)
     * and requires a permission Lectern does not declare.
     */
    @Test
    fun bookFiles_storedInAppPrivateStorage_notExternal() {
        val librarySource = sourceFile("ui/library/LibraryViewModel.kt")
        assertTrue(
            "LibraryViewModel must save cover images to filesDir (D.5)",
            librarySource.contains("filesDir"),
        )
        val comicsSource = sourceFile("ui/reader/ComicsReaderViewModel.kt")
        assertTrue(
            "ComicsReaderViewModel.uriToFile() must cache to cacheDir (D.5)",
            comicsSource.contains("cacheDir"),
        )
        externalStorageApis.forEach { api ->
            assertFalse("$api must not appear in LibraryViewModel.kt (D.5)", librarySource.contains(api))
            assertFalse("$api must not appear in ComicsReaderViewModel.kt (D.5)", comicsSource.contains(api))
        }
    }

    /**
     * Global regression guard: no Kotlin source file in the main source set may
     * reference external storage APIs. If a future feature legitimately requires
     * external storage, add an explicit allowlist entry here with justification
     * rather than removing this test.
     */
    @Test
    fun noExternalStorageApiUsage_inMainSources() {
        val mainSources = File("src/main/kotlin")
        assertTrue(
            "src/main/kotlin not found (working dir: ${System.getProperty("user.dir")})",
            mainSources.exists(),
        )
        val violations = mainSources.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                externalStorageApis
                    .filter { api -> text.contains(api) }
                    .map { api -> "${file.name}: $api" }
            }
            .toList()
        assertTrue(
            "External storage API usage found in main sources (D.5) — " +
                "all book files must use app-private storage only:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val externalStorageApis = listOf(
        "getExternalStorageDirectory",
        "getExternalFilesDir",
        "getExternalCacheDir",
    )

    private val gazeLogSensitiveTerms = listOf(
        "weightsX", "weightsY", "irisU", "irisV", "toJsonString",
    )

    private fun deviceTransferBlock(): String {
        val xml = dataExtractionRulesXml()
        val start = xml.indexOf("<device-transfer>")
        val end = xml.indexOf("</device-transfer>")
        assertTrue(
            "<device-transfer> block not found in data_extraction_rules.xml",
            start >= 0 && end > start,
        )
        return xml.substring(start, end)
    }

    private fun cloudBackupBlock(): String {
        val xml = dataExtractionRulesXml()
        val start = xml.indexOf("<cloud-backup>")
        val end = xml.indexOf("</cloud-backup>")
        assertTrue(
            "<cloud-backup> block not found in data_extraction_rules.xml",
            start >= 0 && end > start,
        )
        return xml.substring(start, end)
    }

    private fun assertRepositoryKeyPattern(
        relativePath: String,
        datastoreName: String,
        keyPattern: String,
    ) {
        val source = sourceFile(relativePath)
        val fileName = relativePath.substringAfterLast('/')
        assertTrue(
            "$fileName DataStore name must be \"$datastoreName\" (D.2)",
            source.contains("name = \"$datastoreName\""),
        )
        assertTrue(
            "$fileName key discriminator must contain \"$keyPattern\" (D.2)",
            source.contains(keyPattern),
        )
    }

    private fun assertNoSensitiveTermsInLogLines(source: String, fileName: String) {
        val logLines = source.lines().filter { "Log." in it || "Timber." in it }
        gazeLogSensitiveTerms.forEach { term ->
            assertFalse(
                "$fileName: Log call must not emit sensitive gaze/calibration term '$term' (D.3)",
                logLines.any { term in it },
            )
        }
    }

    private fun dataExtractionRulesXml(): String = xmlFile("data_extraction_rules.xml")

    private fun manifestXml(): String {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue(
            "AndroidManifest.xml not found (working dir: ${System.getProperty("user.dir")})",
            file.exists(),
        )
        return file.readText()
    }

    private fun xmlFile(name: String): String {
        val file = File("src/main/res/xml/$name")
        assertTrue(
            "XML resource not found: src/main/res/xml/$name " +
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
