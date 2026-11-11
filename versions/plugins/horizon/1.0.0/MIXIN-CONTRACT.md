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
| Close / flush hooks | `close` TAIL, `flush` HEAD | Evict / drain the folder's coordinator |
| Save drain | `ServerChunkCache.save(Z)` TAIL | `/save-all flush` forces every dirty Linear file (fork `minecraft-0017` parity) |
| Startup conversion | `DedicatedServer.initServer`, before `SparksFly.enableEarlyIfRequested()` (cancellable) | Before plugins load and before any storage opens, like the Canvas overlay; `false` aborts boot |
| Final stop barrier | `onServerExit()` INVOKE in `MinecraftServer.stopServer` (Paper) or `stopPart2` (Folia/Canvas) | After the final save and region I/O flush on every host |
| Upgrader awareness | `RegionStorageUpgrader.getAllChunkPositions`: `listFiles`, `REGEX`, `new RegionFile` | Inert while Paper 26.x stubs `--forceUpgrade` |

Multi-method injectors (both storage methods; the Moonrise exists path only
owns these call sites on 26.1.2) use `require = 1`: one site overall is
enough. `MixinDescriptorsTest` checks, per dev bundle, that every selector,
shadow and overwrite exists and that every required INVOKE/NEW/FIELD target
is really called inside its host method (bytecode).

Per-MC retrace (HORIZON_REF `31d1a1b7`; build pin in
`versions/plugins/horizon/upstream.horizon.properties`, per-bundle test pins
in `upstream.horizon-<mc>.properties`):

| Selector | 26.1.2 | 26.2 | 26.3 |
| --- | --- | --- | --- |
| Storage redirects in `getRegionFile` | verified | verified | verified |
| Same redirects in `moonrise$getRegionFileIfExists` | verified | no site (delegates) | no site (delegates) |
| `getRegionFileName` overwrite, shadows, `close`/`flush` | verified | verified | verified |
| Stop barrier (`stopServer` on Paper bundles) | verified | verified | verified; live on Paper, Folia, Canvas |
| Startup, save drain, upgrader | verified | verified | verified |

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
