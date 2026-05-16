# ADR-AND-E: Gaze provider stack — MediaPipe on RGB front camera

**Status:** Accepted
**Date:** 2026-05-16
**Sprint:** 24 (post-Sprint 23 ADR backfill)

## Context

The cross-platform Android-parity brief proposed ARCore Augmented Faces as
the gaze provider, gated to devices supporting front-camera face mesh, with
no fallback to RGB-camera inference. That framing mirrored the iOS ADR-E
"TrueDepth-only gaze; no Vision/RGB fallback" rule, where the depth sensor
is the hardware privilege boundary.

Android has no equivalent depth-sensor boundary that ARCore exposes for
gaze. ARCore Augmented Faces is itself RGB-camera-derived; the proposed
"no RGB fallback" rule reduces to "no fallback at all," which describes
single-provider architecture without naming what the provider is.

Sprint 11 shipped the actual implementation: MediaPipe Tasks Vision
FaceLandmarker on a CameraX front-camera ImageAnalysis pipeline. This ADR
documents that choice and explicitly retires the ARCore framing.

## Decision

Android gaze tracking uses MediaPipe Tasks Vision `FaceLandmarker` as the
sole inference engine, fed by a CameraX `ImageAnalysis` pipeline bound to
the front camera. There is no second provider and no fallback path.

Configuration (`GazeProviderImpl.kt:130-148`):
- `RunningMode.LIVE_STREAM` — async result delivery via listener
- `numFaces = 1` — multi-face capture is forbidden; widens the threat
  surface without serving a single-reader use case
- `Delegate.GPU` — failure to initialise (no GPU support) is fail-closed
  by way of `FaceLandmarker.createFromOptions` throwing; `Paused` state
  is the user-visible result
- Model: `face_landmarker.task` loaded from `app/src/main/assets/` — never
  downloaded at runtime
- Iris landmarks: indices 468 (left) and 473 (right) — pinned by
  `GazeProviderImpl.kt:41-42`

Calibration (`GazeProviderImpl.kt:103-128`):
- Feature vector `[u, v, u², v², uv, 1]` (FEATURE_COUNT = 6)
- Ridge regression, λ = 1e-4, fit via EJML `LinearSolverFactory_DDRM`
- Minimum 6 calibration points; 9 recommended
- LOO-CV mean error reported (in-sample residual rejected as
  trivially-low)
- Output: `CalibrationResult { weightsX: DoubleArray(6), weightsY:
  DoubleArray(6), meanErrorPx: Double }` — 13 doubles + metadata,
  derived only

Camera (`GazeProviderImpl.kt:161-182`):
- `CameraSelector.DEFAULT_FRONT_CAMERA`
- `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` — drops frames under load
  rather than queueing them
- `OUTPUT_IMAGE_FORMAT_RGBA_8888` (required by `BitmapImageBuilder`)
- Bound to `LifecycleOwner` — camera released automatically on Activity
  stop

Manifest gate (`app/src/main/AndroidManifest.xml:8-12`):
- `android.permission.CAMERA` — requested at runtime
- `<uses-feature android:name="android.hardware.camera.any" required="true"/>`
- `<uses-feature android:name="android.hardware.camera.front" required="false"/>`
  — tablets without a front camera reach the Play Store listing but cannot
  enable gaze; UI surfaces a runtime "not available" state

## Privacy guarantees preserved despite RGB-camera path

The brief's privacy posture survives wholesale because the per-instance
threat model — "raw frames or iris coordinates leaving the device" — is
defeated by code paths orthogonal to which inference engine runs.

Pinned by existing tests (all under
`app/src/test/kotlin/com/straysouth/lectern/security/`):

