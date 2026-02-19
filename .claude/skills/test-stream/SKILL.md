---
name: test-stream
description: Boot the app, stream via RTMP, watch via HLS, and validate the full pipeline
disable-model-invocation: true
---

# Full Pipeline E2E Tester

This skill walks through a complete end-to-end test of Theatrum: build, start, stream via RTMP, validate HLS output, check metrics, and clean up. Use `$ARGUMENTS` to customize (e.g., `/test-stream passthrough` or `/test-stream with-recording`).

## Current Config

```
!cat config.yml 2>/dev/null || echo "No config.yml found — will create a test config"
```

## Step 1: Prerequisites Check

Verify required tools are installed:

```bash
ffmpeg -version | head -1
go version
curl --version | head -1
```

If any are missing, stop and inform the user.

## Step 2: Test Video

If no test input video exists, generate a 30-second test pattern:

```bash
ffmpeg -f lavfi -i "testsrc=duration=30:size=1280x720:rate=30" \
       -f lavfi -i "sine=frequency=440:duration=30" \
       -c:v libx264 -preset ultrafast -c:a aac -b:a 128k \
       -pix_fmt yuv420p /tmp/theatrum_test_input.mp4
```

## Step 3: Config Setup

If no `config.yml` exists, create a minimal test config. Use a passthrough live stream for simplicity:

```yaml
application:
  public_path: "http://localhost:8080"

server:
  http: 8080
  rtmp: 1935
  rtmp_config:
    reconnect_delay: 5
    cleanup_delay: 10

channels:
  "/test/{username}":
    stream:
      type: live
      path: "test/{username}"
      live_stream_key: "testkey123"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
      viewers:
        enabled: true
        window: 5
      views:
        enabled: true
        window: 5
```

## Step 4: Build & Start

```bash
# Build
cd src && go build -o ../theatrum ./cmd/main.go && cd ..

# Start in background
./theatrum &
THEATRUM_PID=$!
echo "Theatrum started with PID $THEATRUM_PID"

# Wait for startup
sleep 2
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/metrics
```

Expected: HTTP 200 from `/metrics`.

## Step 5: Compute Auth Token

The RTMP auth token is computed via XOR. For the test config above (key=`testkey123`, username=`alice`):

```python
python3 -c "
key = 'testkey123'
username = 'alice'
result = bytes([ord(c) ^ ord(key[i % len(key)]) for i, c in enumerate(username)])
print(result.hex())
"
```

This outputs the hex token to use as the RTMP publishing name.

## Step 6: Stream via RTMP

```bash
TOKEN=$(python3 -c "
key = 'testkey123'
username = 'alice'
result = bytes([ord(c) ^ ord(key[i % len(key)]) for i, c in enumerate(username)])
print(result.hex())
")

ffmpeg -re -i /tmp/theatrum_test_input.mp4 \
       -c copy -f flv \
       "rtmp://localhost/test/alice/$TOKEN" &
FFMPEG_PID=$!
echo "FFmpeg streaming with PID $FFMPEG_PID"
```

Wait a few seconds for segments to be generated:
```bash
sleep 8
```

## Step 7: Validate HLS Output

Check that HLS playlists and segments are being served:

```bash
# For passthrough, check the default playlist
echo "=== Playlist ==="
curl -s http://localhost:8080/test/alice/default/playlist.m3u8

echo ""
echo "=== Segment check ==="
# Get the first segment URL from the playlist and verify it's downloadable
SEGMENT=$(curl -s http://localhost:8080/test/alice/default/playlist.m3u8 | grep '.ts' | head -1)
if [ -n "$SEGMENT" ]; then
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/test/alice/default/$SEGMENT")
    echo "Segment HTTP status: $HTTP_CODE"
else
    echo "ERROR: No segments found in playlist"
fi
```

For multi-quality streams, also check:
```bash
curl -s http://localhost:8080/test/alice/master.m3u8
curl -s http://localhost:8080/test/alice/low/playlist.m3u8
```

## Step 8: Check Metrics

```bash
echo "=== Theatrum Metrics ==="
curl -s http://localhost:8080/metrics | grep "theatrum_"
```

Expected metrics to see:
- `theatrum_stream_viewers` — Concurrent viewers per stream
- `theatrum_stream_views` — Total views per stream
- `theatrum_http_requests_total` — HTTP request count
- `theatrum_stream_duration_seconds` — Active stream durations
- `theatrum_ffmpeg_exits_total` — FFmpeg process exits
- `theatrum_rtmp_received_bytes_total` — Bytes received via RTMP

## Step 9: Viewer/View Tracking (Optional)

If viewers/views are enabled, test them:

```bash
# Simulate a viewer by requesting segments repeatedly
for i in $(seq 1 10); do
    SEGMENT=$(curl -s http://localhost:8080/test/alice/default/playlist.m3u8 | grep '.ts' | tail -1)
    [ -n "$SEGMENT" ] && curl -s -o /dev/null "http://localhost:8080/test/alice/default/$SEGMENT"
    sleep 2
done &

# After the window period, check counts
sleep 10
echo "Viewers: $(curl -s http://localhost:8080/test/alice/viewers.txt)"
echo "Views: $(curl -s http://localhost:8080/test/alice/views.txt)"
```

## Step 10: Cleanup

```bash
# Stop FFmpeg
kill $FFMPEG_PID 2>/dev/null

# Wait for stream cleanup
sleep 5

# Stop Theatrum
kill $THEATRUM_PID 2>/dev/null

# Remove test data
rm -rf data/test/
rm -f /tmp/theatrum_test_input.mp4

echo "Cleanup complete"
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` on RTMP | Theatrum not running or port in use | Check `$THEATRUM_PID`, check `lsof -i :1935` |
| `401` or auth error | Wrong token | Recompute token; verify `live_stream_key` and `auth_token_template` match config |
| Empty playlist | FFmpeg not started or segments not yet written | Wait longer; check FFmpeg stderr; check `data/` directory |
| `404` on playlist | Wrong URL path | Path must match `stream.path` in config (e.g., `test/alice/default/playlist.m3u8`) |
| No metrics | Theatrum crashed | Check stderr output; look for panic or bind errors |
| Segments 404 but playlist 200 | Segments being deleted faster than requested | Increase `window_size` in config |
| FFmpeg exits immediately | Input file issue or RTMP rejected | Check FFmpeg stderr; verify RTMP URL and token |
