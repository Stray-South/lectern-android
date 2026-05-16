# ADR-AND-H: STT fail-closed — Deferred (no STT in V1)

**Status:** Deferred
**Date:** 2026-05-16
**Sprint:** 24 (backfill stub)

## Context

iOS ADR-H requires STT captions to fail closed if on-device
recognition is unavailable — never silently fall back to server.

Android V1 has no STT module. The microphone permission is not
requested (pinned by `GroupEFSecurityTest.tts_noMicrophonePermissionRequested`).

## Decision

Deferred. When STT ships on Android, it must use
`SpeechRecognizer.createOnDeviceSpeechRecognizer(...)` with
`EXTRA_PREFER_OFFLINE`, and fail closed (caption feature disabled,
no server fallback) if on-device recognition is unavailable.

A new ADR will codify the exact contract when implemented. Until
then, this stub prevents letter drift and marks where the rule will
go.

## Code markers

None yet.

## Consequences

- The `RECORD_AUDIO` permission is not declared in `AndroidManifest.xml`.
  Adding it requires this ADR to be promoted to Accepted with the
  fail-closed contract.
