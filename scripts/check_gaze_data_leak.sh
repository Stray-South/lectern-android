#!/usr/bin/env bash
# Verify no Room entity or DataStore key names match gaze-related terms
set -euo pipefail

PATTERN="face|eye|gaze|lookAt"
FOUND=0

# Check Room entity class names
while IFS= read -r -d '' file; do
    if grep -nEi "@Entity.*($PATTERN)|class [A-Za-z0-9_]*(face|eye|gaze|lookat)[A-Za-z0-9_]*" "$file"; then
        echo "GAZE DATA LEAK in $file"
        FOUND=1
    fi
done < <(find app/src -name "*.kt" -print0)

if [ "$FOUND" -eq 1 ]; then
    echo "FAIL: gaze data leak detected"
    exit 1
fi
echo "OK: no gaze data leak"
