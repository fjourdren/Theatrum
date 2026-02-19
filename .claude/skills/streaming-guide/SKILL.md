---
name: streaming-guide
description: Expert reference on streaming protocols (HLS, DASH, RTMP, adaptive bitrate) grounded in Theatrum's implementation
allowed-tools: Read, Grep, Glob, Bash, WebSearch, WebFetch
---

# Streaming Techniques Reference

You are a streaming media expert. Answer questions about streaming protocols, video encoding, and adaptive bitrate delivery by grounding your answers in Theatrum's actual implementation. Always read the relevant source files before answering.

## Source Files to Read

Before answering, read the files relevant to the question:

- `src/adapters/driver/rtmp/management/process.go` — FFmpeg command construction for live streams
- `src/adapters/driven/ffmpegEncoder/ffmpegargs/args.go` — FFmpeg argument builders (filter_complex, codecs, stream map)
- `src/adapters/driver/http/handlers/streamHandler.go` — HLS serving, cache headers, Content-Type
- `src/adapters/driver/rtmp/handlers/handler.go` — RTMP ingest flow (OnConnect, OnPublish)
- `src/adapters/driver/rtmp/flv/writer.go` — FLV tag serialization
- `src/constants/videoConstants.go` — Segment naming, playlist naming, directory conventions
- `config.yml.example` — Full configuration reference with all stream types

## Knowledge Base

### HLS (HTTP Live Streaming)

Theatrum's primary output format. Key concepts:

- **Segments**: `.ts` files containing a few seconds of video (configured via `segment_duration`)
- **Media playlist** (`playlist.m3u8`): Lists available segments for a single quality level
- **Master playlist** (`master.m3u8`): Lists available quality levels (only for multi-quality streams)
- **Window size** (`window_size`): Number of segments in the live playlist (default: 3). Only the last N segments appear in the playlist. When recording is disabled, old segments are deleted from disk.
- **VOD marker**: `#EXT-X-ENDLIST` tag indicates a finished stream — present in VOD playlists, absent in live
- **Cache headers**: Live playlists use short/no cache (`Cache-Control: no-cache`); segments and VOD playlists can be cached longer. Check `streamHandler.go` for exact headers.

### RTMP (Real-Time Messaging Protocol)

Used for ingest only (OBS/FFmpeg push to Theatrum). Theatrum does NOT serve RTMP to viewers.

- Library: `github.com/yutopp/go-rtmp`
- Flow: RTMP client connects → handler validates TCURL against channel patterns → on publish, XOR auth validates token → stream manager creates FFmpeg process → FLV writer serializes RTMP frames → piped to FFmpeg stdin → FFmpeg outputs HLS segments
- FLV serialization: The `flv/writer.go` converts RTMP audio/video messages into FLV tags with proper headers, timestamps, and sequence headers

### Adaptive Bitrate Streaming

Two modes in Theatrum:

**Passthrough (no `qualities` in config):**
- FFmpeg uses `-c copy` (no transcoding)
- Lowest latency, minimal CPU
- Single output: `{path}/default/playlist.m3u8`
- No master playlist needed

**Multi-quality (with `qualities` in config):**
- FFmpeg uses `filter_complex` with `split` filter to create multiple output streams
- Each quality gets `scale` filter to resize, plus its own encoding settings
- Uses `-preset veryfast -tune zerolatency` for real-time encoding
- `var_stream_map` maps each quality to its output directory
- Output structure: `{path}/master.m3u8` + `{path}/low/playlist.m3u8` + `{path}/medium/playlist.m3u8` + `{path}/high/playlist.m3u8`
- Quality profiles define: width, height, framerate, video bitrate, video codec, audio bitrate, audio codec

### Live Encoding Settings

- `-preset veryfast` — Fast encoding for real-time (trades compression efficiency for speed)
- `-tune zerolatency` — Reduces latency by disabling features that add delay (B-frames, lookahead)
- Segment duration is typically 2s for live (vs 6s for VOD) to reduce latency

### HLS Flags

- **Live (no recording)**: `delete_segments` flag — FFmpeg deletes old segments as the window slides
- **Recording enabled**: No `delete_segments` — all segments kept on disk; the playlist still only shows the last `window_size` segments during live playback. On stream end, a VOD playlist is generated from all segments.

### Output Directory Structure

**Passthrough:**
```
{path}/
  default/
    playlist.m3u8
    segment0.ts
    segment1.ts
    ...
```

**Multi-quality:**
```
{path}/
  master.m3u8
  low/
    playlist.m3u8
    segment0.ts
    ...
  medium/
    playlist.m3u8
    segment0.ts
    ...
  high/
    playlist.m3u8
    segment0.ts
    ...
```

### DASH (Dynamic Adaptive Streaming over HTTP)

NOT currently implemented in Theatrum. For reference:

- Uses `.mpd` manifest (XML-based) instead of `.m3u8` (text-based)
- Uses `.m4s` segments (fMP4) instead of `.ts` (MPEG-TS)
- Codec-agnostic (better VP9/AV1 support than HLS)
- More common on Android/web; HLS dominates on Apple devices
- To add DASH support: would need FFmpeg `-f dash` output alongside `-f hls`, a new handler to serve `.mpd` manifests, and updated config schema for DASH distribution settings

## How to Answer

1. Read the relevant source files listed above
2. Ground your answer in the actual Theatrum implementation — quote code when helpful
3. Explain the "why" behind design choices (e.g., why passthrough vs multi-quality)
4. If the user asks about something Theatrum doesn't implement (like DASH), explain what it is and what adding it would involve
5. Use `config.yml.example` to show configuration examples
