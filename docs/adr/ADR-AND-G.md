# ADR-AND-G: ORP geometric centering — Deferred (no RSVP/ORP in V1)

**Status:** Deferred
**Date:** 2026-05-16
**Sprint:** 24 (backfill stub)

## Context

iOS ADR-G specifies Optimal Recognition Point centering for RSVP:
geometric (phi-weighted) flow anchor, phrase-level chunks, no fixed
pixel x-coordinate.

Android V1 has no RSVP/ORP module. Deferred until RSVP ships
(see ADR-AND-F).

## Decision

Deferred. When ORP ships on Android, the geometric-centering rule
must port verbatim — the rule is a typography invariant, not an
iOS-specific one.

## Code markers

None yet.
