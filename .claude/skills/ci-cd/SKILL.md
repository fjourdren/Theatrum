---
name: ci-cd
description: Manage and extend the CI/CD pipeline (GitHub Actions, Docker, build scripts)
---

# CI/CD Pipeline Manager

You help maintain and extend Theatrum's CI/CD infrastructure. Use `$ARGUMENTS` to specify what you need (e.g., `/ci-cd add linting`, `/ci-cd fix failing workflow`, `/ci-cd add coverage`).

## Current Infrastructure

Before making changes, read the current CI/CD files:

- `.github/workflows/ci.yml` — GitHub Actions pipeline (test, build, release)
- `Dockerfile` — Multi-stage Docker build (Go builder + Alpine runtime with FFmpeg)
- `scripts/local-build.sh` — Local test + build script

### GitHub Actions Workflow (ci.yml)

**Triggers:** All pushes and PRs to any branch.

**Jobs:**
1. **test** — `go test ./...` in `src/` with Go 1.24
2. **build** — `CGO_ENABLED=0 go build -o theatrum ./cmd/main.go`
3. **release** — Only on push to `main`, after test+build pass. Cross-compiles for linux/amd64, linux/arm64, windows/amd64. Creates GitHub release via `softprops/action-gh-release@v2` tagged `latest`.

### Dockerfile

Multi-stage:
- **Builder**: `golang:1.24.2`, downloads deps, builds static binary
- **Runtime**: `alpine:latest`, installs `ca-certificates tzdata ffmpeg`, runs as non-root `appuser`, exposes port 8080

### Local Build Script

`scripts/local-build.sh`: Runs tests then builds the binary. Uses `set -euo pipefail`.

## What's Not Yet Configured

These are areas you can help add:

| Feature | Status | How to Add |
|---------|--------|------------|
| **Linting** | Missing | Add `golangci-lint` to CI workflow + `.golangci.yml` config |
| **Code coverage** | Missing | Add `-coverprofile` to test step, upload to Codecov or similar |
| **Docker build in CI** | Missing | Add Docker build+push step (to GHCR or Docker Hub) |
| **docker-compose** | Missing | For local dev with test environment |
| **Security scanning** | Missing | Add Trivy for container image scanning |
| **Changelog** | Missing | Auto-generate from conventional commits |
| **Branch protection** | Missing | Require CI pass before merge to main |
| **Cache optimization** | Partial | Go module cache exists, Docker layer cache could be added |

## Rules When Modifying CI/CD

### GitHub Actions

- Keep Go version consistent across all jobs (currently `1.24`)
- Always use `working-directory: src` for Go commands (source is in `src/`)
- Use `cache-dependency-path: src/go.sum` for Go setup action
- Release job must depend on test + build: `needs: [test, build]`
- Release only on main: `if: github.ref == 'refs/heads/main' && github.event_name == 'push'`
- Use `CGO_ENABLED=0` for static binaries

### Dockerfile

- Keep multi-stage build pattern (builder + runtime)
- Runtime must include `ffmpeg` (required for live streaming)
- Run as non-root user (`appuser`)
- Binary name in Dockerfile is `main`, release binaries are `theatrum-*`
- Port 8080 for HTTP, port 1935 for RTMP (add `EXPOSE 1935` if RTMP is needed)

### Build Script

- Must run from repo root (script uses `cd "$(dirname "$0")/../src"`)
- Always run tests before build
- Use `set -euo pipefail` for strict error handling

## Common Tasks

### Adding Linting

1. Create `.golangci.yml` at repo root with appropriate linters
2. Add a `lint` job to `.github/workflows/ci.yml`:
```yaml
lint:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-go@v5
      with:
        go-version: "1.24"
        cache-dependency-path: src/go.sum
    - uses: golangci/golangci-lint-action@v6
      with:
        working-directory: src
```

### Adding Code Coverage

Add to the test job:
```yaml
- name: Run tests with coverage
  working-directory: src
  run: go test ./... -coverprofile=coverage.out -covermode=atomic

- name: Upload coverage
  uses: codecov/codecov-action@v4
  with:
    files: src/coverage.out
```

### Adding Docker Build to CI

```yaml
docker:
  needs: [test, build]
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: docker/setup-buildx-action@v3
    - uses: docker/build-push-action@v6
      with:
        push: false
        tags: theatrum:${{ github.sha }}
        cache-from: type=gha
        cache-to: type=gha,mode=max
```

## Verification

After any CI/CD change:
1. Check YAML syntax: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"`
2. Verify Dockerfile builds: `docker build -t theatrum:test .`
3. Verify local script: `bash scripts/local-build.sh`
4. If adding a new job, ensure it has the right `needs:` dependencies
5. Check that secrets/permissions are correctly scoped
