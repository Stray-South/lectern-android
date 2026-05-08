# ADR-AND-L: Focus Band V2 scope boundary — WebView limitation

**Status:** Accepted  
**Date:** 2026-05-08  
**Sprint:** 11

## Context

Sprint 11 delivers the GazeProvider infrastructure. The spec (§6.5) describes
Focus Band V2 as "band center follows `gazeState.gazePoint.y`", using
`TextLayoutResult.getLineTop/Bottom()` to position dim rectangles above and
below the focused line.

The EPUB reader uses `EpubNavigatorFragment`, which hosts an Android WebView
with Readium CSS injected. The WebView renders text in its own process; no
`TextLayoutResult` is accessible from Android code outside the WebView.

Mapping a gaze Y coordinate to a specific paragraph/line inside the WebView
would require:
1. Knowing the WebView's scroll position (available via `scrollY`)
2. Knowing each paragraph's bounding rect in page space (not available —
   Readium provides no layout geometry API for individual elements)
3. Issuing a JavaScript call to `document.elementFromPoint()` and waiting
   for an async callback — adds 16–100ms latency per frame, unacceptable at
   30fps gaze updates

## Decision

Focus Band V2 (gaze-driven) **does not apply to the EPUB WebView path** in V1.

Sprint 11 delivers:
- Full `GazeProvider` infrastructure (CameraX + MediaPipe + ridge calibration)
- `GazeState.Tracking(gazePoint)` emitted at ~30fps
- A gaze-active indicator in the reader toolbar
- The `bandCenterY` derivedStateOf plumbing in `GazeViewModel`

The visual Focus Band V2 (band dims above/below the fixated line) is deferred
until a native Compose text surface exists for body text rendering. This is
tagged **V3** in the phase sequencing table.

Sites that DO support Focus Band V2 today: none (no native body-text surface
exists yet in Sprint 11). Sites that will support it: a future plain-text /
reflow reader composable using `BasicText` + `drawWithContent`.

## Code marker

Locations in code where the V2 visual path would plug in are marked:
```kotlin
// TODO(ADR-AND-L): Focus Band V2 visual — deferred to V3.
// When a native BasicText surface exists, replace this with:
//   bandCenterY = gazePoint.y → getLineForOffset → drawRect dim
```

## Consequences

- EPUB readers see gaze tracking infrastructure but no visual Focus Band V2.
- PDF and Comics readers (bitmap rendering) similarly cannot use the V2 path.
- The calibration and GazeProvider are fully functional and testable via the
  gaze indicator and `GazeState` flow.
- No performance cost from attempting a WebView JS bridge at 30fps.
