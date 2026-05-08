#!/usr/bin/env bash
# Verify no Room entity or DataStore key names match gaze-related terms
set -euo pipefail

PATTERN="face|eye|gaze|lookAt"
FOUND=0

# Check @Entity annotations — gaze-named entities are forbidden everywhere.
while IFS= read -r -d '' file; do
    if grep -nEi "@Entity.*($PATTERN)" "$file"; then
        echo "GAZE DATA LEAK in $file"
        FOUND=1
    fi
done < <(find app/src -name "*.kt" -print0)

# Check Room entity class names in data/db only — gaze classes outside that
# package are legitimate infrastructure and must not be flagged.
while IFS= read -r -d '' file; do
    if grep -nEi "class [A-Za-z0-9_]*(face|eye|gaze|lookat)[A-Za-z0-9_]*" "$file"; then
        echo "GAZE DATA LEAK in $file"
        FOUND=1
    fi
done < <(find app/src -path "*/data/db/*.kt" -print0)

# Check DataStore store names and preference key names — raw gaze terms forbidden.
# Matches: preferencesDataStore("..."), stringPreferencesKey("..."), etc.
while IFS= read -r -d '' file; do
    if grep -nEi "(preferencesDataStore|[a-zA-Z]+PreferencesKey)\s*\(\s*[\"'].*($PATTERN)" "$file"; then
        echo "GAZE DATA LEAK in $file"
        FOUND=1
    fi
done < <(find app/src -name "*.kt" -print0)

if [ "$FOUND" -eq 1 ]; then
    echo "FAIL: gaze data leak detected"
    exit 1
fi
echo "OK: no gaze data leak"
