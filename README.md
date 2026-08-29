# Theatrum Streaming

![](imgs/logo.png)

A powerful and flexible streaming server that supports video on demand (VOD), live RTMP ingest and
restreaming from external sources, with adaptive bitrate capabilities. Built to handle multiple
quality profiles with HLS and MPEG-DASH protocols.

> **Java port.** This is the Java/Spring Boot implementation of Theatrum. Behaviour, config format
> and the HTTP/RTMP surface are intentionally identical to the [Go original](https://github.com/fjourdren/theatrum);
> only the internals differ. The RTMP protocol is implemented in-tree — no third-party library.

## Features

- 📺 **Video on Demand**
  - Support for pre-encoded video streaming
  - Automatic mp4 encoding on startup
  - Optional source file deletion after encoding

- 📡 **Live Streaming**
  - RTMP ingest (OBS, FFmpeg, etc.)
  - Passthrough mode (codec copy, lowest latency)
  - Multi-quality transcoding (adaptive bitrate for viewers)
  - XOR-based stream key authentication
  - Optional recording with VOD playlist generation
  - Periodic thumbnail generation

- 🔁 **Restreaming**
  - Pull from any external URL FFmpeg can read (RTMP, HLS, …)
  - Auto-started at boot, auto-reconnect with exponential backoff
  - Recording, viewers and views work exactly as they do for live

- 🎯 **Quality Profiles**
  - Multi-qualities management
  - Customizable audio and video bitrates
  - Shared quality profiles between VOD, live and restream

- 🔄 **Streaming Protocols**
  - HLS (HTTP Live Streaming)
  - MPEG-DASH (standalone or dual-mode with HLS)
  - Configurable segment duration

- 📊 **Monitoring**
  - Prometheus metrics endpoint (`/metrics`)
  - HTTP, RTMP, live stream, recording and encoding metrics

- ⚙️ **Configuration**
  - Fully configurable through YAML
  - Customizable stream endpoints
  - Flexible quality profiles
  - Adjustable storage paths
  - Domain name customization
  - Global streams playlist m3u8 + built-in browser player

## Getting Started

1. Clone the repository:
```bash
git clone <repository-url>   # not published yet
cd theatrum_java
```

2. Configure your server:
   - Copy `config.yml.example` to `config.yml` (it is gitignored — it holds `live_stream_key` secrets)
   - Or start from a use-case-specific example in [`examples/`](examples/):
     - [`youtube-like.yml`](examples/youtube-like.yml) — User-uploaded VOD platform
     - [`netflix-like.yml`](examples/netflix-like.yml) — Premium pre-encoded VOD
     - [`twitch-like.yml`](examples/twitch-like.yml) — Live streaming platform
     - [`iptv-like.yml`](examples/iptv-like.yml) — Linear TV / IPTV distribution
     - [`restream-like.yml`](examples/restream-like.yml) — Relay of upstream feeds
   - Adjust the configuration according to your needs:
     - Set up your quality profiles
     - Configure storage paths
     - Adjust stream templates
     - Set up endpoints

3. Build and run:
```bash
mvn -q package -DskipTests            # → target/theatrum-2.0.jar
java -jar target/theatrum-2.0.jar --config config.yml

# or, during development
mvn spring-boot:run

# or with Docker — config.yml and data/ are mounted, never baked into the image
docker build -t theatrum .
docker run -p 8080:8080 -p 1935:1935 \
  -v "$PWD/config.yml:/config/config.yml:ro" -v "$PWD/data:/app/data" theatrum
```

`--config` (or `-c`) defaults to `config.yml` in the working directory. `--help` and `--version`
are available.

4. Open `http://localhost:8080/` — the bundled player lists every stream and plays the one you pick.

## Requirements

- Java >= 25 (Temurin)
- Maven >= 3.9
- FFmpeg >= 4.4.0 (external process, required at runtime and for `mvn verify`)
- Storage space for video segments
- Network bandwidth according to your quality profiles

### FFmpeg Requirements
- libx264 encoder
- aac audio codec
- HLS segmenter
- DASH muxer (for DASH and dual-mode streams)

## Configuration

The server is configured through `config.yml`. YAML anchors and aliases are supported. Here's a
breakdown of the main configuration sections — the field-by-field reference lives in
[docs/configuration.md](docs/configuration.md).

### Server Configuration
```yaml
application:
  public_path: "http://localhost:8080"  # What the aggregated playlist advertises
  all_streams_playlist:
    enabled: true
    path: "all_streams.m3u8"

server:
  http: 8080   # HTTP port for HLS/DASH streaming, metrics and the frontend
  rtmp: 1935   # RTMP ingest port
  rtmp_config:
    reconnect_delay: 30  # Seconds to wait before cleaning up a disconnected stream
    cleanup_delay: 30    # Seconds to wait before removing stream files
```

> `server.port` must **not** be set in `application.properties` — the HTTP port comes from
> `config.yml`, which is parsed before Spring starts.

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
      delete_after_encoding: false
      qualities:
        low: *LOW
        medium: *MEDIUM
        high: *HIGH
      distribution:
        hls:
          segment_duration: 6
```

Detection runs at **startup only** — drop the source files in before launching, not while running.

#### unencoded_video (DASH)
```yaml
stream_templates:
  video_dash:
    stream: &video_dash_config
      type: video_unencoded
      video_input_path: "raw_videos/{username}"
      path: "records/{username}"
      delete_after_encoding: false
      qualities:
        low: *LOW
        medium: *MEDIUM
        high: *HIGH
      distribution:
        dash:
          segment_duration: 6
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

#### DASH-only live stream
```yaml
stream_templates:
  live_dash:
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "your-secure-rtmp-secret-key"
      auth_token_template: "{username}"
      distribution:
        dash:
          segment_duration: 2
          window_size: 5
```

#### Dual-mode live stream (HLS + DASH)
Both formats are produced from a single FFmpeg process. They share fMP4/CMAF segments (`.m4s`). HLS uses fMP4 instead of `.ts`.

```yaml
stream_templates:
  live_dual:
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
        dash:
          segment_duration: 2  # Must match HLS segment_duration in dual mode
          window_size: 5
```

#### restream
Pulls from an external URL instead of waiting for an RTMP push. Everything downstream — qualities,
distribution, recording, viewers, views — behaves exactly as it does for `live`.

```yaml
channels:
  "/restream/mystream":       # Literal path: no {var} allowed on restream channels
    stream:
      type: restream
      source_url: "rtmp://external-server/live/stream_key"
      path: "restream/mystream"
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
```

Restreams are started for every restream channel at boot and reconnect on failure with exponential
backoff (1s → 30s max, reset after 30s of success). They take no `live_stream_key` /
`auth_token_template` — there is no client to authenticate.

**Output directory structure:**
```
# HLS-only passthrough (no qualities)
data/live/myuser/default/
  playlist.m3u8 + segment_*.ts

# HLS-only multi-quality (with qualities)
data/live/myuser/
  master.m3u8
  low/playlist.m3u8 + segment_*.ts
  medium/playlist.m3u8 + segment_*.ts
  high/playlist.m3u8 + segment_*.ts

# DASH-only (flat layout)
data/live/myuser/
  manifest.mpd
  init-stream*.m4s + chunk-stream*.m4s

# Dual mode (flat layout, both manifests)
data/live/myuser/
  manifest.mpd + master.m3u8
  init-stream*.m4s + chunk-stream*.m4s
```

For live streams, authentication is required using configurable XOR-based tokens.

**Required fields for live streams:**
- `live_stream_key` - Secret key used for XOR operation
- `auth_token_template` - Template specifying which URL variables to use for authentication

**How authentication works:**

1. Server extracts variables from the RTMP URL based on the channel pattern
2. Server builds the XOR input by replacing `{var}` placeholders in `auth_token_template` with URL values
3. Server XORs the input with `live_stream_key` and hex-encodes the result (lowercase)
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

Computing a token by hand:
```bash
python3 -c "
key=b'secret'; inp=bytearray(b'alice')
for i in range(len(inp)): inp[i] ^= key[i % len(key)]
print(inp.hex())
"
```

> **Note:** All variables in `auth_token_template` must exist in the channel pattern, otherwise configuration validation will fail at startup.
>
> XOR is obfuscation, not encryption: anyone holding the key can mint tokens, and there is no
> replay protection. Adequate against accidental publishing, not against an attacker. See
> [docs/ingest.md](docs/ingest.md).

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

At least one distribution format (`hls` or `dash`) must be configured. Both can be enabled simultaneously for dual-mode output.

```yaml
# HLS-only
distribution:
  hls:
    segment_duration: 6
    window_size: 5       # Live streams only (default: 3)

# DASH-only
distribution:
  dash:
    segment_duration: 6
    window_size: 5       # Live streams only (default: 3)

# Dual mode (both HLS and DASH)
distribution:
  hls:
    segment_duration: 2
    window_size: 5
  dash:
    segment_duration: 2  # Must match HLS segment_duration
    window_size: 5
```

In dual mode, `segment_duration` must be identical between `hls` and `dash` (enforced at startup).

### Viewer & View Counting

Theatrum can track concurrent viewers and total views per stream by monitoring `.ts` and `.m4s` segment requests from unique client IPs. Both use a **delayed window** — they only count after a client has been watching continuously for `window` seconds.

- **`viewers.txt`** (live streams only): Returns the number of concurrent viewers. A viewer only appears in the count after watching for at least `window` seconds. If they stop requesting segments for `window` seconds, their session resets.
- **`views.txt`** (all stream types): Returns the total number of viewing sessions. A view is only counted once a client has watched continuously for `window` seconds. A new session starts after `window` seconds of inactivity.

Setting `window: 0` counts immediately on first request (no delay).

Both files are served alongside `master.m3u8` / `manifest.mpd` at the stream's base URL (e.g., `http://localhost:8080/live/username/viewers.txt`).

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

When disabled (default), requesting `viewers.txt` or `views.txt` returns 404. Client IP is extracted from the `X-Forwarded-For` header (for reverse proxy setups) or the socket address.

View counts are persisted to disk and survive server restarts. When recording is enabled, `views.txt` is preserved alongside the recording. When recording is disabled, all files (including `views.txt`) are deleted on stream end.

### Thumbnails (live streams only)

A second FFmpeg process grabs one frame from the latest segment every `interval` seconds and writes
it atomically to `thumbnail.png` at the stream's base URL.

```yaml
      thumbnail:
        enabled: true
        interval: 5   # Seconds between captures
```

When disabled (default), `thumbnail.png` returns 404. Enabling it on a non-live stream type is
rejected at startup. The thumbnail is moved with the rest of the files when recording.

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

Built-in functions can be mixed freely with user variables in any path template. For a live stream,
builtins are frozen for the whole session, so the RTMP and HTTP sides resolve them identically and
they survive reconnects.

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

## Global Playlist & Player

- `GET /all_streams.m3u8` (path and toggle come from `application.all_streams_playlist`) lists every
  configured stream, with absolute URLs built from `application.public_path`.
- Any request matching no channel falls through to the frontend: `frontend/index.html`, served
  relative to the working directory, lists what `all_streams.m3u8` advertises grouped by channel
  prefix and plays the picked stream with hls.js, including a quality selector bound to the master
  playlist's variants.

## Monitoring

Theatrum exposes Prometheus metrics at `GET /metrics` on the HTTP port. All metrics are prefixed
with `theatrum_`; only Theatrum's own metrics are exported (no JVM binders are registered).

### Metrics Reference

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `theatrum_http_requests_total` | Counter | `status_code`, `stream_type`, `file_type` | Total HTTP requests served |
| `theatrum_http_request_duration_seconds` | Histogram | `stream_type`, `file_type` | HTTP request duration |
| `theatrum_http_response_bytes_total` | Counter | `stream_type`, `file_type` | Total bytes sent in HTTP responses |
| `theatrum_http_requests_in_flight` | Gauge | — | HTTP requests currently being served |
| `theatrum_rtmp_connections_total` | Counter | — | Total RTMP connections |
| `theatrum_rtmp_connections_active` | Gauge | — | Currently active RTMP connections |
| `theatrum_rtmp_auth_total` | Counter | `result` | RTMP authentication attempts (success/failure) |
| `theatrum_rtmp_received_bytes_total` | Counter | `channel`, `type`, `stream_path` | Bytes received from RTMP streams |
| `theatrum_rtmp_received_frames_total` | Counter | `channel`, `type`, `stream_path` | Frames received from RTMP streams |
| `theatrum_live_streams_active` | Gauge | — | Currently active live streams |
| `theatrum_stream_duration_seconds` | Histogram | `stream_path` | Duration of live streams |
| `theatrum_ffmpeg_exits_total` | Counter | `status`, `stream_path` | FFmpeg process exits (clean/error/killed) |
| `theatrum_recordings_total` | Counter | `mode`, `status`, `stream_path` | Recording operations (move/in_place, success/failure) |
| `theatrum_encode_queue_depth` | Gauge | — | Jobs in the encode queue |
| `theatrum_encode_jobs_total` | Counter | `status` | Encode jobs processed (success/failure) |
| `theatrum_encode_job_duration_seconds` | Histogram | — | Duration of encode jobs |
| `theatrum_channels_configured` | Gauge | `type` | Configured channels by stream type |

> **Difference from the Go version:** the per-stream `theatrum_stream_viewers` /
> `theatrum_stream_views` gauges are not exported yet. Those counts are served over `viewers.txt` /
> `views.txt` instead.

### Prometheus Scrape Config

```yaml
scrape_configs:
  - job_name: "theatrum"
    static_configs:
      - targets: ["localhost:8080"]
```

## Architecture

Hexagonal (ports & adapters), three rings — `domain` → `application` → `infrastructure`, with the
dependency rules enforced by ArchUnit in `mvn test`. Driving ports are `*UseCase`, driven ports are
`*Port`. Full map in [docs/architecture.md](docs/architecture.md).

Stack: Java 25 · Spring Boot 4.1 · Maven · Jackson (YAML) · MapStruct · Micrometer/Prometheus ·
picocli · Lombok · JUnit 5 / AssertJ / Mockito · ArchUnit. RTMP is implemented in-tree; FFmpeg is
an external process.

## Development

Every change is test-first — red, green, refactor. Conventions and the harness live in
[docs/testing.md](docs/testing.md).

```bash
mvn test                      # unit tests (e2e excluded)
mvn verify                    # + e2e (needs ffmpeg on PATH)
mvn -q package -DskipTests    # → target/theatrum-2.0.jar
mvn spring-boot:run
```

E2E tests **skip** rather than fail when FFmpeg is missing — a CI job without FFmpeg passes while
testing nothing.

| Doc | Covers |
|-----|--------|
| [docs/architecture.md](docs/architecture.md) | Hexagonal rings, ports, wiring, boot order, dependency rules |
| [docs/ingest.md](docs/ingest.md) | RTMP protocol implementation, XOR auth, restream |
| [docs/streaming.md](docs/streaming.md) | HLS/DASH/dual, FFmpeg commands, recording, path templates, viewers, thumbnails |
| [docs/configuration.md](docs/configuration.md) | `config.yml` reference, validation rules, the `examples/` configs |
| [docs/testing.md](docs/testing.md) | Test conventions, reference tests, manual pipeline run |
| [docs/operations.md](docs/operations.md) | Metrics, build facts, CI/CD |

## License

This project is licensed under the GNU Affero General Public License v3.0, with commercial use
limited to organizations generating under $100,000/year in revenue — see the [LICENSE](LICENSE)
file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

## Support

For support, please open an issue in the GitHub repository or contact the maintainers.
