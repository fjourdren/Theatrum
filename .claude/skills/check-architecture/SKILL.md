---
name: check-architecture
description: Audit the codebase for hexagonal architecture violations (forbidden imports, dependency direction, port conformance)
allowed-tools: Read, Grep, Glob, Bash
---

# Hexagonal Architecture Checker

Audit Theatrum for violations of the hexagonal rules. `$ARGUMENTS` narrows the scope (e.g.
`/check-architecture domain` or `/check-architecture infrastructure/adapter/in/rtmp/`).

## Step 1: Read the rules

`docs/architecture.md` is the source of truth — dependency rules, the documented exceptions
(`BeanConfig`, the adapter lifecycle interfaces, `infrastructure/ffmpeg/`, Spring stereotypes
and Lombok in the domain)
and how they are enforced. Read it before reporting anything as drift.

## Step 2: Run ArchUnit

```bash
mvn test -Dtest=ArchitectureTest
```

`src/test/java/com/fjourdren/theatrum/ArchitectureTest.java` encodes the dependency rules and
the port naming/location rules. A failure names the offending class and the rule it broke — report it, do not weaken the
rule to make it pass.

## Step 3: The checks ArchUnit cannot do

- **Dead contract** — port methods no caller uses, and adapters exposing public methods callers
  use *instead of* the port. `implements` guarantees the methods exist; this is the subtler read.
  ```bash
  grep -rn "implements .*Port\|implements .*UseCase" src/main/java/com/fjourdren/theatrum/
  ```
- **Missing use case** — a domain service a driving adapter needs but no `*UseCase` covers.
  Compare `domain/service/` against the port table in `docs/architecture.md`.
- **Compile** — `mvn -q compile`. ArchUnit reads bytecode, so it needs a successful build first.

## Report format

```
## Architecture Audit Report

### CRITICAL   Jackson or MapStruct in the domain, application importing infrastructure,
###            adapter on a concrete service — breaks the hexagon, must fix
- [file:line] what and which rule

### MODERATE   cross-adapter imports, *Yaml leaking, domain services instantiated by hand
- [file:line] what

### LOW        port in an unexpected place, unused port method

### KNOWN      the documented exceptions — track only, by design

### Clean areas

### Recommendations
```

Do not silently "fix" a documented exception. Report it as a decision and ask.
