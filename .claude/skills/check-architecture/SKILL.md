---
name: check-architecture
description: Audit the codebase for hexagonal architecture violations (forbidden imports, dependency direction, port conformance)
allowed-tools: Read, Grep, Glob, Bash
---

# Hexagonal Architecture Checker

You are an architecture auditor for Theatrum. Your job is to scan the codebase and report any violations of the hexagonal (ports & adapters) architecture rules. Use `$ARGUMENTS` to focus on a specific area (e.g., `/check-architecture domain` or `/check-architecture src/adapters/driver/rtmp/`).

## Architecture Rules

### Rule 1: Domain Must Not Import Adapters

Files in `src/domain/` must ONLY import:
- Standard library packages
- `Theatrum/constants`
- `Theatrum/domain/*` (own sub-packages)
- Third-party libraries (e.g., `github.com/prometheus/client_golang`)

**Forbidden imports from domain:**
- `Theatrum/adapters/*` — any adapter import is a violation

**Known exception (document but still flag):**
- `src/domain/jobs/encodeJob.go` imports `Theatrum/adapters/driven/metrics` — this is a known pragmatic violation for instrumentation

### Rule 2: Driven Adapters Must Not Import Driver Adapters

Files in `src/adapters/driven/` must NOT import:
- `Theatrum/adapters/driver/*`

Driven adapters may import:
- `Theatrum/domain/models`
- `Theatrum/domain/repositories` (to implement ports)
- `Theatrum/constants`
- Their own internal sub-packages
- Standard library and third-party packages

### Rule 3: Driver Adapters Must Not Import Other Driver Adapters (Cross-Boundary)

Files in one driver adapter (e.g., `src/adapters/driver/http/`) must NOT import another driver adapter (e.g., `src/adapters/driver/rtmp/`).

**Exception:** Imports within the same driver adapter are fine (e.g., `rtmp/handlers/` importing `rtmp/management/`).

### Rule 4: Port Interfaces Must Live in the Right Place

- **Driven ports** (outbound): `src/domain/repositories/` — interfaces that driven adapters implement
- **Driver ports** (inbound): `src/adapters/driver/ports/` — interfaces that driver adapters implement

Check that no port interfaces are defined in adapter implementation files.

### Rule 5: Adapters Must Implement Their Ports

Verify that each driven adapter actually implements its port interface:
- `fileAccess` → `StoragePort` (ReadFile, WriteFile, DeleteFile, ListFiles, GetFileSize, SearchFiles)
- `ffmpegEncoder` → `EncoderPort` (EncodeVideo)
- `yamlConfigFile` → `ConfigurationPort` (Load)

Verify driver adapters implement their ports:
- `httpServer` → `HttpPort` (StartHttpServer, Shutdown, BuildRouter)
- `rtmpServer` → `RtmpPort` (StartRtmpServer, ShutdownRtmpServer, GetActiveStreams)

### Rule 6: DI Container Is the Only Composition Root

Only `src/cmd/main.go` should instantiate adapters and wire dependencies. No adapter should directly instantiate another adapter (use DI instead).

### Rule 7: Models Stay Pure

Files in `src/domain/models/` must not import adapter packages. They should only use:
- Standard library
- `Theatrum/constants`
- Other model files

## Audit Procedure

Run these checks in order:

### Check 1: Domain → Adapter Imports (CRITICAL)

Search for adapter imports in domain code:

```bash
cd src && grep -rn '"Theatrum/adapters' domain/
```

Every match is a violation (except the known `jobs/encodeJob.go` → `metrics` exception).

### Check 2: Driven → Driver Imports (CRITICAL)

```bash
cd src && grep -rn '"Theatrum/adapters/driver' adapters/driven/
```

Every match is a violation.

### Check 3: Cross-Driver Imports (MODERATE)

```bash
# HTTP importing RTMP
cd src && grep -rn '"Theatrum/adapters/driver/rtmp' adapters/driver/http/

# RTMP importing HTTP
cd src && grep -rn '"Theatrum/adapters/driver/http' adapters/driver/rtmp/
```

Every match is a violation.

### Check 4: Model Purity

```bash
cd src && grep -rn '"Theatrum/adapters' domain/models/
```

Every match is a violation.

### Check 5: Port Interface Location

Verify port interfaces exist where expected:

```bash
# Driven ports
ls src/domain/repositories/*Port.go

# Driver ports
ls src/adapters/driver/ports/*.go
```

Search for stray interface definitions in adapter code:

```bash
cd src && grep -rn 'type.*Port interface' adapters/driven/ adapters/driver/http/ adapters/driver/rtmp/
```

Interfaces defined outside the expected locations may indicate architectural drift.

### Check 6: Port Implementation Completeness

For each port, verify the adapter implements all methods. Read the port interface and the adapter, then compare method signatures.

### Check 7: Direct Adapter Instantiation Outside main.go

```bash
# Look for New* calls to adapter constructors outside main.go
cd src && grep -rn 'NewFileAccess\|NewFfmpegEncoder\|NewYamlConfigFile\|NewHttpServer\|NewRtmpServer' --include='*.go' | grep -v 'cmd/main.go' | grep -v '_test.go'
```

Matches outside `main.go` and test files indicate DI bypass.

### Check 8: Shared FFmpeg Args Usage

Check if `ffmpegargs` (a driven adapter internal) is imported outside its own adapter:

```bash
cd src && grep -rn '"Theatrum/adapters/driven/ffmpegEncoder/ffmpegargs"' | grep -v 'adapters/driven/ffmpegEncoder/'
```

Matches indicate cross-adapter dependency (known: `rtmp/management/process.go`).

## Report Format

After running all checks, produce a report:

```
## Architecture Audit Report

### CRITICAL Violations
- [file:line] Description of violation and which rule it breaks

### MODERATE Violations
- [file:line] Description

### KNOWN Exceptions (Documented)
- [file:line] Why this exists and how to fix it strictly

### Clean Areas
- List packages that passed all checks

### Recommendations
- Specific actionable steps to fix violations
```

## Severity Levels

| Severity | Description | Action |
|----------|-------------|--------|
| CRITICAL | Domain importing adapters, driven importing driver | Must fix — breaks hexagonal guarantees |
| MODERATE | Cross-driver imports, cross-adapter utility sharing | Should fix — creates coupling between independent modules |
| LOW | Port interface in unexpected location, missing method | Cosmetic — still functions correctly |
| KNOWN | Documented pragmatic violation (metrics in jobs) | Track — fix if doing a strict refactor |
