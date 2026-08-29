# Testing

**Theatrum is TDD-only** — the test comes first, always (CLAUDE.md § Workflow). This doc is the
*how*.

```bash
mvn test                              # surefire: everything except **/e2e/**
mvn verify                            # + failsafe: **/e2e/*Test.java, needs ffmpeg on PATH
mvn test -Dtest=PathTemplateServiceTest
mvn test -Dtest='PathTemplateServiceTest#matchesTemplate'
mvn verify -Dit.test=RtmpAuthTest
```

## Conventions

- **JUnit 5** (`@Nested`, `@TempDir`, `@ParameterizedTest` + `@MethodSource`), **AssertJ** for
  every assertion (never `Assertions.assertEquals`), **Mockito** where it earns its place. All
  three come from `spring-boot-starter-test`; **ArchUnit** is the one extra test dependency. Add
  no others.
- `{ClassName}Test.java` in the mirrored package, package-private class and methods. E2E goes in
  `com.fjourdren.theatrum.e2e` and must end in `Test` so failsafe picks it up.
- Group cases with `@Nested`, name methods after the behaviour, make table-driven cases a
  `@MethodSource` with the case name as the first argument.
- **Fake** when the test needs the port to *behave* (private static class at the bottom of the
  test file). **Mock** when the test is about *interaction*. Not a mock with four
  `thenReturn`s — that is a fake written badly.
- **Never `Thread.sleep()` for logical time.** Time-dependent services take a `Clock` through a
  package-private constructor; use the `MutableClock` pattern from `ViewerTrackerTest`. Real
  external processes get a deadline poll, not a sleep.
- **`@TempDir`** for anything touching the filesystem: `new AppPaths(tempDir, tempDir.resolve("frontend"))`.
- Domain models are records — build them directly, no builders. `LinkedHashMap` for quality maps
  where ordering matters to the assertion.
- With `@RequiredArgsConstructor` the constructor is not in the source: its parameters are the
  `private final` fields, in declaration order.
- A MapStruct mapper is tested through `ConfigMapper.INSTANCE`, never the generated `Impl`.

Cover in this order: happy path → error cases → edge cases → concurrency (a `CountDownLatch` test
for anything with shared mutable state).

## Reference tests

| File | Shows |
|------|-------|
| `domain/service/PathTemplateServiceTest.java` | the most complete example: parameterized, `@Nested`, error cases |
| `domain/service/ViewerTrackerTest.java` | hand-written fakes, `MutableClock`, `@TempDir` |
| `domain/service/EncodeJobQueueTest.java` | Mockito `@Mock` + `ArgumentCaptor`, concurrency |
| `infrastructure/ffmpeg/FfmpegCommandTest.java` | asserting on built command lines |
| `domain/service/DomainWiringTest.java` | `ApplicationContextRunner`, the bean graph |
| `ArchitectureTest.java` | the hexagonal rules as tests |
| `e2e/TestServerSupport.java` | the E2E harness |

E2E tests boot the real Spring context on free ports against a temp directory and skip via
`Assumptions` when FFmpeg is missing. Generate the config through the harness, never by hand.

## Manual pipeline run

`mvn verify` already covers this. Do it by hand only to watch the pipeline live.

```bash
mvn -q package -DskipTests
java -jar target/theatrum-2.0.jar --config /tmp/theatrum_test_config.yml &

for i in $(seq 1 30); do curl -sf -o /dev/null http://localhost:8080/metrics && break; sleep 1; done

TOKEN=$(python3 -c "
key=b'testkey123'; inp=bytearray(b'alice')
for i in range(len(inp)): inp[i] ^= key[i % len(key)]
print(inp.hex())
")

ffmpeg -re -i src/test/resources/testdata/test.mp4 -c copy -f flv "rtmp://localhost/test/alice/$TOKEN" &

curl -s http://localhost:8080/test/alice/default/playlist.m3u8   # HLS passthrough
curl -s http://localhost:8080/test/alice/master.m3u8             # HLS multi-quality
curl -s http://localhost:8080/test/alice/manifest.mpd            # DASH
```

Segments land in `data/` relative to the working directory, so run it from a scratch directory.
`config.yml` is gitignored — do not overwrite it.

Common surprises: viewer counts stay 0 until the `window` elapses (by design); segments 404ing
while the playlist is 200 means `window_size` is too small; the HTTP port comes from `config.yml`,
not `application.properties`.
