---
name: update-docs
description: Update CLAUDE.md and README.md to reflect code changes
---

# Auto-Documentation Updater

You update Theatrum's documentation (`CLAUDE.md` and `README.md`) to accurately reflect the current state of the codebase. Use `$ARGUMENTS` to focus on specific changes if provided (e.g., `/update-docs added recording support`).

## Step 1: Detect Changes

Run these commands to understand what changed:

```bash
git diff
git diff --cached
git log --oneline -5
```

If `$ARGUMENTS` describes specific changes, use that as the primary guide. Also read the affected source files to understand the new behavior in detail.

## Step 2: Read Current Documentation

Read both files:
- `CLAUDE.md` — Developer-facing architectural reference
- `README.md` — User-facing setup and usage guide

## Step 3: Read Affected Source Files

For each changed file, read it to understand:
- New functions, methods, or types
- Changed interfaces or signatures
- New configuration options
- New endpoints or behaviors

## Step 4: Update CLAUDE.md

Rules:
- **Keep the existing structure** — don't reorganize sections unless a new feature warrants a new section
- **Update only affected sections** — don't rewrite unrelated parts
- **Add new sections** for genuinely new features (new adapter, new protocol, new service)
- **Use project-relative file paths** (e.g., `src/domain/services/streamService.go`)
- **Include interface signatures** when documenting ports or services
- **Keep tables updated** (metrics, configuration options, stream types)
- **Match the existing style** — terse, factual, code-focused. No marketing language.
- **Verify accuracy**: every file path mentioned must exist, every interface signature must match the code

### Sections to Check

- Architecture diagram (new packages?)
- RTMP Implementation tables (new components?)
- Data Flow diagram (new steps?)
- Authentication section (new auth methods?)
- Configuration section (new YAML keys?)
- Distribution Modes section (new output modes? HLS/DASH/Dual changes?)
- Recording section (behavior changes?)
- Path Template System (new functions?)
- Viewer & View Tracking (new features?)
- Stream Types (new types?)
- Metrics section (new collectors?)
- Key Dependencies (new deps?)
- Conventions (changed patterns?)

## Step 5: Update README.md

Rules:
- **Keep the user-friendly tone** — README is for users, not developers
- **Update YAML examples** to match `config.yml.example` — read it to verify
- **Update the metrics table** if new metrics were added
- **Keep Getting Started accurate** — build commands, run commands, prerequisites
- **Update API/endpoint documentation** if routes changed

## Step 6: Update Example Configs

Read `config.yml.example` and the example configs in `examples/` (`youtube-like.yml`, `netflix-like.yml`, `twitch-like.yml`, `iptv-like.yml`). If the changes affect configuration structure (new YAML keys, renamed fields, changed defaults, new stream types or distribution modes), update these files to stay consistent:

- **`config.yml.example`** — Must demonstrate all available features. Add new templates/options here first.
- **`examples/*.yml`** — Each example targets a specific use case. Only add new options if they're relevant to that use case (e.g., don't add DASH to a simple HLS-only example unless it makes sense for the scenario).
- Ensure YAML anchors/aliases (`&NAME` / `*NAME`) are used consistently with the rest of the file.
- Verify all examples parse without errors: `python3 -c "import yaml; yaml.safe_load(open('examples/FILE.yml'))"`

## Step 7: Verify

Before finishing, verify:
1. All file paths mentioned in docs still exist (`ls` or `Glob` to check)
2. Interface signatures match actual code
3. YAML examples are valid and match `config.yml.example`
4. Metrics table matches actual Prometheus collectors (check `src/adapters/driven/metrics/`)
5. No stale references to removed features or renamed files
