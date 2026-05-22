# ADR-AND-I: No LLM, no backend, no cloud sync in V1

**Status:** Accepted
**Date:** 2026-05-16
**Sprint:** 24 (backfill)

## Context

iOS ADR-I forbids LLM usage in V1; Foundation Models on-device only
in V1.1, hardware-gated, paired with source note. The constraint is
both a privacy posture (reading content never leaves the device) and
an evidence posture (no opaque-model recommendations to AuDHD readers).

Android V1 takes the constraint further: no LLM, no backend, no cloud
sync. The app is fully local. The `INTERNET` permission is declared
only as forward-compatibility for an unimplemented V2 sync surface.

## Decision

The V1 Android app:

1. Includes zero LLM SDKs in `app/build.gradle.kts` and
   `gradle/libs.versions.toml`.
2. Makes zero network calls from main sources. The `INTERNET`
   permission in `AndroidManifest.xml` is for future use only.
3. Has no analytics, telemetry, or crash-reporting SDK
   (see `RULES.md §Privacy (non-negotiable)` for the enforced banlist;
   ADR-AND-J §Decision codifies the parallel rule for gaze data).
4. Persists all user data locally — Room (books, reading progress)
   and DataStore (preferences, calibration weights).

Readium's `BlockingHttpClient` is wired into both Readium network
entry points to prevent the toolkit from making outbound calls
silently — see ADR-AND-N.

## Pinned by

| Guarantee | Test (in `app/src/test/kotlin/.../security/`) |
|---|---|
| All external deps version-pinned (no floating ranges) | `GroupEFSecurityTest.supply_allExternalDeps_versionPinned_notFloating` |
| `INTERNET` permission declared but no actual network calls from main sources | `GroupHSecurityTest.platform_internetPermission_noActualNetworkCalls_inMainSources` |
| Readium publication repository uses `BlockingHttpClient`, not default HTTP client | `GroupASecurityTest.epub_publicationRepository_blockingHttpClient_noDefaultHttpClient` |

## Code markers

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/com/straysouth/lectern/data/repository/BlockingHttpClient.kt`

## Consequences

- Reading content, queries, document titles, and reading progress
  never leave the device.
- No crash reports, no analytics events, no telemetry of any kind.
- Adding any network-calling code path requires a new ADR documenting
  what is sent, to where, under what user consent, and how the call is
  blocked when consent is absent.
- Promoting V1 → V2 sync requires a separate ADR (likely ADR-AND-S+)
  with explicit threat-model + consent-surface design.
