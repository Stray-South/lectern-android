# ADR-AND-L: Focus Band scope boundary — WebView limitation

**Status:** Amended  
**Date:** 2026-05-08 (Sprint 11) / 2026-05-08 (Sprint 13 amendment) / 2026-05-23 (V2.4 amendment)  
**Sprint:** 11 (original), 13 (V1 pixel overlay added)

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

### V1 — Pixel-Y overlay (Sprint 13, shipped)

A semi-transparent horizontal band is drawn on the Compose overlay layer at
`gazePoint.y` (calibrated screen-space pixels). This is a pure pixel overlay —
it has no semantic relationship to EPUB text content. It provides a visual
"where is my eye" cue without any WebView interaction.

Spec:
- Height: 52 dp
- Color: warm amber at ~15% alpha (`0x26FFE082`)
- Enabled by a separate `FocusBandPrefs.gazeOverlayEnabled` flag (default OFF)
- Coordinate space: edge-to-edge (`enableEdgeToEdge()` confirmed in MainActivity);
  `gazePoint.y` from calibration aligns with ComposeView Y — no transform needed
- Coexists with the TTS sentence-level Readium decoration (separate mechanism)

### V2 — Precise line-level highlight (deferred to V3)

Focus Band V2 (gaze-driven) **does not apply to the EPUB WebView path**.

Sprint 11 delivers:
- Full `GazeProvider` infrastructure (CameraX + MediaPipe + ridge calibration)
- `GazeState.Tracking(gazePoint, irisU, irisV)` emitted at ~30fps
- A gaze-active indicator in the reader toolbar

The visual Focus Band V2 (band dims above/below the fixated line using
`TextLayoutResult.getLineTop/Bottom()`) is deferred until a native Compose
text surface exists for body text rendering. This is tagged **V3**.

Sites that DO support V2 today: none (no native body-text surface exists).
Sites that will support it: a future plain-text / reflow reader composable
using `BasicText` + `drawWithContent`.

## Code markers

V1 pixel overlay lives in `ReaderOverlay.kt` as `GazeFocusBandOverlay`.

Locations where the V2 precise-line path would plug in are marked:
```kotlin
// TODO(ADR-AND-L): Focus Band V2 — deferred to V3.
// When a native BasicText surface exists, replace GazeFocusBandOverlay with:
//   bandCenterY = gazePoint.y → getLineForOffset → drawRect dim above/below
```

## Consequences

- EPUB readers see V1 pixel overlay (Sprint 13) + full gaze infrastructure.
- V1 overlay gives a "where is my eye" cue; it is not line-semantically aware.
- PDF and Comics readers (bitmap rendering) similarly cannot use the V2 path.
- No performance cost from attempting a WebView JS bridge at 30fps.
- V1 overlay defaults OFF (`gazeOverlayEnabled = false`); user opts in.

## 2026-05-23 V2.4 amendment

Per v2-scope.md Convention 1(c) — appended dated section. V2.4 (RSVP
reader, ADR-AND-X) ships the first native Compose body text surface
**with paginated/timed text flow**. Other Compose `Text` surfaces shipped
before V2.4 (annotation panel `AnnotationListPanel` V2.2.2, note dialog
V2.2.2, review screen `ReviewScreen` V2.3) render single-segment user
content, not body text that needs line-level reading optics. The
original Decision noted that line-level Focus Band V2 is gated on a
multi-line reflowable Compose text surface — V2.4 introduces a Compose
body surface but it is **still moot** for Focus Band V2 because RSVP
displays one word at a time (no "line" semantics). Subsequent Compose
text surfaces (annotation panel, review queue) similarly do not need
the line-level path — they are short user-authored fragments where the
existing pixel-band overlay is sufficient.

V2 line-level Focus Band remains deferred to V3 per the original
Decision. RSVP being a Compose body surface does not unblock it.

When the next Compose body text surface lands (e.g. a paginated
plain-text reader, not in the current V2 roadmap), reassess whether
the Focus Band V2 substrate is finally available.
