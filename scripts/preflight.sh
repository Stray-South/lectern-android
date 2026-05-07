#!/usr/bin/env bash
# Run all CI gates locally. Exit 0 only if all pass.
set -euo pipefail

echo "=== Lectern Android preflight ==="

echo "[1/6] Build..."
./gradlew assembleDebug --no-daemon --quiet
echo "  PASS"

echo "[2/6] Unit tests..."
./gradlew testDebugUnitTest --no-daemon --quiet
echo "  PASS"

echo "[3/6] Detekt..."
./gradlew detekt --no-daemon --quiet
echo "  PASS"

echo "[4/6] ktlint..."
./gradlew ktlintCheck --no-daemon --quiet
echo "  PASS"

echo "[5/6] Banned strings..."
scripts/check_banned_strings.sh
echo "  PASS"

echo "[6/6] Gaze data leak..."
scripts/check_gaze_data_leak.sh
echo "  PASS"

echo ""
echo "=== All gates green. Ready to commit. ==="
