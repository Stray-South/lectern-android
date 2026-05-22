#!/usr/bin/env bash
# check_banned_deps.sh — CI gate for RULES.md §Privacy.
#
# Mirrors the iOS Package.swift analytics ban for Kotlin/Android. Fails
# if any dependency declaration in app/build.gradle.kts or
# gradle/libs.versions.toml references a banned vendor SDK.
#
# Vendors banned per RULES.md §Privacy: Firebase (any module),
# Crashlytics, Mixpanel, Amplitude, Segment, Bugsnag, Datadog, Sentry,
# AppsFlyer, Adjust. Crash reporting is opt-in only and not wired by
# default; analytics is forbidden.
#
# Scoped to dep-declaration files only — strings.xml and source files
# may legitimately mention vendor names in unrelated contexts.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Each token is a vendor identifier specific enough to avoid FPs on
# legitimate androidx/kotlin/readium deps. com.google.firebase covers
# analytics + crashlytics + messaging + db etc. (RULES.md says "No
# Firebase any module"). Crashlytics also listed separately in case a
# vendor ships it under a different group.
PATTERN='firebase|crashlytics|mixpanel|amplitude|com\.segment|bugsnag|datadoghq|io\.sentry|appsflyer|com\.adjust'
FOUND=0

for f in app/build.gradle.kts gradle/libs.versions.toml; do
    if [ ! -f "$f" ]; then
        continue
    fi
    # Strip Kotlin DSL (//) and TOML (#) comments before pattern match so
    # a vendor name in a doc comment ("// rejected firebase per RULES.md")
    # does not trigger a false-alarm CI failure. Block-comments and
    # multi-line strings are not used in dep files in practice.
    matches=$(awk '{
        line = $0
        sub(/\/\/.*/, "", line)
        sub(/#.*/, "", line)
        if (tolower(line) ~ pat) print FILENAME ":" NR ": " $0
    }' pat="$PATTERN" FILENAME="$f" "$f")
    if [ -n "$matches" ]; then
        echo "$matches"
        echo "BANNED DEPENDENCY in $f"
        FOUND=1
    fi
done

if [ "$FOUND" -eq 1 ]; then
    echo "FAIL: banned analytics/telemetry vendor — see RULES.md §Privacy"
    exit 1
fi
echo "OK: no banned dependencies"
