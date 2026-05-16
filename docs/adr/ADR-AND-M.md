# ADR-AND-M: Retrieval heuristic-only — Deferred (no retrieval in V1)

**Status:** Deferred
**Date:** 2026-05-16
**Sprint:** 24 (backfill stub)

## Context

iOS ADR-M specifies that retrieval (spaced-repetition / question
generation) is heuristic-only in V1: three-priority pipeline
(user highlights → pre-authored Q&A → first-sentence cloze), falls
silent when sources empty, never fabricates. No LLM, no network calls.

Android V1 has no retrieval module. Deferred.

## Decision

Deferred. When Android retrieval ships, it must port the heuristic-only
rule verbatim — the rule is an evidence/safety posture, not an
iOS-specific implementation choice. A new ADR will codify it then.

## Code markers

None yet.
