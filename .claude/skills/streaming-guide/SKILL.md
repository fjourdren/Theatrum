---
name: streaming-guide
description: Expert reference on streaming protocols (HLS, DASH, RTMP, adaptive bitrate) grounded in Theatrum's implementation
allowed-tools: Read, Grep, Glob, Bash, WebSearch, WebFetch
---

# Streaming Techniques Reference

Answer streaming questions — protocols, encoding, adaptive bitrate — grounded in Theatrum's
implementation, not in general knowledge.

## Read first

- `docs/streaming.md` — distribution modes, HLS/DASH specifics, ABR, output layouts, recording,
  path templates, viewer tracking, thumbnails, HTTP delivery
- `docs/ingest.md` — how frames arrive (RTMP push, restream pull)
- `docs/configuration.md` — the configuration reference

Then the source that answers the question:

- `infrastructure/ffmpeg/FfmpegCommand.java` — `hlsCommand`, `dashCommand`, `hlsFlags`,
  `dashExtraWindowSize`
- `infrastructure/ffmpeg/FfmpegArgs.java` — `addFilter`, `addVideoCodec`, `addVideoCodecLive`,
  `addAudioCodec`, `buildVarStreamMap`
- `infrastructure/ffmpeg/OutputMode.java` / `HlsPlaylist.java`
- `adapter/in/rtmp/management/StreamProcess.java` — live process lifecycle, cleanup, recording
- `adapter/in/rtmp/management/VodPlaylist.java` — VOD playlist on stream end
- `adapter/in/web/handlers/StreamRequestHandler.java` — serving, cache headers, content types
- `adapter/in/rtmp/flv/FlvMuxer.java` — FLV tags
- `domain/constant/VideoConstants.java` — extensions, segment/playlist naming, segment globs
- `adapter/in/web/HttpConstants.java` — content types, cache policies, header names

## How to answer

1. Read the source first — quote the actual command builders, do not paraphrase
2. Explain the *why* (passthrough vs multi-quality, `.ts` vs `.m4s`) and the trade-off
3. Cover HLS, DASH and dual honestly when the question spans them
4. For a real command line without running a stream, read `FfmpegCommandTest` — it asserts on the
   exact argument list for every mode
