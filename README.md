# Theatrum Streaming

![](imgs/logo.png)


A powerful and flexible streaming server that supports video on demand (VOD) and live RTMP streaming with adaptive bitrate capabilities. Built to handle multiple quality profiles and HLS protocol.

## Features

- 📺 **Video on Demand**
  - Support for pre-encoded video streaming
  - Automatic mp4 encoding
  - Optional source file deletion after encoding

- 📡 **Live Streaming**
  - RTMP ingest (OBS, FFmpeg, etc.)
  - Passthrough mode (codec copy, lowest latency)
  - Multi-quality transcoding (adaptive bitrate for viewers)
  - XOR-based stream key authentication
  - Optional recording with VOD playlist generation

- 🎯 **Quality Profiles**
  - Multi-qualities management
  - Customizable audio and video bitrates
  - Shared quality profiles between VOD and live

- 🔄 **Streaming Protocols**
  - HLS (HTTP Live Streaming)
  - Configurable segment duration

- ⚙️ **Configuration**
  - Fully configurable through YAML
  - Customizable stream endpoints
  - Flexible quality profiles
  - Adjustable storage paths
  - Domain name customization
  - Global streams playlist m3u8

## Configuration

The server is configured through `config.yml`. Here's a breakdown of the main configuration sections:

### Server Configuration
```yaml
server:
  http: 8080  # HTTP port for HLS streaming
```

### Quality Profiles
Quality profiles are fully customizable. Here's an example configuration:
```yaml
quality_profiles:
  low:
    width: 640
    height: 360
    framerate: 24
    bitrate: "800k"
    codec: "libx264"
    audio:
      bitrate: "96k"
      codec: "aac"
  # Add more profiles as needed
```

### Stream Templates
The server supports different types of stream templates:

#### encoded_video
```yaml
stream_templates:
  default:  # When there is not quality, then the default directory is used in the storage path
    stream: &default_stream_config
      type: video_encoded
      path: "livestream/{username}"
      qualities:
        low: *LOW
        medium: *MEDIUM
        high: *HIGH
      distribution:
        hls:
          segment_duration: 6
```

#### unencoded_video
```yaml
stream_templates:
  video:
    stream: &video_unencoded_config
      type: video_unencoded
      video_input_path: "raw_videos/{username}"
      path: "records/{username}"
      delete_after_encoding: true
      qualities:
        low: *LOW
        medium: *MEDIUM
        high: *HIGH
      distribution:
        hls:
          segment_duration: 6
        dash:
          segment_duration: 6
          manifest_window: 5
```

#### live

Live streams support two modes:

- **Passthrough** (no `qualities`): codec copy, no transcoding, lowest latency. Outputs a single HLS playlist.
- **Multi-quality** (with `qualities`): real-time transcoding into multiple quality levels with adaptive bitrate. Outputs a master playlist (`master.m3u8`) referencing per-quality playlists. Uses `-preset veryfast -tune zerolatency` for real-time encoding.

```yaml
stream_templates:
  # Passthrough mode (no transcoding)
  live_passthrough:
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "your-secure-rtmp-secret-key"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3       # Segments in live playlist (default: 3)

  # Multi-quality transcoding
  live_multiquality:
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "your-secure-rtmp-secret-key"
      auth_token_template: "{username}"
      qualities:        # Optional: add qualities to enable transcoding
        low: *LOW
        medium: *MEDIUM
        high: *HIGH
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
```

**Output directory structure:**
```
# Passthrough (no qualities)
data/live/myuser/default/
  playlist.m3u8 + segment_*.ts

# Multi-quality (with qualities)
data/live/myuser/
  master.m3u8
  low/playlist.m3u8 + segment_*.ts
  medium/playlist.m3u8 + segment_*.ts
  high/playlist.m3u8 + segment_*.ts
```

For live streams, authentication is required using configurable XOR-based tokens.

**Required fields for live streams:**
- `live_stream_key` - Secret key used for XOR operation
- `auth_token_template` - Template specifying which URL variables to use for authentication

