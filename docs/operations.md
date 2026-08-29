# Operations: metrics and build

## Metrics

`GET /metrics` serves the Micrometer `PrometheusMeterRegistry` scrape (`MetricsController`).
Everything is prefixed `theatrum_`; stream-lifecycle metrics carry a `stream_path` label with the
resolved tracking key.

| Metric | Type |
|--------|------|
| `theatrum_http_requests_total`, `theatrum_http_requests` | counter |
| `theatrum_http_request_duration`, `theatrum_http_response_bytes` | histogram/summary |
| `theatrum_http_requests_in_flight` | gauge |
| `theatrum_rtmp_connections`, `theatrum_rtmp_auth` | counter |
| `theatrum_rtmp_connections_active`, `theatrum_live_streams_active` | gauge |
| `theatrum_rtmp_received_bytes`, `theatrum_rtmp_received_frames` | counter |
| `theatrum_stream_duration`, `theatrum_ffmpeg_exits`, `theatrum_recordings` | histogram/counter |
| `theatrum_encode_queue_depth` | gauge |
| `theatrum_encode_jobs`, `theatrum_encode_job_duration` | counter/histogram |
| `theatrum_channels_configured` | gauge (labelled by stream type) |

Metric *names* live in `adapter/out/metrics/Metrics.java`; label *values* in
`domain/constant/MetricConstants.java`, shared because the vocabulary crosses the hexagon and a
differently spelled label silently splits one time series in two.

Gauges keep a strong reference to their backing atomic — Micrometer only holds a weak one.
`PrometheusMeterRegistry` is declared in `BeanConfig`: without `spring-boot-actuator-autoconfigure`
on the classpath, nothing auto-configures one.

**Known gap vs Go:** per-stream `theatrum_stream_viewers` / `theatrum_stream_views` gauges are not
exported. The counts are served over `viewers.txt` / `views.txt` instead. Adding them means a
`MeterBinder` over `ViewerTracker.getAllStreamStats()`.

## Build

```bash
mvn -q package -DskipTests   # → target/theatrum-2.0.jar (Spring Boot fat jar)
mvn test                     # surefire: everything except **/e2e/**
mvn verify                   # + failsafe: **/e2e/*Test.java, needs ffmpeg on PATH
java -jar target/theatrum-2.0.jar --config config.yml
```

Facts any pipeline must respect:

- **Java 25** (`temurin`).
- **Two annotation processors**, Lombok then MapStruct, and that is the whole
  `annotationProcessorPaths` list — no `lombok-mapstruct-binding`. Order matters: MapStruct has to
  see the getters Lombok generates. A build that skips annotation processing does not link.
- **FFmpeg is required** at runtime and for `mvn verify`. Without it the E2E tests *skip* rather
  than fail, so a CI job with no FFmpeg passes while testing nothing.
- Ports 8080 (HTTP) and 1935 (RTMP); `data/` holds segments and recordings.
- `config.yml` is gitignored — an image must not bake one in; mount it and pass `--config`.

## CI/CD

Two GitHub Actions workflows, ported from the Go original's:

| Workflow | Trigger | Does |
|----------|---------|------|
| `.github/workflows/ci.yml` | every push and PR | `mvn -B package` (compile + surefire + ArchUnit), uploads the fat jar; on `main`, publishes it to the `latest` release |
| `.github/workflows/e2e.yml` | push to `main` | installs FFmpeg, then `mvn -B verify` (~2 min) |

Both run on Temurin 25 with `cache: maven`. `package` already runs the unit tests, so there is no
separate test job — Maven would only run them twice.

E2E skips itself when FFmpeg is missing, which would leave a green job that tested nothing, so
`e2e.yml` runs `ffmpeg -version` as a guard right after installing it. The test video
(`src/test/resources/testdata/test.mp4`) is committed, so nothing generates it in CI —
`generate_test_video.sh` sits beside it for regenerating by hand.

The release job ships one artifact (`theatrum-*.jar`); there is no cross-compilation to port from
the Go pipeline. Still missing versus the Go original: no `Dockerfile`, no `scripts/`.
