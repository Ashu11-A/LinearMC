# AGENTS.md — LinearMC

Canonical guide for AI agents and contributors. Keep it short; details live in
the linked docs and in `.claude/skills/linearmc-*`.

## What this repo is

LinearMC stores Minecraft regions as compact `.linear` files (zstd). It ships
the same capabilities through three **legs**:

| Leg | Where | Shape |
| --- | --- | --- |
| Folia fork 26.1.2 | `versions/servers/26.1.x/folia/` (+ `26.1.x/common/`) | paperweight patches (`minecraft-*`, `paper-*`) + tests |
| CanvasMC fork 26.3 | `versions/servers/26.3/canvasmc/` (+ `versions/servers/common/canvas/`) | overlay patches + plain `paper-server` files |
| Horizon plugin 1.0.0 | `versions/plugins/horizon/1.0.0/` | mixins (`src/main`) + Paper plugin (`src/plugin`); runs on Paper/Folia/Canvas 26.1–26.3 |

All format, codec, flush, convert, config and command logic lives in
**`linear-core/`** (NMS-free, JUnit 4). Legs are thin adapters. Archived legs:
`versions/servers/removed/` (never re-add without a port).

Upstream reference clones (git-ignored, read-only oracles): `proof/refs/`
(Paper, Folia, Canvas, Horizon, DivineMC, Pumpkin, SectorTool, …).

## Non-negotiable invariants

1. **Leg parity.** A user-facing capability (command, flag, permission, config
   key, sysprop, storage folder, stop/save behaviour, stat) ships on every leg
   in the same change, or carries an approved `exempt` in
   `parity/capabilities.yml`. `scripts/parity-check.sh` must pass.
2. **Anvil stays byte-identical** unless the change is about Anvil. Linear
   behaviour sits behind the format flag.
3. **No data loss paths.** Any read/open path must find an existing region in
   any format (`.mca`, `.linear`, `.b_linear`) before treating it as absent.
   Test with a PRE-EXISTING world in the other format, never only fresh worlds.
4. **Hot path does not allocate per chunk** (pooled buffers / zstd contexts;
   see `docs/performance.md`).
5. **Patches apply with `git am`**: exact hunk counts, real context, a blank
   context line is a single space. See `CONTRIBUTING.md`.
6. **Mixins**: every selector/target is checked per dev bundle by
   `MixinDescriptorsTest`; host-optional injectors use `require = 0` and are
   documented in `MIXIN-CONTRACT.md`.
7. Code identifiers and comments in English. Scripts live in `scripts/`, never
   in `/tmp`; scratch work goes in `proof/` (git-ignored).

## Verify before you claim done

```bash
CORE_LIBS=$PWD/proof/corelibs linear-core/build.sh   # core: NMS gate + all JUnit suites
(cd versions/plugins/horizon/1.0.0 && ./gradlew test -PdevBundle=<pin>)  # pins: versions/plugins/horizon/upstream.horizon-*.properties
scripts/preflight.sh [--compile|--tests]              # inventory, pins, hunks, parity gates, import lint
python3 scripts/lint-java.py [--fix]                  # unused/duplicate imports, redundant qualified names
scripts/parity-check.sh                               # capability contract
scripts/audit-mixin-sites.sh <server.jar>             # every `new RegionFile` site is redirected
scripts/stage.sh <folia|canvas>                       # local fork workspace in proof/fork-<leg>, exactly like CI
scripts/validate-all.sh [--jars]                      # everything above + both forks (compile, Linear suite, jars), one build at a time
python3 scripts/patch-recount.py <file.patch>         # fix hunk counts after hand-editing a patch
```

Fork builds need ~5 GB free RAM (the Vineflower decompile runs with -Xmx4G);
run them one at a time (`validate-all.sh` does) and stop Gradle daemons after.

Live checks on real servers (skill `linearmc-test`):
`scripts/server-smoke.sh` (boot, load, flush) and `scripts/migration-smoke.sh`
(pre-existing `.mca` world → LINEAR, clean-stop durability; Paper, Folia and
Canvas hosts plus both forks).

## Workflow

Research → implement → expert review scored 0–10 with file:line evidence
(skill `linearmc-review`) → iterate to 10 → full test pyramid (skill
`linearmc-test`).

## Skills index (`.claude/skills/`)

| Skill | Use when |
| --- | --- |
| `linearmc-explore` | Locating code, mapping a symbol across legs, reading upstream refs |
| `linearmc-core-dev` | Changing `linear-core` (format, codec, flush, convert, command, config) |
| `linearmc-leg-port` | Landing a change on Folia patches, Canvas overlay and Horizon mixins |
| `linearmc-parity` | Adding/auditing capabilities, editing `parity/capabilities.yml` |
| `linearmc-test` | Choosing and running tests, live server matrix, migration scenarios |
| `linearmc-perf` | Benchmarks, profiling, allocation and compression work |
| `linearmc-upstream-bump` | New Canvas/Folia/Paper/Horizon builds or MC versions |
| `linearmc-release` | Tags, asset names, release notes, release gates |
| `linearmc-review` | Scoring a change before merge |

## Key docs

`docs/architecture.md` (save path ↔ format), `docs/configuration.md`
(settings source of truth), `docs/commands.md`, `docs/limitations.md`,
`docs/linear-format-requirements.md` (normative wire spec),
`release/TAGS.md`, `release/RELEASE.md`, `CONTRIBUTING.md`.
