#!/usr/bin/env bash
# check_release_logging.sh — CI gate for RULES.md §Code quality.
#
# Bans Log.d, Log.v, and println in app/src/main/kotlin sources.
# Log.e, Log.w, Log.i are allowed (error/warning/info severities are
# legitimate production-path diagnostics; ProGuard can strip them at
# release if required — that hardening is tracked separately).
#
# Scoped to main sources only — test/androidTest may legitimately use
# Log.d for development. Comment lines are stripped before matching
# (a // Log.d in a comment is not a violation).

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

FOUND=0

while IFS= read -r -d '' file; do
    # Strip from // (inline comments) to end of line, plus collapse
    # single-line block comments, before pattern match. Awk used over
    # sed for portable in-stream stripping.
    # macOS BSD awk does not support \< / \> word boundaries (gawk only).
    # Use an explicit non-word-char-or-start-of-line prefix instead so
    # MyLog.d(...) does not match while android.util.Log.d(...) does.
    matches=$(awk '{
        line = $0
        sub(/\/\*.*\*\//, "", line)
        sub(/\/\/.*/, "", line)
        if (line ~ /(^|[^A-Za-z0-9_])(Log\.[dv]\(|println\()/) {
            print FILENAME ":" NR ": " $0
        }
    }' FILENAME="$file" "$file")
    if [ -n "$matches" ]; then
        echo "$matches"
        echo "DEBUG LOGGING in $file"
        FOUND=1
    fi
done < <(find app/src/main/kotlin -name "*.kt" -print0)

if [ "$FOUND" -eq 1 ]; then
    echo "FAIL: Log.d / Log.v / println in main sources — RULES.md §Code quality"
    exit 1
fi
echo "OK: no debug logging in main sources"
