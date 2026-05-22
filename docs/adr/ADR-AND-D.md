# ADR-AND-D: Default-OFF for camera/mic/haptics/ambient features

**Status:** Accepted (with documented divergence from iOS)
**Date:** 2026-05-16
**Sprint:** 24 (backfill)

## Context

iOS ADR-D specifies that every feature using camera, microphone,
haptics, or ambient sound is gated by `FeatureFlagStore` defaulting OFF.
Intensity 0 is a literal no-op. The store is the single point of policy.

Android V1 has no central `FeatureFlagStore`. Each opt-in feature
manages its own enablement state via its repository's preferences.

## Decision

Android V1 enforces default-OFF on a per-feature basis. The contract
is the same as iOS — features that use camera/mic/haptics/ambient must
default to disabled and require explicit user opt-in — but the
implementation is decentralised.

Current opt-in features:
- Gaze overlay (camera) — `FocusBandPrefs.gazeOverlayEnabled = false`
  in `FocusBandRepository`. Pinned by two tests (see below).

Microphone, haptics, and ambient surfaces are not currently in tree;
when they ship, they must default OFF in their respective repositories
and be pinned by analogous tests.

## Pinned by

| Guarantee | Test (in `app/src/test/kotlin/.../security/`) |
|---|---|
| Gaze overlay default OFF in prefs class | `GroupGSecurityTest.audhd_gazeOverlay_defaultOff_inPrefsClass` |
| Gaze overlay default OFF in repository | `GroupGSecurityTest.audhd_gazeOverlay_defaultOff_inRepository` |

## Divergence from iOS

A central `FeatureFlagStore` provides three properties the per-feature
approach does not:

1. **Discoverability.** A new contributor reading iOS `FeatureFlagStore`
   sees every flag at once. Android requires reading every feature
   repository.
2. **Test uniformity.** A single contract test on the store covers all
   flags. Android requires a per-feature pin.
3. **Runtime override.** Debug builds can flip every flag from one
   surface. Android needs per-feature debug toggles.

Trade-offs accepted in V1: simpler scope, no infrastructure dependency,
no DataStore schema for flags. Promote to a central store when the
opt-in surface grows beyond ~4 features.

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/data/repository/FocusBandRepository.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/repository/FocusBandPrefs.kt`

## Consequences

- Adding a new opt-in feature requires: default-false in its prefs
  class, default-false in its repository load path, two unit tests
  matching the gaze pattern.
- This ADR will be revised when a `FeatureFlagStore` is introduced;
  the per-feature tests can then be replaced by a single store
  contract test.
