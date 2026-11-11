# Canvas post-apply layer, line 26.3

Upstream pin: Canvas `main` at `a636063e`, build **#1010** (de-facto stable 26.3:
`channel=STABLE`, `mcVersion=26.3`; no `ver/26.3` branch exists upstream).
Pin source is `upstream.canvas.properties` next to this file.

Same model as the former 26.1/26.2 Canvas legs: zero files in the upstream
source patch directory, every Linear hunk applies after upstream apply with a
check gate. The 18 shared patches, 9 paper-server files, and 20 tests come
from `versions/servers/common/canvas/` (see the hub README); this leg keeps
only seam adapters plus the three demoted shadows below.

Build #1010 rebase (2026-11-05): `RegionFile.write(ChunkPos, ByteBuffer)`
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

For behavior and settings see docs/architecture.md and docs/configuration.md.