| Guarantee | Test |
|---|---|
| No raw iris UV in logs (full gaze module scan) | `GroupIJSecurityTest.gaze_rawIrisUV_neverLoggedInFullGazeModule` |
| No log lines in `GazeProviderImpl` containing weights or iris data | `GroupDSecurityTest.gazeProviderImpl_logLines_containNoWeightOrIrisData` |
| No log lines in `GazeViewModel` containing weights or iris data | `GroupDSecurityTest.gazeViewModel_logLines_containNoWeightOrIrisData` |
| `CalibrationRepository` has zero `Log.*` call sites | `GroupDSecurityTest.calibrationRepository_hasNoLogCalls` |
| Calibration repository stores weights only — no raw iris coordinates | `GroupIJSecurityTest.gaze_calibrationRepository_storesOnlyWeights_noRawIrisCoordinates` |
| Calibration DataStore name matches D2D-transfer exclusion path | `GroupDSecurityTest.calibrationRepository_datastoreName_matchesExclusionPath` |
| Calibration prefs excluded from device-transfer | `GroupDSecurityTest.calibrationPrefs_excludedFromDeviceTransfer` |
| Camera permission revocation handled cleanly | `GroupIJSecurityTest.coroutines_gazeStart_catchesSecurityException_onPermissionRevoke` |
| Front camera not required at install (gate is runtime) | `GroupHSecurityTest.platform_frontCamera_notRequiredAtInstall` |
| Thermal throttle pauses analysis for all severe statuses | `GroupIJSecurityTest.gaze_thermalThrottle_pausesAnalysisForAllSevereStatuses` |
| `pauseAnalysis()` clears analyzer (not just sets state) | `GroupIJSecurityTest.gaze_pauseAnalysis_clearsAnalyzer_notJustSetsState` |
| MediaPipe model loaded from assets, not remote URL | `GroupIJSecurityTest.gaze_modelFile_loadedFromAssets_notExtractedByIntegrationCode` |
| MediaPipe AAR contributes INTERNET permission only once in source manifest | `GroupEFSecurityTest.supply_mediapipe_sourceManifest_internetDeclaredOnce` |

## Supersession

This ADR supersedes the "ARCore Augmented Faces, no RGB fallback" framing
in the cross-platform Android-parity brief. Equivalent privacy posture is
achieved via:

1. In-memory-only inference — raw `Bitmap` frames and iris UV coordinates
   never leave `GazeProviderImpl` scope
2. Derived-weights-only persistence — only the 13-double
   `CalibrationResult` reaches `DataStore`, scoped to app-internal-private
   storage and excluded from cloud backup + D2D transfer
3. Single-provider architecture — no second inference path that could
   silently widen the data surface

Future cross-platform briefs should not introduce ARCore Augmented Faces
language for Android. If hardware-depth gating becomes desirable (e.g. for
a class of devices with privacy-grade depth sensors), file a new ADR;
do not amend this one.

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/gaze/GazeProvider.kt`
- `app/src/main/kotlin/com/straysouth/lectern/gaze/GazeProviderImpl.kt`
- `app/src/main/kotlin/com/straysouth/lectern/gaze/CalibrationResult.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/repository/CalibrationRepository.kt`
- `app/src/main/assets/face_landmarker.task` — model file
- `app/src/main/res/xml/data_extraction_rules.xml` — D2D exclusion path

## Consequences

- Devices without GPU delegate support: `FaceLandmarker.createFromOptions`
  throws at `start()`; state stays `Paused`. **No test currently pins
  this** — adjacent candidate, tracked separately.
- Devices without a front camera: install permitted (per `required="false"`);
  gaze feature surfaces an unavailable state at runtime.
- Multi-face capture is prevented at the MediaPipe option level
  (`numFaces = 1`) — **not currently pinned by a test**. Adjacent
  candidate.
- No `GazeDisabledProvider` / `MockGazeProvider` exists in tree. iOS
  ADR-K provides both for testability of ADR-D default-OFF. Adjacent
  candidate.
- ARCore is **not** a runtime dependency. Re-introducing ARCore would
  require a new ADR.
