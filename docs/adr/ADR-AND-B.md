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

`scripts/check_banned_strings.sh` is a two-pass scan over
`app/src/main/res/values/*.xml` (substring match) and
`app/src/main/kotlin/**/*.kt` (word-bounded, with `Log.*` /
`Exception(...)` / comment-line skips and BSD-compatible awk
filtering) for the banned token set:

```
streak | consecutive | wrong | incorrect | failed | missed
great job | keep it up | daily goal | 🔥 | 🏆 | ⭐
```

Match (case-insensitive) → exit 1, block merge. Wired into
`scripts/preflight.sh` step `Banned strings...`.

> Two-pass behaviour + the new preflight step count reflect the
> post-merge state of the Track C stack (`ci/banned-strings-extend-kotlin`,
> `aa5b203`). On Track A in isolation the script is still single-pass
> over XML; see §"Known gap" below for the cross-branch dependency.

A separate unit test, `GroupGSecurityTest.audhd_stringsXml_noBannedCopy`,
re-runs the XML pass in-process so the XML gate fails at the JVM
test layer even if the shell script is bypassed. The `.kt` pass is
authoritative via the shell-script gate only — a faithful JVM mirror
would have to replicate the filter logic (Log.*, Exception, word
boundaries) and is tracked as a future refinement.

## Known gap (CLOSED — Sprint 24 Set 2 PR-F, `aa5b203`)

~~The script scans only `res/values/*.xml`. Compose `Text("…")` literals
and string constants in `.kt` source are not currently scanned.~~

**Status:** CLOSED by `ci/banned-strings-extend-kotlin` (`aa5b203`).
`check_banned_strings.sh` now performs a two-pass scan: XML files in
`app/src/main/res/values/*.xml` and Kotlin sources in
`app/src/main/kotlin/**/*.kt` with awk-based comment stripping
(BSD-compatible) and an allowlist for the security-test source set.
Two real `.kt` violations were fixed in the same commit.

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

- Reintroducing a banned token in `strings.xml` or in `.kt` user-
  facing copy (Compose `Text("…")` literals, fallback strings) fails
  the build.
- The shell-script gate is authoritative for `.kt`; the XML pass has
  a JVM-test mirror, the `.kt` pass does not (mirror would have to
  replicate the Log.* / Exception / word-bounded filter logic).
- Adding new banned tokens requires editing the shell script and the
  XML in-process test in tandem.
