# Streaming: distribution, FFmpeg, delivery

## Distribution modes

| Mode | Config | Muxer | Segments | Output |
|------|--------|-------|----------|--------|
| **HLS-only** | `hls` only | `-f hls` | `.ts` | `master.m3u8` + per-quality `playlist.m3u8` |
| **DASH-only** | `dash` only | `-f dash` | `.m4s` | `manifest.mpd` + init/chunk `.m4s` |
| **Dual** | both | `-f dash -hls_playlist 1` | `.m4s` (CMAF) | `manifest.mpd` + `master.m3u8` |

`OutputMode.determine(Distribution)` picks it; `FfmpegCommand.create(...)` dispatches to
`hlsCommand()` or `dashCommand()`, with the shared argument builders in `FfmpegArgs`. The same
builders serve live (`-f flv -i pipe:0`), restream (`-i <url>`) and VOD — only the input differs.

**To see a real command line, read `FfmpegCommandTest`** — it asserts the exact arguments for
every mode.

## HLS vs DASH

- HLS: `.ts` segments named `segment_%03d.ts`, one directory per quality, `master.m3u8` listing
  them. `#EXT-X-ENDLIST` marks a finished stream; `VodPlaylist` writes it on stream end.
- DASH and dual: **flat** layout, no quality directories — FFmpeg manages representations via
  `$RepresentationID$`. FFmpeg finalises the MPD on clean exit, so no `VodPlaylist` pass.
- `window_size` (default 3) is how many segments appear in the live playlist. Without recording,
  older segments are deleted from disk too (`delete_segments` / `-extra_window_size 0`).
- Live playlists and manifests are served `no-cache`; segments cache longer
  (`StreamRequestHandler`).

## Adaptive bitrate

Omit `qualities` for **passthrough**: `-c copy`, lowest latency, output at
`{path}/default/playlist.m3u8`.

With `qualities`, FFmpeg gets a `split` + `scale` filter graph, per-quality codec settings and a
`var_stream_map`, plus `-preset veryfast -tune zerolatency` for real time. Output is
`{path}/master.m3u8` with one subdirectory per quality (HLS) or a flat tree (DASH/dual).

Live uses 2s segments; VOD uses 6s — latency versus compression efficiency.

Segment and playlist names all derive from the extensions in `domain/constant/VideoConstants.java`,
including the globs the thumbnail generator scans with, so they cannot drift apart.

## Recording

| `record.enabled` | `record.path` | After the stream ends |
|---|---|---|
| `false` (default) | N/A | Files deleted after `cleanup_delay` |
| `true` | set | VOD playlist generated, files moved to `record.path` |
| `true` | omitted | VOD playlist generated in place |

While recording, every segment stays on disk but the playlist still shows only the last
`window_size`. `thumbnail.png` and `views.txt` move with the rest.

## Path templates

- `{var}` — user variables extracted from the URL pattern (`{username}`, `{room_id}`)
- `{%STARTING_DATE%}` — `yyyy-MM-dd_HH-mm-ss`
- `{%UUID%}` — random UUID v4

Builtins resolve first, then user variables; both phases sanitise to alphanumerics, `_`, `-`, `.`.
`LiveStreamRegistry` freezes a live stream's builtins for the whole session, so they resolve
identically on the RTMP and HTTP sides and across reconnects.

## Viewers, views, thumbnails

`ViewerTracker` counts by watching segment requests per client IP. Both counters use a **delayed
window**: a client counts only after watching continuously for `window` seconds, so counts
staying at 0 for the first `window` seconds is the design. Views persist to
`{videoDir}/{trackingKey}/views.txt`; viewer counts are ephemeral. The tracking key is the fully
resolved stream path.

`ThumbnailGenerator` runs a second FFmpeg process every `interval` seconds, grabbing one frame
from the latest segment and writing `{streamRootDir}/thumbnail.png` atomically. Live streams only.

## HTTP delivery

Spring MVC. `StreamController` maps `@RequestMapping("/**")`, matches the channel patterns and
dispatches to the handlers in `adapter/in/web/handlers/`. Alongside `master.m3u8` /
`manifest.mpd` and the segments:

- `viewers.txt` — concurrent viewers (live only), 404 when disabled
- `views.txt` — total views (all stream types), 404 when disabled
- `thumbnail.png` — latest thumbnail (live only), 404 when disabled
- `GET /metrics` — Prometheus scrape

Anything matching no channel falls through to `FrontendHandler`, which serves `frontend/` relative
to the working directory — `frontend/index.html` lists every stream `all_streams.m3u8` advertises,
grouped by channel prefix, and plays the picked one with hls.js through a quality selector bound to
the master playlist's variants.
