# ADR-AND-O: Rendering-surface isolation — Compose for chrome+body, WebView only via ADR-AND-N

**Status:** Accepted
**Date:** 2026-05-16
**Sprint:** 24 (backfill)

## Context

iOS ADR-O isolates TTS word-level highlight rendering to TextKit 2's
`addRenderingAttribute(_:value:for:)`, forbidding storage-attribute
mutation in the highlight path (storage mutation triggers re-layout
per word advance, causing flicker and breaking pagination).
`textView.layoutManager` is absolutely forbidden — accessing it
triggers a one-way migration to the TextKit 1 stack.

Android has no TextKit equivalent. The relevant rendering-isolation
property here is: which surface renders user-facing text, and is
each surface scoped to a single purpose?

## Decision

Lectern Android uses exactly two rendering surfaces for text:

1. **Jetpack Compose** — for app chrome (toolbar, dialogs, settings,
   library list, calibration UI, overlays) and any non-EPUB document
   body text (future plain-text reader if added).
2. **Android `WebView` via Readium** — strictly for EPUB body content
   rendering, gated by `EpubBlockingWebViewClient` (see ADR-AND-N).

PDF and CBZ rendering use their respective bitmap-based surfaces
(`PdfRenderer`, `BitmapFactory`-decoded `ImageBitmap` in Compose) —
neither involves WebView or HTML.

There is no path that mixes the two text surfaces for the same piece
of content. The reader overlay (gaze focus band, anchor markers) is
a Compose layer drawn *above* the WebView in z-order; it does not
write into the WebView (pinned by
`epub_overlay_addedAfterNavigatorContainer_zOrderCorrect`).

## Implementation rules

- Legacy `TextView` is not used for body text. Compose `Text` /
  `BasicText` only. **This rule is not currently pinned by a test**
  — adjacent candidate, see §Known gaps below.
- Future TTS word-level highlight on an EPUB page must use Readium's
  decoration API (which the navigator translates into in-WebView CSS),
  not a Compose overlay over WebView content. Mixing surfaces breaks
  the gaze-coord ↔ text-position correspondence already disclaimed in
  ADR-AND-L.
- Compose overlays may carry purely decorative state (`GazeFocusBand`
  pixel rectangle) but must not carry semantic text content that
  duplicates what the WebView renders.

## Pinned by

| Guarantee | Source |
|---|---|
| Overlay z-order: Compose on top, WebView below | `GroupASecurityTest.epub_overlay_addedAfterNavigatorContainer_zOrderCorrect` |
| No direct `evaluateJavascript` from Kotlin into the WebView | `GroupASecurityTest.epub_noDirectEvaluateJavascript_inMainSources` |
| WebView is gated by `EpubBlockingWebViewClient` (see ADR-AND-N) | (full test set in ADR-AND-N) |

## Known gaps (adjacent candidates)

1. "No legacy `TextView` for body text" is not pinned by a test. A
   future contributor adding a `TextView` for performance reasons
   would not fail CI.
2. "Reader overlay layer is Compose, never AndroidView with WebView
   content" — not pinned beyond the z-order test.
3. Whether TTS word-level highlight will ship in V1 as a Readium
   decoration (in-WebView) or a Compose overlay is undecided. Either
   path must respect this ADR; the decision is recorded in DEVLOG
   when made, not pre-emptively here.

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/ui/reader/ReaderScreen.kt`
- `app/src/main/kotlin/com/straysouth/lectern/ui/reader/ReaderOverlay.kt`
- `app/src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderFragment.kt`

## Consequences

- Adding a third rendering surface (e.g. legacy `TextView`, a custom
  `Canvas` text path, a second WebView for some auxiliary content)
  requires a new ADR.
- A future PDF text-extraction path that needs reflow must reach
  Compose, not a WebView.
- See ADR-AND-L for the EPUB-WebView limit on line-level gaze
  highlighting — a direct consequence of this isolation rule.
