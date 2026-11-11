---
name: linearmc-upstream-bump
description: Track and adopt upstream changes - new CanvasMC Jenkins builds, Folia/Paper commits, Horizon releases and dev bundles, new Minecraft versions. Use when asked to update a server base, check whether patches still apply, port to a new MC line, or assess upstream drift.
---

# Upstream bumps

## Pins (single source of truth)

| Upstream | File | Keys |
| --- | --- | --- |
| Folia | `versions/servers/26.1.x/upstream.properties` | `FOLIA_REPO/BRANCH/REF/BUILD`, `MC_VERSION` |
| Canvas | `versions/servers/26.3/upstream.canvas.properties` | `CANVAS_REPO/BRANCH/REF/BUILD`, `MC_VERSION` |
| Horizon (build) | `versions/plugins/horizon/upstream.horizon.properties` | `HORIZON_REF`, `HORIZON_DEV_BUNDLE`, `HORIZON_BUILD`, `HORIZON_MC_RANGE` |
| Horizon (tests) | `versions/plugins/horizon/upstream.horizon-<mc>.properties` | one dev bundle per supported MC |

`release/RELEASE.md` section 2 mirrors the pins. Keep it in sync.

## Drift check (read-only)

```bash
for p in Paper Folia Canvas Horizon; do git -C proof/refs/$p pull -q --depth 1; git -C proof/refs/$p log -1 --format="$p %h %cs %s"; done
grep -h mcVersion proof/refs/*/gradle.properties
```

Compare the result with the pins. Known pressure (re-check, dated 2026-11-10):
- Folia `ver/26.3.x` exists but is alpha. The leg is deferred until stable (decision).
- Horizon `MinecraftVersion` throws on unknown MC ids (`proof/refs/Horizon/core/.../MinecraftVersion.java`), so a new MC needs a Horizon release first.
- Canvas patches the same `ChunkMap`/`ServerChunkCache` as the overlay.

## Bump procedure

1. **Same MC, new upstream build** (for example Canvas #1010 → #10xx):
   1. Update the REF/BUILD pin.
   2. Run `scripts/validate-all.sh` (stages, compiles and tests both forks) or CI.
   3. If the patches apply unchanged, ship a companion release `vX.Y.Z-release.N+1` (skill `linearmc-release`). Otherwise fix the hunks and bump `X.Y.Z`.
2. **New MC line on Folia**: run `scripts/new-version.sh <line> <branch> <ref>`, never copy by hand. Then port the rejected hunks with the same patch numbers.
3. **New MC for Horizon**:
   1. Add `upstream.horizon-<mc>.properties`.
   2. Run `./gradlew clean test -PdevBundle=<pin>`.
   3. Re-verify `MIXIN-CONTRACT.md` with `javap` and update `HORIZON_MC_RANGE`.
4. **After any bump**:
   - Run `scripts/audit-mixin-sites.sh` on the new server jar.
   - Run `scripts/parity-check.sh`.
   - Run the live matrix (skill `linearmc-test`).
