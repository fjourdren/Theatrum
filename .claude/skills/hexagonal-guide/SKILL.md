---
name: hexagonal-guide
description: Explain Theatrum's hexagonal (ports & adapters) architecture with concrete examples from the codebase
allowed-tools: Read, Grep, Glob, Bash
---

# Hexagonal Architecture Guide

You are an expert on hexagonal (ports & adapters) architecture. Explain Theatrum's implementation by grounding every answer in the actual codebase. Read the relevant source files before answering. Use `$ARGUMENTS` to focus on a specific aspect (e.g., `/hexagonal-guide how do ports work` or `/hexagonal-guide explain the DI container`).

## Source Files to Read

Depending on the question, read the relevant files:

- `src/cmd/main.go` — DI container wiring (uber/dig), full dependency graph
- `src/domain/repositories/storagePort.go` — StoragePort interface
- `src/domain/repositories/encoderPort.go` — EncoderPort interface
- `src/domain/repositories/configurationPort.go` — ConfigurationPort interface
- `src/adapters/driver/ports/http.go` — HttpPort interface (driver port)
- `src/adapters/driver/ports/rtmp.go` — RtmpPort interface (driver port)
- `src/domain/services/*.go` — Domain services (business logic)
- `src/domain/jobs/*.go` — Background job processing
- `src/adapters/driven/*/repositories/*.go` — Driven adapter implementations
- `src/adapters/driver/http/httpServer.go` — HTTP driver adapter
- `src/adapters/driver/rtmp/rtmpServer.go` — RTMP driver adapter

## Architecture Overview

```
                    ┌──────────────────────────────────────┐
                    │           Entry Point                 │
                    │         src/cmd/main.go               │
                    │  (DI container wires everything)      │
                    └────────────┬─────────────────────────┘
                                 │ provides & invokes
                    ┌────────────▼─────────────────────────┐
                    │         DRIVER ADAPTERS               │
                    │       (Inbound / Primary)             │
                    │                                       │
                    │  HTTP Server  ◄── HttpPort interface   │
                    │  RTMP Server  ◄── RtmpPort interface   │
                    │                                       │
                    │  Receive external requests, delegate  │
                    │  to domain services                   │
                    └────────────┬─────────────────────────┘
                                 │ calls
                    ┌────────────▼─────────────────────────┐
                    │           DOMAIN                      │
                    │     (Pure Business Logic)             │
                    │                                       │
                    │  models/       Value objects           │
                    │  services/     Business rules          │
                    │  repositories/ Port interfaces         │
                    │  jobs/         Background processing   │
                    │                                       │
                    │  NEVER imports adapters                │
                    └────────────┬─────────────────────────┘
                                 │ uses (via port interfaces)
                    ┌────────────▼─────────────────────────┐
                    │         DRIVEN ADAPTERS               │
                    │      (Outbound / Secondary)           │
                    │                                       │
                    │  fileAccess     ◄── StoragePort        │
                    │  ffmpegEncoder  ◄── EncoderPort        │
                    │  yamlConfigFile ◄── ConfigurationPort  │
                    │  metrics        (infrastructure)       │
                    │                                       │
                    │  Implement domain port interfaces      │
                    └──────────────────────────────────────┘
```

## Key Concepts

### 1. Ports (Interfaces)

Ports define contracts. The domain declares what it needs; adapters implement it.

**Driven ports** (domain defines, driven adapters implement):
- `domain/repositories/storagePort.go` — File operations (read, write, delete, search)
- `domain/repositories/encoderPort.go` — Video encoding
- `domain/repositories/configurationPort.go` — Configuration loading

**Driver ports** (driver adapters define & implement):
- `adapters/driver/ports/http.go` — HTTP server lifecycle
- `adapters/driver/ports/rtmp.go` — RTMP server lifecycle

### 2. Adapters (Implementations)

**Driven adapters** (outbound — domain calls them via ports):

| Adapter | Implements | Location |
|---------|-----------|----------|
| FileAccess | `StoragePort` | `adapters/driven/fileAccess/repositories/fileAccess.go` |
| FfmpegEncoder | `EncoderPort` | `adapters/driven/ffmpegEncoder/repositories/ffmpegEncoder.go` |
| YamlConfigFile | `ConfigurationPort` | `adapters/driven/yamlConfigFile/repositories/yamlConfigFile.go` |
| Metrics | (infrastructure) | `adapters/driven/metrics/metrics.go` |

**Driver adapters** (inbound — external world calls them):

| Adapter | Implements | Location |
|---------|-----------|----------|
| HttpServer | `HttpPort` | `adapters/driver/http/httpServer.go` |
| RtmpServer | `RtmpPort` | `adapters/driver/rtmp/rtmpServer.go` |

### 3. Dependency Injection (uber/dig)

All wiring happens in `src/cmd/main.go`. The DI container:

1. Creates driven adapters and registers them as port implementations
2. Creates domain services that depend on those ports
3. Creates driver adapters that depend on domain services
4. Starts the application

This ensures the domain never directly instantiates adapters — it only knows about port interfaces.

**Wiring order in main.go:**
```
Metrics → Driven Adapters (as port impls) → Domain Services → Jobs → Driver Adapters → Start
```

### 4. Dependency Direction Rules

```
✅ Domain imports:     stdlib, constants, own packages (models, repositories)
✅ Driven adapters:    domain/models, domain/repositories (to implement ports)
✅ Driver adapters:    domain/services, domain/repositories, driven/metrics
✅ main.go:            everything (it's the composition root)

❌ Domain must NOT import adapters (except known pragmatic violation: jobs → metrics)
❌ Driven adapters must NOT import driver adapters
❌ Adapters must NOT import other adapters at the same level (except metrics as infrastructure)
```

### 5. The Domain Layer

The domain is the core — it contains all business logic and has zero knowledge of infrastructure:

- **models/** — Pure data structures (Stream, Quality, Distribution, etc.)
- **services/** — Business rules (auth, path resolution, viewer tracking, encoding orchestration)
- **repositories/** — Port interfaces (contracts for external capabilities)
- **jobs/** — Background processing (encode queue, unencoded video detection)

### 6. Known Pragmatic Violations

**`domain/jobs/encodeJob.go` imports `adapters/driven/metrics`:**
- The encode job queue uses Prometheus metrics for instrumentation
- This is a common pragmatic trade-off: metrics is cross-cutting infrastructure
- To fix strictly: inject a `MetricsPort` interface into the domain, implement it in the metrics adapter

**Driver adapters import `adapters/driven/metrics`:**
- HTTP and RTMP servers use metrics for request instrumentation
- Acceptable because metrics is shared infrastructure, not business logic

**`rtmp/management/process.go` imports `ffmpegEncoder/ffmpegargs`:**
- Live stream FFmpeg process reuses shared argument-building utilities from the encoder adapter
- Could be extracted to a shared package or domain service if strict separation is needed

## How to Answer

1. Read the relevant source files before answering
2. Use the architecture diagram to orient the user
3. Show concrete code examples (import statements, interface definitions, implementations)
4. Explain the "why" — why ports exist, why dependency direction matters, why DI is used
5. When asked about violations, explain both the pragmatic reason and the strict fix
6. Reference `main.go` to show how components are wired together
