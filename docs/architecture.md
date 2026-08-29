# Architecture

Hexagonal (ports & adapters), three rings.

```
domain/         # Ring 1 — business logic. JDK + application.port + Spring stereotypes
application/    # Ring 2 — port/in (*UseCase, driving) and port/out (*Port, driven)
infrastructure/ # Ring 3 — adapters: cli, web, rtmp, restream (in); config, encoder,
                #           persistence, metrics (out). Plus config/ (wiring) and ffmpeg/
```

## Dependency rules

```
domain          → JDK + application.port + Spring stereotypes  (no Jackson/MapStruct, ever)
application     → JDK + domain only
infrastructure  → ports, never a concrete domain service
```

Allowed in the domain: `@Component`, `@Autowired`, `@PostConstruct`/`@PreDestroy`, and Lombok.
Constructor injection only. Forbidden: Jackson, MapStruct, Micrometer, picocli — a wire format
must never become a business rule.

**Enforced by ArchUnit**, `src/test/java/com/fjourdren/theatrum/ArchitectureTest.java`, run by
`mvn test`. A failure names the class and the rule it broke.

```bash
mvn test -Dtest=ArchitectureTest
```

### Documented exceptions

- `infrastructure/config/BeanConfig.java` sits outside `adapter/`, so it may import concrete
  domain services to build the beans component scanning cannot produce.
- `RtmpLifecycle` / `RestreamLifecycle` live beside their adapters, not in `port/in/` — they
  describe how a driving adapter is *started*, which the domain never calls.
- `infrastructure/ffmpeg/` is shared by the live stream process and the VOD encoder, so it sits
  under `infrastructure/` but outside `adapter/` — neither adapter depends on the other.

## Ports

**Driving** (`application/port/in/`, `*UseCase`) — implemented by domain services, called by
driving adapters:

| Port | Implementation | Purpose |
|------|----------------|---------|
| `ResolveChannelUseCase` | `ApplicationService` | Read config: application, server, channels |
| `AuthorizePublishUseCase` | `RtmpAuthService` | RTMP channel matching + XOR token check |
| `PathTemplateUseCase` | `PathTemplateService` | `{var}` / `{%FUNC%}` resolution |
| `ServeStreamUseCase` | `StreamService` | Resolve a stream's on-disk path |
| `TrackViewerUseCase` | `ViewerTracker` | Viewer / view accounting |
| `QueueEncodeUseCase` | `EncodeJobQueue` | Submit background encode jobs |
| `LiveStreamVarsUseCase` | `LiveStreamRegistry` | Session-stable builtin variables |

**Driven** (`application/port/out/`, `*Port`) — implemented by infrastructure adapters:

| Port | Implementation |
|------|----------------|
| `StoragePort` | `FileAccess` |
| `EncoderPort` | `FfmpegEncoder` |
| `ConfigurationPort` | `YamlConfigFile` |
| `EncodeMetricsPort` | `EncodeMetricsAdapter` |

A domain service a driving adapter calls with no `*UseCase` covering it is drift.

## Boot order

```
TheatrumApplication.main()
  1. picocli parses --config (default: config.yml)
  2. new YamlConfigFile().load(path)   ← before Spring: the HTTP port comes from config.yml
  3. SpringApplicationBuilder, with server.port set and the config registered as a singleton
  4. Component scan + BeanConfig
  5. TheatrumRunner: metrics → encode queue → video detection → RTMP server → restreams
  6. @PreDestroy stops them in reverse
```

Spring owns the HTTP server lifecycle; `TheatrumRunner` starts everything *except* HTTP.
**Do not set `server.port` in `application.properties`** — it outranks the value from `config.yml`.

`ViewerTracker` and `RtmpAuthService` each keep a second constructor for tests, so the injectable
one carries `@Autowired`.

## Stream types

`domain/model/StreamType.java`: `video_encoded` (pre-encoded VOD), `video_unencoded` (encoded on
startup), `live` (RTMP push), `restream` (pull from an external URL).
