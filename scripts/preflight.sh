#!/usr/bin/env bash
# Run all CI gates locally. Exit 0 only if all pass.
set -euo pipefail

echo "=== Lectern Android preflight ==="

echo "[1/9] Build..."
./gradlew assembleDebug --no-daemon --quiet
echo "  PASS"

echo "[2/9] Unit tests..."
./gradlew testDebugUnitTest --no-daemon --quiet
echo "  PASS"

echo "[3/9] Detekt..."
./gradlew detekt --no-daemon --quiet
echo "  PASS"

echo "[4/9] ktlint..."
./gradlew ktlintCheck --no-daemon --quiet
echo "  PASS"

echo "[5/9] Banned strings..."
bash "$(dirname "$0")/check_banned_strings.sh"
echo "  PASS"

echo "[6/9] Gaze data leak..."
bash "$(dirname "$0")/check_gaze_data_leak.sh"
echo "  PASS"

echo "[7/9] Audio session ownership..."
bash "$(dirname "$0")/check_audio_session.sh"
echo "  PASS"

echo "[8/9] Banned dependencies..."
bash "$(dirname "$0")/check_banned_deps.sh"
echo "  PASS"

echo "[9/9] Release-build logging..."
bash "$(dirname "$0")/check_release_logging.sh"
echo "  PASS"

echo ""
echo "=== All gates green. Ready to commit. ==="
