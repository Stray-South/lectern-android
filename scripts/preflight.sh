#!/usr/bin/env bash
# Run all CI gates locally. Exit 0 only if all pass.
set -euo pipefail

echo "=== Lectern Android preflight ==="

echo "[1/7] Build..."
./gradlew assembleDebug --no-daemon --quiet
echo "  PASS"

echo "[2/7] Unit tests..."
./gradlew testDebugUnitTest --no-daemon --quiet
echo "  PASS"

echo "[3/7] Detekt..."
./gradlew detekt --no-daemon --quiet
echo "  PASS"

echo "[4/7] ktlint..."
./gradlew ktlintCheck --no-daemon --quiet
echo "  PASS"

echo "[5/7] Banned strings..."
bash "$(dirname "$0")/check_banned_strings.sh"
echo "  PASS"

echo "[6/7] Gaze data leak..."
bash "$(dirname "$0")/check_gaze_data_leak.sh"
echo "  PASS"

echo "[7/7] Audio session ownership..."
bash "$(dirname "$0")/check_audio_session.sh"
echo "  PASS"

echo ""
echo "=== All gates green. Ready to commit. ==="
