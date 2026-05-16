#!/usr/bin/env bash
# Mirrors lectern-ios check_banned_strings.sh — strings.xml + .kt scan.
#
# Pass 1 (full token set, substring): res/values/*.xml. XML tag bodies
# are user-facing by definition; substring match is safe.
#
# Pass 2 (word-bounded, Log.* and comments excluded): .kt main sources.
# Compose Text("…") literals and UI-state fallback strings caught here.
# Log.* lines are exempted (diagnostic, not user-facing). Pure-comment
# lines (lines starting with //, *, or /*) are skipped.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Pass 1: full set, substring match on whole-tag content.
BANNED_XML="streak|consecutive|wrong|incorrect|failed|missed|great job|keep it up|daily goal|🔥|🏆|⭐"
FOUND=0

# ── Pass 1: strings.xml ───────────────────────────────────────────────
while IFS= read -r -d '' file; do
    if grep -nEi "$BANNED_XML" "$file"; then
        echo "BANNED STRING in $file"
        FOUND=1
    fi
done < <(find app/src/main/res/values -name "*.xml" -print0)

# ── Pass 2: .kt main sources ──────────────────────────────────────────
# macOS BSD awk: use explicit non-word-char prefix rather than \b.
# Pattern matches the banned tokens preceded by line-start or non-word
# char and followed by line-end or non-word char (word boundary equiv).
while IFS= read -r -d '' file; do
    matches=$(awk '
    BEGIN {
        # Lowercase token alternation. tolower() applied per-line.
        tokens = "streak|consecutive|wrong|incorrect|failed|missed|great job|keep it up|daily goal"
        emoji = "🔥|🏆|⭐"
    }
    {
        line = $0
        # Strip inline trailing comments before pattern check. Note: a
        # // inside a string literal (e.g. URL) truncates incorrectly,
        # but the surviving prefix still gets matched and the dropped
        # tail is rarely user-facing copy. Acceptable tradeoff.
        sub(/\/\/.*/, "", line)
        # Skip pure-comment lines (now reduced to empty by the strip
        # above, but check leading * and /* for block-comment bodies).
        t = line
        sub(/^[[:space:]]+/, "", t)
        if (t == "" || t ~ /^(\*|\/\*)/) next
        # Skip Log.* diagnostic lines (caller logs; user-facing copy is
        # set separately via strings.xml or fixed literals).
        if (line ~ /(^|[^A-Za-z0-9_])Log\.[dvwie]\(/) next
        # Skip Exception(...) constructor lines: exception messages are
        # diagnostic — callers either Log.e them or substitute a fixed
        # user-facing string before display. Same threat model as Log.*.
        if (line ~ /(^|[^A-Za-z0-9_])Exception\(/) next
        lower = tolower(line)
        # Word-bounded token match (BSD-awk friendly)
        if (lower ~ ("(^|[^a-z0-9_])(" tokens ")([^a-z0-9_]|$)")) {
            print FILENAME ":" NR ": " $0
            next
        }
        # Emoji match (no boundary needed)
        if (line ~ emoji) {
            print FILENAME ":" NR ": " $0
        }
    }' FILENAME="$file" "$file")
    if [ -n "$matches" ]; then
        echo "$matches"
        echo "BANNED STRING in $file"
        FOUND=1
    fi
done < <(find app/src/main/kotlin -name "*.kt" -print0)

if [ "$FOUND" -eq 1 ]; then
    echo "FAIL: banned strings found"
    exit 1
fi
echo "OK: no banned strings"
