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
    if grep -niE "$PATTERN" "$f"; then
        echo "BANNED DEPENDENCY in $f"
        FOUND=1
    fi
done

if [ "$FOUND" -eq 1 ]; then
    echo "FAIL: banned analytics/telemetry vendor — see RULES.md §Privacy"
    exit 1
fi
echo "OK: no banned dependencies"
