# Mixin contract

Every plugin patch selector is traced to a verified server method.
Verify mechanically before release. Any drift fails the gate.

Current proven trace, bytecode verified on the dev bundle:
(the shipped jar builds against 26.1.2 and declares `api-version: "26.1"`;
the same jar is tested on 26.2 and 26.3)

| Plugin patch purpose | Server method it targets | Notes |
| --- | --- | --- |
| Field shadows | `RegionFileStorage.folder`, `REGION_SHIFT` | Widened in `linearmc.at` |
| File name overwrite | `getRegionFileName(II)` | Fallback only: configured extension for call sites not redirected below |
| Existing-first name | `getRegionFileName(II)` INVOKE in `getRegionFile(ChunkPos;Z)` and `moonrise$getRegionFileIfExists(II)` | The host checks `Files.exists` on this name before any open hook; an existing region in another format must keep its name or it is regenerated. Core `LinearStorageProbe.resolveFileName`, then `hostOpening` (records an `.mca` so a live convert never deletes it; a region converted away resolves to its new file); broken-symlink halt here too |
| Open dispatch | `new RegionFile` NEW in the same two methods | Waits for a pending close of the region, dispatches by extension (`.linear`, `.b_linear`, else vanilla); the final path goes through `hostOpening` too |
| Cache counters | `getAndMoveToFirst(J)` INVOKE in the same two methods | `/linearstats` hit/miss (fork `minecraft-0010` parity) |
| Flush-unlock | `RegionFile.close()` INVOKE in the same two methods, deferred to `DeferredRegionCloses`; drained at RETURN of `getRegionFile`, `moonrise$readData/finishRead/finishWrite`, `write` when the monitor is not held | Eviction close never runs under the storage monitor; a reopen first finishes the pending close (fork `minecraft-0011` parity) |
| Close / flush hooks | `close` TAIL, `flush` HEAD | Evict / drain the folder's coordinator; the flush drain is forced, so it joins flushes already running (fork `minecraft-0009` parity) |
| Save drain | `ServerChunkCache.save(Z)` TAIL | `/save-all flush` forces every dirty Linear file (fork `minecraft-0017` parity) |
| Save-all barrier (Paper) | `SaveAllCommand.saveAll`: `MinecraftServer.saveEverything(ZZZ)Z` INVOKE (redirect, `require = 0`) | Paper shape: a flush save runs, the forced barrier runs, players are saved again after their chunks, then the core ticket (chunk I/O fences, staged coupled saves, forced barrier) writes `linear-checkpoint/save-all.done` (fork `minecraft-0019` parity). The wait runs on the main thread, so its budget is 20 s (below the 60 s watchdog). CanvasMC replaces that call, so the site is host-optional; `HorizonSaveBarrierTest` asserts it on every Paper bundle |
| Save-all barrier (region hosts) | `SaveAllCommand.saveAll(Lnet/minecraft/commands/CommandSourceStack;Z)I` HEAD (inject, cancellable, `require = 0`) | Folia/CanvasMC hosts only (`HorizonRegionSaveAll.applies()`): cancels the host body and runs the region-scheduled barrier of the forks (one tick task per owned region section, chunks then that region's players, sealed core ticket, marker; Canvas global/world data first when the host has `autosaveSafeGlobalData`). The region API (`RegionizedServer`, `ThreadedRegionizer`, `saveAllChunksRegionised`) is resolved reflectively; an unresolved API fails the command loudly. Same method on Paper, so `MixinDescriptorsTest` checks the descriptor; live PASS on Folia 26.3 and CanvasMC 26.3 hosts (tests/RESULTS.md round 4) |
| Save-all registration (Folia) | `Commands.<init>(Commands$CommandSelection;CommandBuildContext;Z)V` RETURN (inject, `require = 0`, `CommandsMixin`) | Registers vanilla `SaveAllCommand` when a region host left it out (Folia); a no-op where the `save-all` node exists (Paper, CanvasMC). The Paper Brigadier constructor exists on every host; `MixinDescriptorsTest` + `HorizonSaveBarrierTest` check it per bundle |
| Save-off lease | `MinecraftServer.setAutoSave(Z)Z` RETURN | Arms/disarms the core lease; same method on Paper, Folia and CanvasMC |
| Player autosave gate | `PlayerList.saveAll(I)V` INVOKE in `MinecraftServer.tickServer` (redirect) | Skipped while any level has `noSave` (save-off flips every level); every host's `tickServer` makes this call |
| Player data | `PlayerDataStorage.<init>` RETURN, `Util.safeReplaceFile` INVOKE in `save` (redirect), `load(NameAndId;String)` RETURN | Orphan temp quarantine, durable replace, WARN on `.dat_old` fallback (`durable-player-data`) |
| Stats / advancements | `Files.newBufferedWriter(Path;Charset;[OpenOption)` INVOKE in `ServerStatsCounter.save` and `PlayerAdvancements.save` (redirect) | Core atomic writer when `durable-player-data` is on |
| Coupled save: logout | `PlayerList.save(ServerPlayer)` INVOKE in `remove(ServerPlayer;Component)` (redirect) | With `coupled-save.enabled`, the quit save runs as a capture of the player's region (`HorizonCoupledSave`); direct otherwise (fork `minecraft-0020` parity). Same site on Paper, Folia and CanvasMC |
| Coupled save: `Player#saveData` | `CraftPlayer.saveData()` HEAD (cancellable) | A capture when coupled save is active on the player's own thread; the host method runs otherwise (fork `paper-0013` parity) |
| Coupled save: player data | `PlayerDataStorage.save(Player)` HEAD (cancellable), `load(NameAndId;String)` HEAD (cancellable) | Inside a capture the `.dat` is serialized in memory and staged (the host body up to the serialization is mirrored); a load returns a staged save before the file |
| Coupled save: stats / advancements reads | `Files.isRegularFile(Path;[LinkOption)` and `Files.newBufferedReader(Path;Charset)` INVOKE in `ServerStatsCounter.<init>` and `PlayerAdvancements.load(ServerAdvancementManager)` (redirect) | A staged save is read before the file |
| Coupled save: periodic and stop | the player autosave gate above (`HorizonCoupledSave.periodic`), `MinecraftServer.stopServer` HEAD, final stop barrier | Captures replace the periodic player save while the flag is on; the stop ends captures, the final drain writes what is still staged. The chunk I/O fences read Moonrise's private `RegionDataController.chunkTasks` and `ChunkIOTask` fields reflectively (not a mixin); `HorizonCoupledSaveTest` asserts they resolve on every bundle, and a host where they do not keeps coupled save off with an ERROR |
| Coupled save: departure | `ServerPlayer.preChangeDimension()` HEAD and `ServerPlayer.respawn(Consumer;RespawnReason;Z)V` HEAD (`require = 0`, `ServerPlayerMixin`) | Folia/CanvasMC only (the methods do not exist on Paper): before a player leaves a region, the source region saves its chunks and stages the player (`HorizonCoupledSave.departure`, fork `minecraft-0020` parity). On Paper one capture covers every chunk, so nothing is needed. No Paper dev bundle has these targets, so `MixinDescriptorsTest` cannot check them; live check on Folia/CanvasMC hosts pending |
| Startup conversion | `DedicatedServer.initServer`, before `SparksFly.enableEarlyIfRequested()` (cancellable) | Before plugins load and before any storage opens, like the Canvas overlay; `false` aborts boot |
| Final stop barrier | `onServerExit()` INVOKE in `MinecraftServer.stopServer` (Paper) or `stopPart2` (Folia/Canvas) | After the final save and region I/O flush on every host |
| Upgrader awareness | `RegionStorageUpgrader.getAllChunkPositions`: `listFiles`, `REGEX`, `new RegionFile` | Inert while Paper 26.x stubs `--forceUpgrade` |

Multi-method injectors (both storage methods; the Moonrise exists path only
owns these call sites on 26.1.2) use `require = 1`: one site overall is
enough. `MixinDescriptorsTest` checks, per dev bundle, that every selector,
shadow and overwrite exists and that every required INVOKE/NEW/FIELD target
is really called inside its host method (bytecode).

Per-MC retrace (HORIZON_REF `31d1a1b7` = Horizon stable build #90; build pin in
`versions/plugins/horizon/upstream.horizon.properties`, per-bundle test pins
in `upstream.horizon-<mc>.properties`):

| Selector | 26.1.2 | 26.2 | 26.3 |
| --- | --- | --- | --- |
| Storage redirects in `getRegionFile` | verified | verified | verified |
| Same redirects in `moonrise$getRegionFileIfExists` | verified | no site (delegates) | no site (delegates) |
| `getRegionFileName` overwrite, shadows, `close`/`flush` | verified | verified | verified |
| Stop barrier (`stopServer` on Paper bundles) | verified | verified | verified; live on Paper, Folia, Canvas |
| Startup, save drain, upgrader | verified | verified | verified |
| Save barrier, lease, player gate, player data, stats/advancements writers | verified | verified | verified (all three by `MixinDescriptorsTest` + `HorizonSaveBarrierTest`, 2026-11-25) |
| Region-host save-all (`SaveAllCommandMixin` HEAD, `CommandsMixin`) | verified (descriptor) | verified (descriptor) | verified (descriptor, 2026-11-26); reflective region API live on Folia/CanvasMC hosts only |
| Coupled save (logout, `saveData`, player data/stats/advancements reads, `stopServer` HEAD, reflective fences) | verified | verified | verified (all three by `MixinDescriptorsTest` + `HorizonCoupledSaveTest`, 2026-11-26) |
| Coupled save departure (`ServerPlayerMixin`, `require = 0`) | absent (Paper) | absent (Paper) | absent (Paper); Folia/CanvasMC-host targets unchecked, live pending |

26.1.2 and 26.3 verified by `javap` against the dev bundles plus the
reflection patcher check (`MixinDescriptorsTest#mixinTargetsExistInThisBundle`,
run per bundle in the `test.yml` matrix); 26.2 verified by that same matrix.
`upstream.horizon-26.1.2.properties` is present so every push covers the
oldest bundle too, not only the release build. Selectors are
`defaultRequire`, so any future miss fails the boot loudly instead of
silently. The only exceptions are injectors marked `require = 0`, which
`MixinDescriptorsTest` treats as host-shape optional; every other selector,
`NEW` target, `INVOKE` target, shadow and overwrite of every `*Mixin.java` is
checked per bundle.

Retired, kept for provenance: redirects that named the constructor
descriptor were dead on arrival (the NEW point takes the class); a revision
that redirected only the create path let vanilla Anvil code parse `.linear`
files on the exists path.

Packaging notes proven in testing:

- The compression library must be embedded in the jar, otherwise the first new-format write fails at runtime, which also proves the redirect engages.
- The plugin descriptor follows the real schema with name, version, description, authors, mixins, dependencies, and wideners.
- The paper half loads through the plugin flag, but the mixin jar must also sit in the plugins folder for discovery.

For behavior and settings see docs/architecture.md and docs/configuration.md.

## 26.3 stable bundle (26.3.build.253-stable)

Test pin moved from 26.3.build.140-beta to 26.3.build.253-stable. All mixin
selectors resolve (MixinDescriptorsTest green). `RegionFile`-family interface
no longer declares `getRecalculateCount()`; the Horizon region-file
implementations keep the method without `@Override` so the 26.1.2 build pin
and 26.2 still compile and run.
