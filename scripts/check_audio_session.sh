#!/usr/bin/env bash
# check_audio_session.sh — CI gate for ADR-AND-A.
#
# Mirrors lectern-ios/scripts/check_audio_session.sh adapted for
# Kotlin/Android. AudioSessionCoordinator.kt is the ONLY file in
# app/src/main/kotlin/ permitted to call AudioManager.requestAudioFocus,
# AudioManager.abandonAudioFocusRequest, or to construct
# AudioFocusRequest.Builder. All other files must route audio-focus
# transitions through the coordinator.
#
# Scoped to app/src/main/kotlin only — test sources may legitimately
# reference these APIs in assertions or mocks.
#
# Usage: ./scripts/check_audio_session.sh
# Exit 0 = clean. Exit 1 = violations found.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PATTERN='(requestAudioFocus|abandonAudioFocus|abandonAudioFocusRequest|AudioFocusRequest\.Builder)'
FOUND=0

# awk-based scan: strip inline trailing comments and skip pure-comment lines
# before pattern-matching, so legitimate references to these APIs inside Kotlin
# comments (e.g. "// abandonAudioFocusRequest is called below") don't FP.
# Mirrors the filter approach used in check_release_logging.sh.
while IFS= read -r -d '' file; do
    basename=$(basename "$file")
    if [ "$basename" = "AudioSessionCoordinator.kt" ]; then
        continue
    fi
    matches=$(awk -v pat="$PATTERN" '
        {
            line = $0
            # Strip inline trailing // comments.
            sub(/\/\/.*/, "", line)
            # Skip pure-comment lines (leading //, /*, or block-comment-body *).
            t = line
            sub(/^[[:space:]]+/, "", t)
            if (t == "" || t ~ /^(\*|\/\*)/) next
            if (line ~ pat) print FILENAME ":" NR ": " $0
        }' FILENAME="$file" "$file")
    if [ -n "$matches" ]; then
        echo "$matches"
        echo "AUDIO SESSION VIOLATION in $file"
        FOUND=1
    fi
done < <(find app/src/main/kotlin -name "*.kt" -print0)

if [ "$FOUND" -eq 1 ]; then
    echo "FAIL: audio session calls outside AudioSessionCoordinator.kt — see ADR-AND-A"
    exit 1
fi
echo "OK: audio session ownership clean"
