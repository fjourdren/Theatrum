---
name: update-docs
description: Update CLAUDE.md and docs/ to reflect code changes
---

# Documentation Updater

Bring the docs back in line with the code. `$ARGUMENTS` describes the change (e.g.
`/update-docs added restream recording`).

## Step 1: Find what changed

```bash
git diff && git diff --cached && git status --short && git log --oneline -5
```

If `$ARGUMENTS` describes the change, use it as the guide and read the affected source for
detail.

## Step 2: Update the doc that owns the subject

`CLAUDE.md` is deliberately short — the entry point, stack, rules-in-one-box, commands,
conventions, docs index. **Resist growing it.** Detail goes in `docs/`.

| Changed | Update |
|---------|--------|
| New package, port, bean, boot step, dependency rule | `docs/architecture.md` |
| RTMP protocol/handler, auth, restream behaviour | `docs/ingest.md` |
| FFmpeg args, output mode, recording, path templates, viewers, thumbnails, HTTP handlers | `docs/streaming.md` |
| New YAML key, changed default, new validation rule | `docs/configuration.md` |
| Test conventions, harness, new reference test | `docs/testing.md` |
| New metric, build fact, CI/CD | `docs/operations.md` |
| New stack element, convention, or command | `CLAUDE.md` |

Rules:

- Update only the affected sections; leave the rest alone
- Copy real signatures, do not paraphrase them
- Keep tables current — ports, metrics, distribution modes, RTMP components
- Terse and factual; no marketing
- Verify every claim: each path exists, each signature matches, each metric name appears in
  `Metrics.java`

## Step 3: Update the skills

A moved or renamed class breaks the "read first" file lists in `.claude/skills/`. Skills hold
*procedure*; docs hold app knowledge — if you are about to paste an explanation into a skill, it
belongs in `docs/` with a pointer from the skill.

```bash
# every path the docs and skills claim exists
grep -ohE "src/(main|test)/java/[^ \`)]*\.java" CLAUDE.md docs/*.md .claude/skills/*/SKILL.md | \
    sort -u | while read -r f; do [ -e "$f" ] || echo "MISSING: $f"; done

# every docs/ link resolves
grep -oh "docs/[a-z-]*\.md" CLAUDE.md docs/*.md .claude/skills/*/SKILL.md | sort -u | \
    while read -r f; do [ -e "$f" ] || echo "MISSING: $f"; done
```

## Step 4: Verify

1. Both greps above are silent
2. YAML examples parse: `python3 -c "import yaml; yaml.safe_load(open('config.yml.example'))"`
3. Config still loads: `mvn test -Dtest=YamlConfigFileTest`
4. Metric names match `adapter/out/metrics/Metrics.java`
5. Maven commands in the docs actually work
6. No stale references to removed features or renamed classes
