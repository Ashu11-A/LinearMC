---
name: linearmc-leg-port
description: Land one change on all three LinearMC legs (Folia patches, CanvasMC overlay, Horizon plugin mixins). Use when editing paperweight patches, Canvas overlay patches/paper-server files, Horizon mixins or plugin code, or when a feature exists on one leg but not another.
---

# Porting a change across legs

Order: **core first** (skill `linearmc-core-dev`) → Folia → Canvas → Horizon → parity contract.

## Folia fork (`versions/servers/26.1.x/`)

- `folia/patches/minecraft-NNNN-*.patch` → `folia-server/minecraft-patches/features/`; `paper-NNNN-*.patch` → `paper-patches/features/`. Same number per feature on every line.
- Patches apply with `git am`: hunk counts must be exact (old = context + `-`, new = context + `+`), a blank context line is a single space, and context must come from the real target file. After a hand edit run `python3 scripts/patch-recount.py <patch>`; prefer editing the LAST patch that touches a line so no later patch's context changes. Validate with `scripts/stage.sh folia` (applyAllPatches) and `scripts/validate-all.sh`.
- Shared fork patch: `26.1.x/common/minecraft-0011-Linear-flush-unlock.patch`.
- Tests: `folia/tests/*.java` run through `LinearNmsTestSuite`, which uses `@SelectPackages` over `net.linear`, `net.minecraft.world.level.chunk.storage` and `net.minecraft.util.worldupdate`. A test outside those packages never runs.

## Canvas fork (`versions/servers/26.3/canvasmc/` + `versions/servers/common/canvas/`)

- Overlay patches `overlay/{nms,paper,config}/<fqcn_with_underscores>.java.patch` apply with plain `git apply` after Canvas `applyAllPatches` (`scripts/stage.sh canvas` does both). The leg folder overrides the hub file of the same name. Some legacy overlay headers are one context line short; `git apply` tolerates that, so keep their counts as they are.
- Plain files live in `paper-server/src/main/java/...`. Track them in `common/canvas/MANIFEST.txt` (sha256), which preflight checks.
- Canvas patches the same `ChunkMap`/`ServerChunkCache`/`RegionShutdownThread`/`MinecraftServer` files. Re-check context after every Canvas build bump.
- Config lives in Canvas world/global config, not `paper-world-defaults.yml`.

## Horizon plugin (`versions/plugins/horizon/1.0.0/`)

- `src/main` holds the mixin side (`mixins/*Mixin.java`, listed in `mixins.linearmc.json`, `defaultRequire: 1`). `src/plugin` holds the Paper plugin side (commands, config, bootstrap).
- No class restrictions in Horizon: Moonrise and threadedregions classes can be mixed. A target that exists only on some hosts (for example Folia `stopPart2`) uses `require = 0`, and Paper-only behaviour checks the host at runtime (`LinearShutdownBarrier.hostHasStopPart2`).
- Every selector, `NEW`/`INVOKE` target, shadow and overwrite is checked per dev bundle by `MixinDescriptorsTest`. Run it on 26.1.2, 26.2 and 26.3 (`-PdevBundle=<pin>`).
- Document each new target in `MIXIN-CONTRACT.md` (purpose plus per-MC status). Audit creation sites with `scripts/audit-mixin-sites.sh <server.jar>`.
- Plugins are disabled before the final world save, and `onDisable` is not a stop barrier.

## Move-together checklist

- [ ] Same grammar/flags/permissions/config keys/defaults on all legs (`docs/configuration.md` table ⇔ Horizon `config.yml` ⇔ fork config).
- [ ] `parity/capabilities.yml` entry updated for every leg; `scripts/parity-check.sh` green.
- [ ] Leg tests added (Folia suite, Canvas tests, Horizon JUnit 5).
- [ ] Docs: `docs/commands.md` / `configuration.md` / `CHANGELOG.md`.
