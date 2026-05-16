# ADR-AND-Q: A11y V2 flagless exception — Deferred (no A11y V2 in V1)

**Status:** Deferred
**Date:** 2026-05-16
**Sprint:** 24 (backfill stub)

## Context

iOS ADR-Q is a deliberate exception to ADR-D default-OFF: accessibility
V2 (chapter rotor + heading-level attributes) ships *without* a feature
flag, because defaulting OFF would regress VoiceOver users — the cost
of default-OFF is regressive UX for the population the feature exists
to serve.

Android V1 has no A11y V2 feature set. TalkBack support is delivered
via standard Compose `semantics {}` blocks on every screen
(RULES.md §Accessibility) — this is base-level accessibility, not a
V2 enhancement.

## Decision

Deferred. When Android-equivalent A11y V2 features (e.g. heading
navigation, custom rotors, structured TalkBack landmarks) ship, this
ADR will be amended to allow them to ship flagless using the iOS
rationale — accessibility enhancements that *regress* base behavior
when disabled should not default OFF.

The standard ADR-AND-D rule still applies to camera/mic/haptics/ambient
features. This exception is narrowly scoped to a11y.

## Code markers

None yet for V2. Base TalkBack support lives in every Composable that
defines `semantics {}` — see e.g. `ReaderScreen.kt`, `LibraryScreen.kt`.
