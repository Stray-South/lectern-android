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
    local expected_sha="${3:-}"
    if [ -f "$dest" ]; then
        echo "Already present: $dest"
    else
        echo "Downloading $(basename "$dest")..."
        curl -sSL "$url" -o "$dest"
        echo "  -> saved to $dest"
    fi
    # Verify checksum on every invocation, even when the file was already present —
    # catches the case where /latest/ silently updated the upstream model between
    # runs (the MediaPipe CDN does not expose stable per-version URL paths; this
    # sha256 pin is the actual version anchor per ADR-AND-Q + F.1 spirit).
    if [ -n "$expected_sha" ]; then
        local actual_sha
        actual_sha=$(shasum -a 256 "$dest" | awk '{print $1}')
        if [ "$actual_sha" != "$expected_sha" ]; then
            echo "  FAIL: sha256 mismatch for $dest"
            echo "    expected: $expected_sha"
            echo "    actual:   $actual_sha"
            echo "    The model file may have drifted from the pinned version."
            echo "    Delete and re-run, or update the expected sha if upstream change is intentional."
            exit 1
        fi
    else
        echo "  WARN: no sha256 pin for $(basename "$dest") — supply-chain integrity unenforced."
        echo "  To populate: shasum -a 256 $dest"
    fi
}

# face_landmarker.task — float16, ~3.6 MB
# Used by GazeProviderImpl via MediaPipe Face Landmarker Tasks API.
# Canonical source: https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker
# The URL uses /latest/ because MediaPipe does not publish stable per-version paths
# in this bucket. The sha256 below is the actual version pin.
# To rotate the model: re-download, run `shasum -a 256 <file>`, update the value,
# DEVLOG the change with rationale.
FACE_LANDMARKER_SHA256=""  # TODO: populate after first verified download (warn-only until set)
download_if_missing \
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task" \
    "$ASSETS_DIR/face_landmarker.task" \
    "$FACE_LANDMARKER_SHA256"

echo ""
echo "All model files present. Ready to build."
