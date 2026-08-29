# Configuration

Copy `config.yml.example` to `config.yml` (gitignored — it holds `live_stream_key` secrets), or
pass any file with `--config`. The YAML schema is identical to the Go version, anchors/aliases
included.

Parsing lives in `adapter/out/config/`: Jackson deserialises into `entities/*Yaml`,
`YamlConfigFile` validates, `ConfigMapper` (MapStruct) maps onto the domain models. Nulls are
parsed with `Nulls.SKIP`, so an empty YAML block keeps the wire type's field initializer instead
of overwriting it with `null`.

```bash
mvn test -Dtest=ShippedConfigsTest   # config.yml.example + every examples/*.yml
mvn test -Dtest=YamlConfigFileTest
```

## Channels

```yaml
server:
  http: 8080
  rtmp: 1935

channels:
  # Passthrough live (codec copy, no transcoding, lowest latency)
  "/user/{username}":
    stream:
      type: live
      path: "livestreams/{username}"
      live_stream_key: "your-secret-key"
      auth_token_template: "{username}"  # REQUIRED for live streams
      distribution:
        hls:
          segment_duration: 2
          window_size: 3     # segments in the live playlist (default: 3)

  # Multi-quality transcoding (adaptive bitrate, costs CPU)
  "/premium/{username}":
    stream:
      type: live
      path: "livestreams/{username}"
      live_stream_key: "your-secret-key"
      auth_token_template: "{username}"
      qualities:           # omit for passthrough
        low: ...
        medium: ...
        high: ...
      distribution:
        hls: { segment_duration: 2, window_size: 5 }

  # Recording, viewer/view tracking, thumbnails
  "/tracked/{username}":
    stream:
      type: live
      path: "live/{username}/{%STARTING_DATE%}"
      live_stream_key: "your-secret-key"
      auth_token_template: "{username}"
      distribution:
        hls: { segment_duration: 2, window_size: 5 }
      record:
        enabled: true
        path: "recordings/{username}/{%STARTING_DATE%}"
      viewers: { enabled: true, window: 30 }   # concurrent, live only
      views:   { enabled: true, window: 30 }   # total, all stream types
      thumbnail: { enabled: true, interval: 5 } # live only

  # Dual mode: HLS + DASH from one FFmpeg process
  "/dual/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "your-secret-key"
      auth_token_template: "{username}"
      distribution:
        hls:  { segment_duration: 2, window_size: 5 }
        dash: { segment_duration: 2, window_size: 5 }  # duration must match HLS

  # Restream: pull from an external URL
  "/restream/mystream":
    stream:
      type: restream
      source_url: "rtmp://external-server/live/stream_key"
      path: "restream/mystream"
      distribution:
        hls: { segment_duration: 2, window_size: 5 }
```

Defaults that are a value rather than an empty block — `reconnect_delay` / `cleanup_delay` at 30s,
`window_size` at 3 — live in `domain/constant/ConfigConstants.java`.

## Validation rules (`YamlConfigFile`)

- Live streams require `live_stream_key` and `auth_token_template`
- Restreams require `source_url` and must not set `live_stream_key`, `auth_token_template`,
  `video_input_path` or `delete_after_encoding`
- Restream channel keys, `path` and `record.path` must not contain `{var}` placeholders
- In dual mode, `segment_duration` must match between `hls` and `dash`
- `thumbnail.enabled` is rejected on non-live stream types

## Ready-made configs

`examples/` holds five complete configs to copy from. Nothing loads them automatically.

| File | Shape | Stream types | Delivery |
|------|-------|--------------|----------|
| `twitch-like.yml` | Live platform, two tiers | `live` | dual (premium) / HLS (regular) |
| `youtube-like.yml` | User uploads, auto-encoded | `video_unencoded`, `video_encoded` | dual, 6s |
| `netflix-like.yml` | Pre-encoded VOD, 4K ladder | `video_encoded` | dual, 10s |
| `iptv-like.yml` | Linear TV / radio, passthrough | `live` | DASH (TV) / HLS (radio) |
| `restream-like.yml` | Relay of upstream feeds | `restream` | HLS / dual |

Before exposing one: replace `change-me-to-a-secure-key`, set `application.public_path` to the
address viewers actually reach, and check the ports.
