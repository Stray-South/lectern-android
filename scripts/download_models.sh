#!/usr/bin/env bash
# Downloads MediaPipe model files required at runtime.
# Run once before first build, or after cloning the repo.
# Model files are excluded from git (.gitignore) due to size.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSETS_DIR="$SCRIPT_DIR/../app/src/main/assets"

mkdir -p "$ASSETS_DIR"

download_if_missing() {
    local url="$1"
    local dest="$2"
    if [ -f "$dest" ]; then
        echo "Already present: $dest"
    else
        echo "Downloading $(basename "$dest")..."
        curl -sSL "$url" -o "$dest"
        echo "  -> saved to $dest"
    fi
}

# face_landmarker.task — float16, ~3.6 MB
# Used by GazeProviderImpl via MediaPipe Face Landmarker Tasks API.
# Canonical source: https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker
download_if_missing \
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task" \
    "$ASSETS_DIR/face_landmarker.task"

echo ""
echo "All model files present. Ready to build."
