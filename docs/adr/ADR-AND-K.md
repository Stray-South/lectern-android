# ADR-AND-K: captureForTarget calibration API — Deferred (current API serves)

**Status:** Deferred
**Date:** 2026-05-16
**Sprint:** 24 (backfill stub)

## Context

iOS ADR-K specifies that `captureForTarget(_ target: CGPoint) async`
on `GazeProvider` is the sole mechanism for collecting calibration
frames without subscribing to the single-consumer `focusEvents` stream.
`GazeDisabledProvider` and `MockGazeProvider` are no-ops; dwell timing
and frame buffering are actor-internal to `ARKitGazeProvider`.

Android's current `GazeProvider.calibrate(points: List<CalibrationPoint>)`
(see `GazeProvider.kt:25`) accepts a pre-collected list of points.
Frame buffering during target dwell is currently inside the calibration
UI flow, not the provider.

## Decision

Deferred. The current Android calibration API is sufficient for V1.
If the calibration UI is reworked to push dwell/buffering down into the
provider (matching iOS), a new ADR will codify the API contract.

A `GazeDisabledProvider` / `MockGazeProvider` should also exist for
testability of ADR-AND-D default-OFF — currently noted as an adjacent
candidate in ADR-AND-E §Consequences.

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/gaze/GazeProvider.kt:24`
  (current `calibrate(points)` API)
