# ADR-AND-J: Gaze ephemerality — raw frames in-memory only, weights only persist

**Status:** Accepted
**Date:** 2026-05-16
**Sprint:** 24 (backfill)

## Context

Gaze tracking is the highest-sensitivity data surface in Lectern. Raw
front-camera frames and iris UV coordinates are biometric-adjacent and
would, if exfiltrated or persisted, defeat the AuDHD-trust posture
the app exists to maintain.

iOS ADR-J restricts ARFaceAnchor frames and raw gaze coordinates to
in-memory ephemerality; only the derived `PersistedCalibration`
(12 floats + metadata) may be persisted, and only to
`applicationSupportDirectory` as local JSON — never to Core Data,
CloudKit, or logs.

This ADR ports the rule to Android. See ADR-AND-E for the choice of
inference provider; this ADR governs the data lifecycle.

## Decision

Raw data — `Bitmap` frames from CameraX, `FaceLandmarkerResult` iris
UV coordinates, intermediate ridge-regression feature vectors — is
in-memory only. None of these reach `DataStore`, Room, the filesystem,
or `Log.*` calls.

Derived data — the `CalibrationResult` (12 doubles + 1 float + meta) — may be
persisted to DataStore at `files/datastore/calibration_prefs.preferences_pb`.
This file is excluded from cloud backup (`data_extraction_rules.xml`
`<cloud-backup>` blanket exclusion) and from device-to-device transfer
(`<device-transfer>` explicit path exclusion).

No `@Entity` may use a name matching `face|eye|gaze|lookAt`. No
DataStore preference name may match. Enforced by
`scripts/check_gaze_data_leak.sh`.

## Pinned by

| Guarantee | Test (in `app/src/test/kotlin/.../security/`) |
|---|---|
| Calibration repository stores weights only — no raw iris coordinates | `GroupIJSecurityTest.gaze_calibrationRepository_storesOnlyWeights_noRawIrisCoordinates` |
| No raw iris UV in any log line across the gaze module | `GroupIJSecurityTest.gaze_rawIrisUV_neverLoggedInFullGazeModule` |
| `GazeProviderImpl` log lines contain no weight or iris data | `GroupDSecurityTest.gazeProviderImpl_logLines_containNoWeightOrIrisData` |
| `GazeViewModel` log lines contain no weight or iris data | `GroupDSecurityTest.gazeViewModel_logLines_containNoWeightOrIrisData` |
| `CalibrationRepository` has zero `Log.*` call sites | `GroupDSecurityTest.calibrationRepository_hasNoLogCalls` |
| Calibration DataStore name matches D2D-transfer exclusion path | `GroupDSecurityTest.calibrationRepository_datastoreName_matchesExclusionPath` |
| Calibration prefs excluded from device-transfer | `GroupDSecurityTest.calibrationPrefs_excludedFromDeviceTransfer` |
| `check_gaze_data_leak.sh` blocks `@Entity` / DataStore names matching gaze terms | (CI script, gates merge) |

Cross-references: ADR-AND-E (inference provider), ADR-AND-I (no cloud
sync), ADR-AND-C (no `@Entity` may use gaze-named tables).

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/data/repository/CalibrationRepository.kt`
- `app/src/main/kotlin/com/straysouth/lectern/gaze/GazeProviderImpl.kt`
- `app/src/main/kotlin/com/straysouth/lectern/gaze/CalibrationResult.kt`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `scripts/check_gaze_data_leak.sh`

## Consequences

- Re-introducing a `Log.d("...$irisU...")` style debug line fails CI
  via the per-module log-line tests.
- Adding a new persistence target (e.g. a future Room entity, a new
  DataStore file) for gaze-derived data requires extending
  `data_extraction_rules.xml` exclusions *and* adding a matching
  exclusion-path test before merge.
- A future cloud sync (ADR-AND-S+) must explicitly state that
  calibration weights remain local — they are scoped to one device's
  camera geometry and re-uploading them is a privacy regression even
  if technically derived.
