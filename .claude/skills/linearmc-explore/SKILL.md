---
name: linearmc-explore
description: Map LinearMC code fast. Use when locating where a behaviour lives, finding the equivalent of a symbol on the Folia patches / Canvas overlay / Horizon plugin, understanding the save/open path, or reading upstream Paper/Folia/Canvas/Horizon/DivineMC/Pumpkin sources as reference.
---

# Exploring LinearMC

## Where things live

| Concern | Core (`linear-core/`) | Folia fork | Canvas fork | Horizon plugin |
| --- | --- | --- | --- | --- |
| Region open / name / probe | `format/.../probe/LinearStorageProbe`, `AbstractRegionFileFactory` | `versions/servers/26.1.x/folia/patches/minecraft-0009-*` | `versions/servers/26.3/canvasmc/overlay/nms/*RegionFileStorage*.patch` | `versions/plugins/horizon/1.0.0/src/main/.../mixins/RegionFileStorageMixin.java` |
| Codec (V1/V2/V3/B_LINEAR/Pumpkin) | `codec/` (`LinearRegionFile`, `BufferedLinearRegionFile`, `versions/*`) | — | — | `Horizon*RegionFile` wrap core files |
| Flush / save / stop | `flush/` (`LinearFlushCoordinator`, `flush/FlushReports`) | `minecraft-0017`, `26.1.x/common/minecraft-0011` | overlay `ServerChunkCache`, `RegionShutdownThread` | `mixins/MinecraftServerMixin`, `LinearShutdownBarrier`, heartbeat in `LinearPlugin` |
| Config + sysprops | `config/` (`LinearPolicy`, `LinearFileConfig`, `LinearFormatOverride`) | `paper-0008`, `paper-0010` | overlay `config/*` (Canvas world/global config) | `src/plugin/resources/config.yml`, `LinearFormatPolicy` |
| Conversion | `convert/` (`pipeline/ConversionPipeline`, `routing/ExplicitConvertRouter`) | `paper-0011`, `minecraft-0015` | `26.3/canvasmc/paper-server/.../LinearStartupConversion.java` | `LinearCommand` (convert only) |
| Commands | `command/` (parser, queue, stats views) | `paper-0009` (embedded `CommandLinear`) | `common/canvas/paper-server/.../CommandLinear.java` | `src/plugin/.../LinearCommand.java` |

Capability → leg evidence for every user-facing feature: `parity/capabilities.yml`
(`scripts/parity-check.sh --matrix` prints the table).

## Fast recipes

- Same symbol on every leg: `grep -rn "<symbol>" linear-core versions/servers/26.1.x versions/servers/26.3 versions/servers/common versions/plugins --include=*.java --include=*.patch | grep -v removed/`
- Leg code embedded in a Folia patch: search the `.patch` text; added lines start with `+`.
- Host shape (what a mixin really hits): `unzip` the class from the dev-bundle jar (`versions/plugins/horizon/1.0.0/.gradle/caches/paperweight/taskCache/mappedServerJar.jar`) and `javap -p -c`.
- Upstream sources: `proof/refs/<Project>` (git-ignored shallow clones; refresh with `git -C proof/refs/<P> pull --depth 1`). Paper's chunk system is in `proof/refs/Paper/paper-server/patches/features/0003-Moonrise-optimisation-patches.patch`; Folia's stop path in `proof/refs/Folia/folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch`.
- History and decisions: ai-memory `memory_query` (`decisions/`, `gotchas/`) before guessing why something is the way it is.

## Gotchas already paid for

- Paper/Folia check `Files.exists/isRegularFile` on the computed region name BEFORE the creation site; hooking only the `new RegionFile` site misses reads (ai-memory `gotchas/horizon-read-path-isregularfile`).
- Plugins are disabled before the final world save on every host; stop-time durability needs a post-save hook.
- `versions/servers/removed/` is archive only; `versions/servers/26.3/folia/` has no leg (Folia 26.3 deferred until stable).
