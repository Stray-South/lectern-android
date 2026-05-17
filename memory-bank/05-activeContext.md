# 05 — Active Context

**Last updated:** 2026-05-17 (Sprint 25 — Set 5 docs hygiene)

## Current trunk state

`sprint/14-calibration-entry-point` @ `05b8d1d` — Sprint 23 (May 10):

- connectedAndroidTest compile fixes
- schema asset wiring (`androidTest` assets sourceset)
- Phase 5 emulator security checks (D.4 pkgFlags ALLOW_BACKUP absent,
  J.6 app data dir 700, D.3/J.2 logcat gaze scan empty, J.4 CAMERA
  grant/revoke round-trip clean)

## In-flight Phase 1 work (post-handoff gap closure)

**14 local branches**, none pushed, organized in 4 sets + Set 5 docs.

### Track A — Standalone ADR docs (parallel to Track B/C)

- `docs/adr-and-e-gaze-stack` (`d2ab878`) — ADR-AND-E
- `docs/adr-and-backfill` (`f4f950f`) — 14 ADRs (B / C / D / F / G / H /
  I / J / K / M / N / O / P / Q)

### Track B — Set 1 (audio coordinator extraction)

- `refactor/audio-session-coordinator` (`489793e`) —
  `AudioSessionCoordinator` + `EpubReaderViewModel` refactor +
  `GroupEFSecurityTest` rewrite + `ADR-AND-A.md` + `RULES.md §Audio`
- `ci/audio-session-grep-gate` (`1a1c658`) — `check_audio_session.sh`

### Track C — Set 2 (CI gates), stacked on Track B

- `ci/banned-deps-grep-gate` (`7cb07cc`) — `check_banned_deps.sh`
- `ci/release-build-log-gate` (`04e7c72`) — `check_release_logging.sh` +
  removed one `Log.d` in `GazeProviderImpl`
- `ci/banned-strings-extend-kotlin` (`aa5b203`) — extended
  `check_banned_strings.sh` to scan `.kt` main sources + fixed
  `GazeViewModel` user-facing fallback string (dropped `e.message` channel)
- `docs/rules-cite-set2-scripts` (`10bc7d9`) — RULES.md citations

### Track C continued — Set 3 (contract tests)

- `tests/no-javascript-interface` (`106e8b9`) —
  `epub_noJavascriptInterface_inMainSources`
- `tests/platform-component-inventory` (`fa60941`) —
  `platform_onlyMainActivityIsExported` +
  `platform_noPendingIntent_inMainSources` + harmonized
  `GroupIJSecurityTest.stripComments` (chokepoint fix per
  `feedback_chokepoint_over_per_instance`)

### Track C continued — Set 4 (FLAG_SECURE ADR)

- `docs/adr-and-r-flag-secure` (`920a748`) — `ADR-AND-R.md` formalizing
  FLAG_SECURE absence with three rationales (accessibility, V1 threat
  model, gaze-overlay specifically)

### Track C continued — Set 5 (this work — repo hygiene)

- `docs/manifest-and-memory-bank` ← **you are here**
- `docs/surgical-engineer-doc` (next)
- `chore/hprof-cleanup` (last)

## Current focus

Phase 1 gap closure from the cross-platform handoff brief is complete
after Set 5. 8 of 10 original Sev-2s are closed; Sev-3 #15 (hprof) will
close in Set 5 PR-M.

## Open questions for the user

1. **Push order** — Track A first (docs) or Track B+C first (code)?
   Recommendation: A first, then B/C — docs land cleanly; code rebases
   trivially once ADR-AND-A/E/etc references resolve on trunk.
2. ~~**Cross-branch doc cleanup PR**~~ — **CLOSED 2026-05-17 (Sprint 26):**
   split into `docs/cleanup-trunk-side` (ships immediately) and
   `docs/cleanup-track-a-side` (ships after Track A merges). See
   `06-progress.md §Cross-branch follow-ups`.

## Recent ADR landings (since the last DEVLOG digest)

- ADR-AND-A (Set 1 / PR-C1) — AudioSessionCoordinator sole-owner
- ADR-AND-R (Set 4) — FLAG_SECURE absence
- ADR-AND-B through Q (Track A, parallel branch, not yet on trunk)

## Adversarial-audit lessons applied this round

- Multiple per-PR audits caught real bugs that solo-author review missed:
  TtsNavigator leak, `e.message` channel leak, BSD-awk regex
  incompatibility, ADR cross-reference dangling annotations
- `feedback_chokepoint_over_per_instance` discipline applied to the
  recurring `stripComments` divergence — fixed once across all 5 Group
  test files
- `feedback_reviewer_fp_verification` prevented one false-Sev-2
  escalation in the Set 5 cumulative audit
