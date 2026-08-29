#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT="$SCRIPT_DIR/test.mp4"

if [ -f "$OUTPUT" ]; then
    echo "test.mp4 already exists, skipping generation"
    exit 0
fi

echo "Generating test video..."
ffmpeg -y \
    -f lavfi -i "testsrc2=duration=3:size=320x240:rate=15" \
    -f lavfi -i "sine=frequency=440:duration=3" \
    -c:v libx264 -preset ultrafast -crf 28 \
    -c:a aac -b:a 64k -pix_fmt yuv420p \
    -movflags +faststart \
    "$OUTPUT"

echo "Generated: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
