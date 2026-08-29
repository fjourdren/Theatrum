---
name: ci-cd
description: Manage and extend the CI/CD pipeline (GitHub Actions, Docker, build scripts)
---

# CI/CD Pipeline Manager

Build or fix Theatrum's CI/CD. `$ARGUMENTS` says what (e.g. `/ci-cd add the workflow`,
`/ci-cd add a Dockerfile`, `/ci-cd fix failing workflow`).

## Read first

`docs/operations.md` § Build and § CI/CD — the build facts any pipeline must respect (Java 25,
FFmpeg required for `mvn verify`, jar name, ports, gitignored `config.yml`).

## Check the current state

```bash
ls -d .github/workflows Dockerfile scripts 2>/dev/null || echo "no CI/CD yet"
```

`ci.yml` (build + release), `e2e.yml` and the multi-stage `Dockerfile` are in place — see
`docs/operations.md` § CI/CD and § Docker for what each does, why there is no separate test job,
and why the image must run from `/app`. Still missing: `scripts/`, and nothing builds the image in
CI. The Go original (`../theatrum`) has `scripts/local-build.sh` — read it before writing the Java
equivalent. **Add the piece that was asked for, not all of it
speculatively.**

## Verify after any change

1. YAML parses: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"`
2. The image builds: `docker build -t theatrum:test .`
3. The image runs: `docker run --rm -v "$PWD/config.yml:/config/config.yml" theatrum:test --config /config/config.yml`
4. New jobs have the right `needs:` and `if:` guards
5. Secrets and `permissions:` are scoped to the job that needs them
6. The E2E job really has FFmpeg — check the log for skipped tests, not just a green tick
