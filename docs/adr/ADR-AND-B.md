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

## Known gap

The script scans only `res/values/*.xml`. Compose `Text("…")` literals
and string constants in `.kt` source are not currently scanned. This
gap is tracked separately and will be closed by extending the script
to `app/src/main/kotlin/**/*.kt` with a small allowlist for tests.

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
