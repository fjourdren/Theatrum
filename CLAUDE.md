# Theatrum - Claude Code Context

## Project Overview

Theatrum is a streaming server built in Go supporting:
- Video on Demand (VOD) with adaptive bitrate streaming
- Multiple quality profiles (low, medium, high)
- HLS (HTTP Live Streaming) protocol
- Live RTMP streaming

## Architecture

**Pattern:** Hexagonal (Ports & Adapters) Architecture

```
src/
├── cmd/main.go              # Entry point with DI container (uber/dig)
├── constants/               # App constants, paths, video settings
├── domain/
│   ├── models/              # Stream, Quality, Application, Server, Distribution
│   ├── services/            # ApplicationService, StreamService, EncodeService, PathTemplateService
│   ├── repositories/        # Port interfaces (ConfigurationPort, EncoderPort, StoragePort)
│   └── jobs/                # EncodeJobQueue, VideoUnencodedDetector
└── adapters/
    ├── driven/              # Output adapters
    │   ├── ffmpegEncoder/   # FFmpeg encoding implementation
    │   │   ├── ffmpegargs/  # Shared FFmpeg argument builders (filter, codecs, stream map)
    │   │   └── repositories/# EncoderPort implementation (VOD encoding)
    │   ├── fileAccess/      # File system operations
    │   └── yamlConfigFile/  # YAML configuration loader
    └── driver/              # Input adapters
        ├── http/            # HTTP/HLS server
        ├── rtmp/            # RTMP streaming server
        └── ports/           # Port interfaces for drivers
```

## RTMP Implementation

### Components

| Component | Path | Description |
|-----------|------|-------------|
| Server | `adapters/driver/rtmp/rtmpServer.go` | Main RTMP server lifecycle |
| Handler | `adapters/driver/rtmp/handlers/handler.go` | Connection handling, publish flow |
| Manager | `adapters/driver/rtmp/management/manager.go` | Active stream management |
| Process | `adapters/driver/rtmp/management/process.go` | FFmpeg process wrapper |
| Auth | `adapters/driver/rtmp/auth/` | URL pattern matching, XOR auth |
| FLV | `adapters/driver/rtmp/flv/` | FLV tag serialization |

### Data Flow

```
RTMP Client (OBS, etc.)
    ↓
RTMP Server (port 1935)
    ↓
Handler.OnConnect() - Validates TCURL against channel patterns
    ↓
Handler.OnPublish() - XOR token authentication
    ↓
StreamManager.GetOrCreateStream() - Creates/reuses FFmpeg process
    ↓
FLV Writer - Serializes RTMP frames to FLV format
    ↓
FFmpeg (stdin pipe) - Converts FLV to HLS (passthrough or multi-quality)
    ↓
HLS output (single playlist or master + per-quality playlists)
    ↓
HTTP Server (port 8080) - Serves HLS to viewers
```

### Authentication

Live streams use configurable XOR-based authentication via `auth_token_template`:

1. Client connects to `rtmp://server/user/{username}`
2. TCURL matched against channel patterns in config
3. Server builds XOR input from `auth_token_template` by replacing `{var}` placeholders with URL values
4. On publish, client sends `publishingName = XOR(auth_input, live_stream_key)` (hex encoded)
5. Server validates by computing expected token

**Required fields for live streams:**
- `live_stream_key` - Secret key for XOR operation
- `auth_token_template` - Template specifying which URL variables to use (e.g., `{username}`, `{room_id}{username}`)

## Configuration

Copy `config.yml.example` to `config.yml`:

```yaml
server:
  http: 8080
  rtmp: 1935

channels:
  # Passthrough (codec copy, no transcoding, lowest latency)
  "/user/{username}":
    stream:
      type: live
      path: "livestreams/{username}"
      live_stream_key: "your-secret-key"
      auth_token_template: "{username}"  # REQUIRED for live streams
      distribution:
        hls:
          segment_duration: 2

  # Multi-quality transcoding (adaptive bitrate, requires CPU)
  "/premium/{username}":
    stream:
      type: live
      path: "livestreams/{username}"
      live_stream_key: "your-secret-key"
      auth_token_template: "{username}"
      qualities:           # Optional: omit for passthrough
        low: ...
        medium: ...
        high: ...
      distribution:
        hls:
          segment_duration: 2
```

**Live stream modes:**
- **Without `qualities`** (passthrough): codec copy, single playlist at `{path}/default/playlist.m3u8`
- **With `qualities`** (transcoding): multi-quality HLS with `master.m3u8` + per-quality subdirs (`low/`, `medium/`, `high/`), uses `-preset veryfast -tune zerolatency` for real-time encoding

## Stream Types

- `video_encoded` - Pre-encoded VOD content
- `video_unencoded` - Raw videos to be encoded
- `live` - Live RTMP streams (passthrough or multi-quality transcoding)

## Development

```bash
# Build
go build -o theatrum ./src/cmd/main.go

# Run
./theatrum

# Test RTMP (using FFmpeg)
ffmpeg -re -i input.mp4 -c copy -f flv "rtmp://localhost/user/myuser"
```

## Key Dependencies

- `github.com/yutopp/go-rtmp` - RTMP protocol implementation
- `go.uber.org/dig` - Dependency injection
- `gopkg.in/yaml.v3` - YAML configuration parsing

## Conventions

- **Files**: camelCase for Go files (e.g., `streamService.go`)
- **Packages**: lowercase single words (e.g., `handlers`, `models`)
- **Interfaces**: Port interfaces suffixed with 'Port' (e.g., `ConfigurationPort`, `EncoderPort`)
- **Tests**: Same package with `_test.go` suffix
