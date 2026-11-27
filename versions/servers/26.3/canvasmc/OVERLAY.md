# Canvas post-apply layer, line 26.3

Upstream pin: Canvas `main` at `59357cb7`, build **#1047** (de-facto stable 26.3:
`channel=STABLE`, `mcVersion=26.3`; no `ver/26.3` branch exists upstream).
Pin source is `upstream.canvas.properties` next to this file.

This leg follows the model of the former 26.1/26.2 Canvas legs. It adds no
files to the upstream source patch directory, and every Linear hunk is applied
after the upstream apply, behind a check. The 18 shared patches, 9
paper-server files and 20 tests come from `versions/servers/common/canvas/`
(see the hub README). This leg keeps only seam adapters, plus the three demoted
shadows below.

Build #1016 bump (2026-11-14, `31a7f9ac`): Paper upstream update only; the overlay
is expected to apply unchanged (re-verify with `scripts/stage.sh canvas`).

Historical: build #1010 rebase (2026-11-05): `RegionFile.write(ChunkPos, ByteBuffer)`
became `protected`, so the hub patch keeps the public long-key bridge as the
contract entry; `SimpleRegionStorage` dropped the `info.dfuType()[0]`
assignment from its 5-arg constructor; and `RegionFileStorage` moved chunk
recovery to `RegionFileRecovery` and removed `isChunkData`. The 26.3 adapter
now uses `RegionFileRecovery.isChunkStorage(info)` and the new recovery
shape. The full `LinearNmsTestSuite` (140 tests) is green on the pinned tree.

Seam changes vs 26.2 (all verified by `apply --check` + full compile +
`LinearNmsTestSuite` 140/140 on the pinned tree):

- `RegionFileStorage`: upstream narrowed the cache to
  `Long2ObjectLinkedOpenHashMap<Optional<RegionFile>>`, added `Files` /
  `Objects` / `Optional` / `NbtAccounter` imports and an `isChunkData` field.
  The adapter widens to `Optional<AbstractRegionFile>` with the same
  outside-monitor close, flushDirty-first, and parallel fan-out semantics;
  `getRegionFile(pos)` unified upstream so the single-arg accessor and the
  `existingOnly` flag are gone (call sites updated).
- `ChunkMap`: upstream dropped the `overworldDataStorage` ctor parameter and
  the `storageName` field, and added `this.level = level;`. The adapter
  follows (same `+` lines as the hub file, re-anchored).
- `ServerChunkCache`: the `ChunkMap` call site passes `chunkStatusListener`
  where the hub file has `overworldDataStorage`. One context line swapped.
- `MinecraftServer`: `saveAllChunks` restructured upstream (`saveGlobalData`
  moved to the top, `// Paper start - split out global level data` tail).
  The stop-drain `evictAll` hunk follows the loop end; `+` lines verbatim.
- `RegionShutdownThread`, `RegionStorageUpgrader`, `PaperCommands`,
  `GlobalConfiguration`: carried from 26.2 (hunk-count repair + one restored
  context line each where session edits had dropped them; content verified
  against the 26.3 tree).
- `GlobalConfiguration` also gains a `postLoad` hunk (writer keys pushed to
  the core after validation) and the `implementation`/`grid-size` options:
  re-anchor both hunks on a bump.
- `LinearStartupConversion.java`: unchanged from 26.2
  (`Registries.levelStemToLevel` inline mapping); compiles as-is.

Carried divergences from 26.2, re-verified:

- Stop drain stays Linear only.
- Live format reads the Canvas world config. Operators use the Canvas worlds file.
- Startup conversion file plus the pre-plugin server trigger remain.
- Command lines sit after the tick line.

Proven on the pinned tree: full `LinearNmsTestSuite` green, ANVIL boot
(0 `.linear`), convert reboot (`Startup conversion done`, `.mca` gone),
LINEAR boot, plus a 5-minute B_LINEAR load run (297/297 `.b_linear`,
82k chunks, 0 errors, 35 s shutdown drain).

Save barrier (2026-11-25, same behaviour as Folia `minecraft-0019` / `paper-0012`):

- `SaveAllCommand` (new leg overlay): the region part of Canvas' `saveAllAsync`
  is replaced by one tick task per owned region section and a sealed core
  ticket (`net.linear.save.SaveAllBarrier`); the global-data part is kept.
  Canvas' `SaveAllTicket` was consumed as "done" when a region was destroyed
  by a merge or split, so the old path could report success for a region it
  never saved; tick tasks follow their section instead.
- `MinecraftServer`: `!region.world.noSave` on the periodic player save, and
  the save-off lease hook in `setAutoSave`.
- `RegionFileStorage`: the first `flushDirty` of `flush()` is forced (joins
  flushes already running).
- `PlayerDataStorage`, `ServerStatsCounter`, `PlayerAdvancements` (new leg
  overlays): `durable-player-data`.
- `GlobalConfiguration`: `linear.saveOffMaxDuration` (120) and
  `linear.durablePlayerData` (true), published to the core after validation.
- Leg test: `tests/net/linear/LinearSaveBarrierLegTest.java` (staged beside
  the hub tests).

For behavior and settings, see `docs/architecture.md` and `docs/configuration.md`.

Coupled save (2026-11-26, same behaviour as Folia `minecraft-0020` / `paper-0013`):

- Hub `MoonriseRegionFileIO` gains the chunk I/O fences
  (`linear$snapshotInFlight`, `linear$fenceState`); MANIFEST hash updated.
- New leg file `paper-server/.../io/papermc/paper/linear/LinearCoupledSave.java`
  (byte-identical to the Folia `paper-0013` file): the region adapter for
  `net.linear.save.CoupledSave`.
- `MinecraftServer`: region captures replace the periodic player save while
  `linear.coupledSave.enabled` is on. `PlayerList` (new leg overlay): the
  logout save runs as a capture. `CraftPlayer` (new paper overlay):
  `saveData` too, after Canvas's own `ensureTickThread`.
- `PlayerDataStorage`, `ServerStatsCounter`, `PlayerAdvancements`: stage
  inside a capture, read a staged save before the file.
- `SaveAllCommand`: with coupled save on, the region task runs a capture
  instead of the blocking flush save; the ticket's barrier waits for it.
- `RegionShutdownThread`: `CoupledSave.beginShutdown()` before the stop
  drain, `finishShutdown` after the final player save.
- `GlobalConfiguration`: `linear.coupledSave.enabled` (false) and
  `linear.coupledSave.interval` (20), published to the core after validation.
