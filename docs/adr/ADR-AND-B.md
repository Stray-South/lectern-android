# ADR-AND-B: Banned-strings CI lint

**Status:** Accepted
**Date:** 2026-05-16
**Sprint:** 24 (backfill)

## Context

Lectern is an AuDHD-first reading app. Streak language, loss-framing,
urgency copy, and contingent-reward vocabulary are excluded from the
product surface by editorial policy (RULES.md §AuDHD copy).

Editorial policy alone is unenforceable across multi-session work. A
CI gate is required to prevent silent reintroduction.

## Decision

`scripts/check_banned_strings.sh` greps `app/src/main/res/values/*.xml`
for the banned token set:

```
streak | consecutive | wrong | incorrect | failed | missed
great job | keep it up | daily goal | 🔥 | 🏆 | ⭐
```

Match (case-insensitive) → exit 1, block merge. Wired into
`scripts/preflight.sh` step `[5/6] Banned strings...`.

A separate unit test, `GroupGSecurityTest.audhd_stringsXml_noBannedCopy`,
re-runs the equivalent check in-process so the gate fails at the JVM
test layer even if the shell script is bypassed.

## Known gap (CLOSED — Sprint 24 Set 2 PR-F, `aa5b203`)

~~The script scans only `res/values/*.xml`. Compose `Text("…")` literals
and string constants in `.kt` source are not currently scanned.~~

**Status:** CLOSED by `ci/banned-strings-extend-kotlin` (`aa5b203`).
`check_banned_strings.sh` now performs a two-pass scan: XML files in
`res/values/*.xml` and Kotlin sources in `app/src/main/kotlin/**/*.kt`
with awk-based comment stripping (BSD-compatible) and an allowlist for
the security-test source set. Two real `.kt` violations were fixed in
the same commit.

> **Cross-branch note:** Sprint 2 (`752f00e`) introduced a basic
> `find`-loop `.kt` scan; `aa5b203` (Sprint 24 Set 2 PR-F) added the
> awk comment-stripping + test allowlist that fully closes this gap.
> The referenced commit lives on the Track C stack
> (`ci/banned-strings-extend-kotlin`); reachable on trunk only after
> the Track C stack merges.

## Code markers

- `scripts/check_banned_strings.sh`
- `scripts/preflight.sh`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/com/straysouth/lectern/security/GroupGSecurityTest.kt`

## Consequences

- Reintroducing a banned token in `strings.xml` fails the build.
- New `.kt`-resident user-facing copy is currently uncovered.
- Adding new banned tokens requires editing both the shell script
  and the in-process test in tandem.
