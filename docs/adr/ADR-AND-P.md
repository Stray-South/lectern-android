# ADR-AND-P: F5 envelope-consumer pattern — Deferred (no F5 features in V1)

**Status:** Deferred
**Date:** 2026-05-16
**Sprint:** 24 (backfill stub)

## Context

iOS ADR-P specifies the F5 envelope-consumer pattern for
gaze-envelope-driven features (paragraph tint, focus vignette,
TTS soft pause): every feature ships a Coordinator + Renderer +
Wiring ViewModifier with two `.tasks` (envelope-event ingestion +
10 Hz state polling).

Android V1 does not have a `FocusEvent` / envelope abstraction.
Gaze state flows directly as a `StateFlow<GazeState>` from
`GazeProvider`. F5-class features (paragraph tint, vignette, gaze-TTS
pause) are not implemented on Android.

## Decision

Deferred. When F5-class features land on Android, this ADR will be
amended to define the envelope-consumer equivalent — likely a
`SharedFlow<FocusEvent>` with single-consumer semantics, plus a
secondary `register(listener)` API for piggy-backed consumers.

## Code markers

- Current direct-state path:
  `app/src/main/kotlin/com/straysouth/lectern/gaze/GazeProvider.kt`
  exposes `val gazeState: StateFlow<GazeState>` (line 21).
