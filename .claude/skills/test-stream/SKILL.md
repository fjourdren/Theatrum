---
name: test-stream
description: Boot the app, stream via RTMP, watch via HLS/DASH, and validate the full pipeline
disable-model-invocation: true
---

# Full Pipeline Manual Test

Build, start, publish over RTMP, validate the output, check metrics, clean up. `$ARGUMENTS`
customises the scenario (e.g. `/test-stream passthrough`, `/test-stream with-recording`).

**Run `mvn verify` first.** The automated equivalent already exists — `RtmpPassthroughTest`,
`RtmpMultiQualityTest`, `DashStreamingTest`, `RecordingTest`, `ViewerTrackingTest` and friends
run against a real FFmpeg on temp ports. This skill is for watching the pipeline by hand or
debugging something the suite does not cover.

## The procedure

`docs/testing.md` § Manual pipeline run has the commands end to end, and the troubleshooting
table. `docs/streaming.md` § Output directory structure tells you which URLs to expect for the
mode under test. `docs/ingest.md` § Computing a token has the token snippet.

## Before starting

```bash
ffmpeg -version | head -1
java -version 2>&1 | head -1     # needs Java 25
mvn -version | head -1
```

Stop and tell the user if anything is missing.

## Rules for this run

- Write a scratch config and pass `--config`; never overwrite the user's `config.yml`
- Run from a scratch directory — the app writes segments to `data/` relative to the working
  directory (`AppPaths.defaults()`)
- Wait on a condition (poll `/metrics`, poll for segments), never a blind `sleep`
- Track the PIDs you start and kill them at the end; allow `cleanup_delay` to elapse first
- Report what actually happened, including empty playlists or non-200s — a green run that
  validated nothing is worse than a red one