**How authentication works:**

1. Server extracts variables from the RTMP URL based on the channel pattern
2. Server builds the XOR input by replacing `{var}` placeholders in `auth_token_template` with URL values
3. Server XORs the input with `live_stream_key` and hex-encodes the result
4. Client must provide this token as the stream key
5. Streaming is allowed only if the tokens match

**Simple example** (single variable):
```yaml
channels:
  "/user/{username}":
    stream:
      type: live
      auth_token_template: "{username}"
      live_stream_key: "secret"
```
- RTMP URL: `rtmp://server/user/alice`
- Stream key: `hex(XOR("alice", "secret"))`

**Advanced example** (multiple variables):
```yaml
channels:
  "/room/{room_id}/user/{username}":
    stream:
      type: live
      auth_token_template: "{room_id}{username}"
      live_stream_key: "secret"
```
- RTMP URL: `rtmp://server/room/42/user/alice`
- Stream key: `hex(XOR("42alice", "secret"))`

> **Note:** All variables in `auth_token_template` must exist in the channel pattern, otherwise configuration validation will fail at startup.

### Source File Management (video_unencoded only)
For `video_unencoded` streams, you can configure automatic deletion of source files after successful encoding:

```yaml
delete_after_encoding: false  # Default: false
```

- When set to `true`, the original video file will be automatically deleted after successful encoding
- This helps save storage space for large video files
- Only applies to `video_unencoded` stream types
- Deletion only occurs after successful encoding - if encoding fails, the source file is preserved

### Stream Distribution
HLS configuration includes:
- Segment duration: configurable per stream
- Window size: number of segments in the live playlist (default: 3, live streams only)

```yaml
distribution:
  hls:
    segment_duration: 6
    window_size: 5       # Live streams only (default: 3)
```

### Viewer & View Counting

Theatrum can track concurrent viewers and total views per stream by monitoring `.ts` segment requests from unique client IPs. Both use a **delayed window** — they only count after a client has been watching continuously for `window` seconds.

- **`viewers.txt`** (live streams only): Returns the number of concurrent viewers. A viewer only appears in the count after watching for at least `window` seconds. If they stop requesting segments for `window` seconds, their session resets.
- **`views.txt`** (all stream types): Returns the total number of viewing sessions. A view is only counted once a client has watched continuously for `window` seconds. A new session starts after `window` seconds of inactivity.

Setting `window: 0` counts immediately on first request (no delay).

Both files are served alongside `master.m3u8` at the stream's base URL (e.g., `http://localhost:8080/live/username/viewers.txt`).

```yaml
channels:
  "/live/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "your-key"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
      viewers:
        enabled: true
        window: 30          # Minimum watch time in seconds (default: 30)
      views:
        enabled: true
        window: 30          # Minimum watch time in seconds (default: 30)
```

When disabled (default), requesting `viewers.txt` or `views.txt` returns 404. Client IP is extracted from the `X-Forwarded-For` header (for reverse proxy setups) or `RemoteAddr`.

View counts are persisted to disk and survive server restarts. When recording is enabled, `views.txt` is preserved alongside the recording. When recording is disabled, all files (including `views.txt`) are deleted on stream end.

### Channel Endpoints
Channel endpoints can be configured with a templating system.

Example :
```yaml
channels:
  "/user/{username}":
    stream:
      <<: *default_stream_config
```

### Built-in Template Functions

In addition to user variables extracted from URL patterns (`{username}`, `{room_id}`, etc.), path templates support **built-in functions** using the `{%FUNC%}` syntax. These generate values automatically at resolution time.

**Available functions:**

| Function | Description | Example output |
|----------|-------------|----------------|
| `{%STARTING_DATE%}` | Current date and time | `2026-02-07_15-30-00` |
| `{%UUID%}` | Random UUID v4 | `550e8400-e29b-41d4-a716-446655440000` |

