#!/usr/bin/env bash
# Mirrors lectern-ios check_banned_strings.sh for Android strings.xml
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

BANNED="streak|consecutive|wrong|incorrect|failed|missed|great job|keep it up|daily goal|🔥|🏆|⭐"
FOUND=0

while IFS= read -r -d '' file; do
    if grep -nEi "$BANNED" "$file"; then
        echo "BANNED STRING in $file"
        FOUND=1
    fi
done < <(find app/src/main/res/values -name "*.xml" -print0)

while IFS= read -r -d '' file; do
    if grep -nEi "$BANNED" "$file"; then
        echo "BANNED STRING in $file"
        FOUND=1
    fi
done < <(find app/src/main -name "*.kt" -print0)

if [ "$FOUND" -eq 1 ]; then
    echo "FAIL: banned strings found"
    exit 1
fi
echo "OK: no banned strings"
