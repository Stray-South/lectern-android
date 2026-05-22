# ADR-AND-R: FLAG_SECURE absence — accessibility over screen-capture defense

**Status:** Accepted
**Date:** 2026-05-17
**Sprint:** 25 (post-Sprint 24 ADR backfill)

## Context

The cross-platform Android-parity brief §13 flagged
`WindowManager.LayoutParams.FLAG_SECURE` as a "sensitive content" surface:
when set on an Activity or Window, the system blocks screenshots and
screen recording from capturing that surface. The brief asked us to
either apply FLAG_SECURE to the reader Activity (preventing capture of
the gaze overlay) or document why we don't.

The decision was made during the Sprint 22 RED-TEAM pass (documented at
`docs/security/RED-TEAM.md` §H.6) and pinned by
`GroupHSecurityTest.platform_flagSecureAbsent_screenshotsPermitted`,
but never formalized as an ADR. This document closes that gap.

## Decision

FLAG_SECURE is NOT set on any Activity or Window in
`app/src/main/kotlin/`. Screenshots and screen recording of Lectern V1
are intentionally permitted.

## Rationale

**1. Accessibility regression risk.** FLAG_SECURE blocks the system
screenshot pipeline. Several accessibility tools and dev-time surfaces
rely on it being available:

- TalkBack's screenshot-feedback flow (when users ask the assistant
  "what's on screen")
- Screen magnifier tools that internally read the framebuffer
- Compose preview / IDE screenshot capture during development
- Third-party reading-aid apps that capture-and-OCR a highlighted
  passage to dictionary or translation services

For an AuDHD-first reading app, regressing accessibility tooling has
direct user impact; preventing screen recording has speculative impact.

**2. V1 threat model.** Lectern V1 has no authentication screen, no
private user-generated content, and no third-party-confidential
display surface. The Library shows imported book titles. The Reader
shows EPUB text the user already owns. There are no annotations in V1
(deferred to V2). Shoulder-surf risk is equivalent to any other book
reader; screen-capture risk is equivalent to a manual photograph of
the device.

**3. Gaze overlay specifically (handoff §13).** The gaze focus band
renders a calibrated pixel-Y position (per ADR-AND-L V1 design) — a
derived display coordinate, not raw iris UV. Per ADR-AND-J (gaze
ephemerality, on branch `docs/adr-and-backfill` until Track A merges),
raw biometric data (iris UV, calibration weight vectors) never leaves
`GazeProviderImpl` in-memory scope and is never persisted in a form
that can be screenshotted. A screenshot of the overlay reveals
"what part of the screen the user was looking at" — behavioral, not
biometric — a marginal-privacy versus material-accessibility cost.

## V2 reconsideration triggers

If any of the following ship, re-evaluate and apply FLAG_SECURE to the
relevant Activity or Window:

- Private user annotation feature (user-authored content distinct from
  third-party EPUB body text)
- Login / authentication screen (credentials surface)
- Encrypted-content reading surface (DRM-protected publications)
- Third-party-confidential display (preview of unpublished material,
  reviewer copies, etc.)
- Foreground service with a persistent notification — the notification
  shade is visible in screenshots; if its content reflects reading
  state (current book, gaze indicator, etc.) the screenshot-capture
  threat surface widens

Adding FLAG_SECURE later is a one-line
`getWindow().setFlags(FLAG_SECURE, FLAG_SECURE)` per Activity; no
architectural lift. The ADR is intentionally easy to reverse.

## Pinned by

| Guarantee | Test |
|---|---|
| Zero `FLAG_SECURE` references in `app/src/main/kotlin/**/*.kt` | `GroupHSecurityTest.platform_flagSecureAbsent_screenshotsPermitted` |

## Code markers

- `app/src/test/kotlin/com/straysouth/lectern/security/GroupHSecurityTest.kt`
  — the test function that pins the rule
- `app/src/main/kotlin/com/straysouth/lectern/MainActivity.kt`
  — the Activity that would receive `FLAG_SECURE` if V2 reverses this
  decision
- `docs/security/RED-TEAM.md` §H.6 — the original design-decision note
  (Sprint 22)

## Cross-references

- ADR-AND-J — gaze ephemerality: raw biometric data never persists in
  a form that could be screenshotted. **Note:** ADR-AND-J currently
  lives on branch `docs/adr-and-backfill` and is not yet on trunk;
  the cross-reference resolves once that branch merges.
- ADR-AND-L — Focus Band scope: gaze overlay is pixel-Y only, no
  semantic text correspondence; ADR-AND-R extends the "what is
  actually captured by a screenshot" analysis
