# ADR-AND-F: RSVP TextKit-bypass — Deferred (no RSVP module in V1)

**Status:** Deferred
**Date:** 2026-05-16
**Sprint:** 24 (backfill stub)

## Context

iOS ADR-F mandates that RSVP (Rapid Serial Visual Presentation)
bypasses TextKit 2 entirely and uses a `ContinuousClock`
deadline-corrected timing path. A CI grep asserts zero
`.layoutManager` references in the iOS RSVP module.

Android V1 has no RSVP module. The feature is deferred to a future
phase.

## Decision

Deferred. When an Android RSVP module is added, this ADR will be
amended to specify the equivalent Compose rule (RSVP must not route
through any text-layout/measurement API that touches TextKit-like
internals — on Android the closest analog is `TextMeasurer`, which
should be bypassed in favour of a direct `BasicText` draw at
pre-computed positions). A CI grep equivalent will be added.

## Code markers

None yet.

## Consequences

When this surface ships, expect: a new package
`com.straysouth.lectern.rsvp`, a Compose-native draw path, a
deadline-corrected timing loop, and a CI grep gate analogous to
the iOS one.
