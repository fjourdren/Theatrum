# Theatrum (Java)

Streaming server: VOD, live RTMP ingest, restreaming from external URLs, delivered over HLS
and/or MPEG-DASH with optional multi-quality adaptive bitrate.

Java port of the Go original (`../theatrum`). Behaviour, config format and HTTP/RTMP surface are
intentionally identical; only the internals differ.

## Workflow — TDD only

Every change is test-first. Red → green → refactor:

1. Write the failing test that describes the wanted behaviour
2. Run it — watch it fail, and fail for the *right reason*
3. Write the minimum code to make it pass
4. Refactor with the test green

No production code lands without a test that demanded it. No "I'll add tests after". If a change
seems untestable, that is a design signal — fix the seam, do not skip the test.

Conventions, reference tests and the harness: [docs/testing.md](docs/testing.md).

## Stack — use these, add nothing else without asking

Java 25 · Spring Boot 4.1 · Maven · Jackson (YAML) · MapStruct · Micrometer/Prometheus · picocli
· Lombok (compile-time only) · JUnit 5 + AssertJ + Mockito via `spring-boot-starter-test` ·
ArchUnit (test-only, the hexagonal rules).

Both Lombok and MapStruct are annotation processors, declared in that order in the compiler
plugin's `annotationProcessorPaths` — MapStruct has to see the getters Lombok generates. Those
two paths are the whole list; no binding artefact.

RTMP is implemented in-tree — no library. FFmpeg is an external process, required at runtime and
for `mvn verify`.

## Architecture in one box

Hexagonal, three rings:

```
domain          → JDK + application.port + Spring stereotypes   (no Jackson/MapStruct, ever)
application     → JDK + domain only
infrastructure  → ports, never a concrete domain service
```

Driving ports are `application/port/in/*UseCase` (implemented by domain services). Driven ports
are `application/port/out/*Port` (implemented by infrastructure adapters). Domain services carry
`@Component` and wire themselves; `BeanConfig` holds only what component scanning cannot reach.

Details, port tables, boot order and the ArchUnit rules: **[docs/architecture.md](docs/architecture.md)**.

## Commands

```bash
mvn -q package -DskipTests    # → target/theatrum-2.0.jar
mvn test                      # unit tests (e2e excluded)
mvn verify                    # + e2e (needs ffmpeg on PATH)
mvn spring-boot:run
java -jar target/theatrum-2.0.jar --config config.yml
```

## Conventions

- **Packages**: lowercase, singular (`domain.model`, not `models`)
- **Classes**: `PascalCase`, one public type per file
- **Ports**: driving `*UseCase`, driven `*Port`
- **Models**: Java `record`s where the value is immutable
- **Config wire types**: suffixed `Yaml`, confined to `adapter/out/config/entities/` — the only
  place `@Getter`/`@Setter` are allowed
- **Mapping**: wire ↔ domain conversion is MapStruct's job, never a hand-written converter. A
  mapper is an `@Mapper` interface named `*Mapper`, reached through its `INSTANCE`. Zero values for
  blocks left empty belong on the wire type as a field initializer (`Nulls.SKIP` keeps it), not in
  the mapper and not in the calling adapter; `@Mapping(expression = …)` is for a default that is a
  *value* rather than an empty block. Only a conversion MapStruct cannot express
  (`StreamType.fromValue`) earns a `default` method.
- **Lombok**: `@RequiredArgsConstructor` (field order = parameter order), `@Slf4j`,
  `@UtilityClass`, `@StandardException`. No `@Data`, no `@Builder`. Non-trivial or overloaded
  constructors are written by hand.
- **Tests**: `{ClassName}Test.java` mirroring the main package; E2E in `com.fjourdren.theatrum.e2e`
- **Javadoc**: explains *why*, not *what* — especially where the Java port deviates from Go

## Docs

| Doc | Covers |
|-----|--------|
| [docs/architecture.md](docs/architecture.md) | Hexagonal rings, ports, wiring, boot order, dependency rules |
| [docs/ingest.md](docs/ingest.md) | RTMP protocol implementation, XOR auth, restream |
| [docs/streaming.md](docs/streaming.md) | HLS/DASH/dual, FFmpeg commands, recording, path templates, viewers, thumbnails, HTTP delivery |
| [docs/configuration.md](docs/configuration.md) | `config.yml` reference, validation rules, the `examples/` configs |
| [docs/testing.md](docs/testing.md) | Test conventions, reference tests, manual pipeline run |
| [docs/operations.md](docs/operations.md) | Metrics, build facts, CI/CD |

**Keep them current.** When you change code, update the doc that covers it in the same change —
`docs/`, `docs/configuration.md` for new config keys, and the `.claude/skills/` that name the files
you moved. Docs that lie are worse than no docs. `/update-docs` walks the checklist.