**Example:**
```yaml
channels:
  "/user/{username}":
    stream:
      type: live
      path: "livestreams/{username}/{%STARTING_DATE%}"
      # Resolves to: livestreams/alice/2026-02-07_15-30-00
```

Built-in functions can be mixed freely with user variables in any path template.

## Getting Started

1. Clone the repository:
```bash
git clone https://github.com/fjourdren/theatrum.git
cd theatrum
```

2. Configure your server:
   - Copy `config.yml.example` to `config.yml`
   - Adjust the configuration according to your needs:
     - Set up your quality profiles
     - Configure storage paths
     - Adjust stream templates
     - Set up endpoints

3. Run with golang:
```bash
go run ./src/cmd/main.go
```

## Requirements

- Go >= 1.24
- FFmpeg >= 4.4.0
- Storage space for video segments
- Network bandwidth according to your quality profiles

### FFmpeg Requirements
- libx264 encoder
- aac audio codec
- HLS segmenter

## Installation

### Using Docker (Recommended)

1. Build the Docker image:
```bash
docker build -t theatrum .
```

2. Run the container:
```bash
docker run -d \
  -p 8080:8080 \
  -v /path/to/your/config.yml:/app/config.yml \
  -v /path/to/your/storage:/app/storage \
  theatrum
```

## Configuration Examples

### Basic Quality Profile
```yaml
quality_profiles:
  standard:
    width: 1280
    height: 720
    framerate: 30
    bitrate: "2500k"
    codec: "libx264"
    audio:
      bitrate: "128k"
      codec: "aac"
```

### Custom Stream Template
```yaml
stream_templates:
  custom:
    stream:
      type: video_encoded
      path: "custom/{username}"
      qualities:
        standard: *standard_profile
      distribution:
        hls:
          segment_duration: 4
```

### Live Stream (Passthrough)
```yaml
stream_templates:
  live_passthrough:
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "your-secure-rtmp-secret-key"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
```

### Live Stream (Multi-Quality)
```yaml
stream_templates:
  live_multiquality:
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "your-secure-rtmp-secret-key"
      auth_token_template: "{username}"
      qualities:
        low: *LOW
        medium: *MEDIUM
        high: *HIGH
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
```

### Live Stream with Recording
When recording is enabled, all segments are kept on disk during the stream (while only the last `window_size` segments appear in the live playlist). When the stream ends, a VOD playlist is generated.

Recording supports two modes: files can be **moved** to a separate `record.path`, or kept **in-place** in `stream.path`.

```yaml
stream_templates:
  # Recording with separate destination
  live_recorded:
    stream:
      type: live
      path: "live/{username}/{%STARTING_DATE%}"
      live_stream_key: "your-secure-rtmp-secret-key"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
      record:
        enabled: true
        path: "recordings/{username}/{%STARTING_DATE%}"

  # In-place recording (files stay in stream.path)
  live_recorded_inplace:
    stream:
      type: live
      path: "live/{username}/{%STARTING_DATE%}"
      live_stream_key: "your-secure-rtmp-secret-key"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
      record:
        enabled: true
        # No path = files stay in stream.path after stream ends
```

- `record.enabled`: Set to `true` to enable recording (default: `false`)
- `record.path` (optional): Destination path for the recording. Supports the same `{var}` and `{%FUNC%}` placeholders as `stream.path`. Built-in functions resolve to the same values within the same stream session. When omitted, files remain in `stream.path` (in-place recording).
- Without recording (default): old segments are deleted during streaming, and all remaining files are cleaned up when the stream ends.
- With recording + `record.path`: all segments accumulate on disk, a VOD playlist is generated, and files are moved to `record.path`.
- With recording, no `record.path`: all segments accumulate on disk, a VOD playlist is generated in-place, and files stay in `stream.path`.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Third-Party Licenses

- go-rtmp (https://github.com/yutopp/go-rtmp) — © 2018-2025 Yusuke Topp — Boost Software License 1.0 (BSL-1.0)

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

## Support

For support, please open an issue in the GitHub repository or contact the maintainers.