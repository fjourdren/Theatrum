---
name: hexagonal-guide
description: Explain Theatrum's hexagonal (ports & adapters) architecture with concrete examples from the codebase
allowed-tools: Read, Grep, Glob, Bash
---

# Hexagonal Architecture Guide

Explain Theatrum's architecture, grounded in the actual codebase. `$ARGUMENTS` focuses the
question (e.g. `/hexagonal-guide how do ports work`, `/hexagonal-guide why is the domain free
of Spring`).

## Read first

1. `docs/architecture.md` — rings, port tables, wiring, boot order, the documented exceptions
2. Then the source that answers the specific question:
   - `TheatrumApplication.java` — bootstrap: CLI, config, Spring
   - `infrastructure/config/BeanConfig.java` — beans component scanning cannot produce
   - `infrastructure/config/TheatrumRunner.java` — start/stop sequence
   - `application/port/in/*.java` / `application/port/out/*.java` — the contracts
   - `domain/service/*.java` — business logic
   - `infrastructure/adapter/in/**` and `out/**` — the adapters

## How to answer

1. Read the source before answering — never paraphrase the doc alone
2. Quote real import lists, interface definitions and `BeanConfig` methods
3. Explain the *why* and what it costs, not just the rule
4. When comparing to the Go original (`../theatrum`), name the difference and the reason
5. On enforcement: `ArchitectureTest` (ArchUnit) runs with `mvn test`
