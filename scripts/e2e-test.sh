#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../src"

# Check FFmpeg is available
command -v ffmpeg &>/dev/null || { echo "Error: FFmpeg is required but not found in PATH"; exit 1; }

# Generate test video if needed
if [ ! -f "e2e/testdata/test.mp4" ]; then
    echo "Generating test video..."
    bash e2e/testdata/generate_test_video.sh
fi

echo "Running E2E tests..."
go test -tags=e2e -v -timeout 10m ./e2e/...
