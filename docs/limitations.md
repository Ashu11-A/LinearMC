# Limitations and open questions

This page lists what this release does not cover. Read it before a rollout.

## What was not measured

- Tick timing has not been measured with players connected, and there is no side-by-side comparison with the old format on a warm server. Judge a rollout by the lag players feel on a warm server. Cold-start numbers mislead, because a cold cache and slow first compression make the first minutes look worse than steady state.
- Nothing above the default compression level has had a multi-day soak. The highest level has booted cleanly and completed a clean manual save, but the cost of running it for days is unknown.
- Shutdown flush time has a single paired sample, in which the new format took slightly longer. One sample is not enough, so treat shutdown cost as unmeasured.
- The broken-symlink guard has never fired on a real server; only fixtures test it. When it first fires it will stop the server on purpose, so check your symlinks beforehand.

## Where you can run it

- Only local disk on a single node is tested. Shared disks, network filesystems, two servers sharing a world folder and cold archiving are all untested. Never run two servers against the same world folder.
- There is no cold archiver in this release.
- `--forceUpgrade` and `--recreateRegionFiles` work only on the Folia and CanvasMC forks, because Paper 26.x disables them on every host the Horizon plugin runs on.
- Before converting or rolling back, have free space of about two and a half times the current world size, so copies, backups and rewritten files all fit at once.

## Conversion

- In a world set to the new format, leftover old-format region files convert automatically at startup, on every leg, before plugins load. Each file is converted, checked and then deleted; failures get a few retries, and the server stops if errors keep happening. `/linear convert` converts on demand in either direction.
- A live `/linear convert --execute` away from `.mca` never deletes an `.mca` region the server opened since startup (a handle to a deleted `.mca` would lose every later write) and defers Linear regions with unflushed writes. Those files are reported as SKIPPED and kept. To finish them, restart and run the convert again before the region loads. Startup conversion finishes them only in a world set to a linear format, and only into that format.
- Worlds explicitly set to the old format never convert.
- The offline two-way converter used for earlier measurements is not part of this repository.
- Empty zero-byte region files are skipped and reported, never converted. When a single file reports a conversion error, this is usually the cause. A failed file leaves the live world untouched.
- Always confirm a converted copy shows zero differences before trusting it.
- One region file in the live test world has a permanent compression error and cannot be converted. It stays in the old format, and the server regenerates the affected chunk.
- The write path creates no sidecar files for oversized chunks (no `.mcc` files). If an offline conversion left sidecars, copy them along with the region files when you move data by hand.

## Write path edges that lose data

- If a stored chunk header disagrees with the file header, that chunk is regenerated and its old contents are lost. If the chunk matters, restore the region file from backup and note the coordinates from the log.
- Chunks larger than 500 megabytes are rejected, not saved.
- The symlink guard described above stops the server instead of treating a broken link as an empty region and regenerating it.
- A zero-length `.linear` file (or `.b_linear` master) holds no region data. It is renamed to `<name>.corrupt-<epoch ms>` beside the original (moved, never deleted), an ERROR is logged, `/linear stats` counts it as `quarantined`, and the region opens empty, so its chunks regenerate unless you restore the file from a backup.
- A `.linear` file that fails to open (truncated, bad header, bad checksum) is copied to `linear-conflicts/<region|entities|poi>/` beside the storage folder before the error reaches the server, named `<name>.conflict-<mtime ms>-<size>`. The original stays in place; repeated failures of the same file make one copy. A `.b_linear` master that cannot be parsed is copied the same way before its swap file heals (rewrites) it. `/linear stats` counts the two events apart: `quarantined` (a zero-length file moved aside, its chunks regenerate) and `conflicts` (a copy kept, the original stays). The conflicts folder location is fixed (beside the storage folder, so it travels with world backups); there is no config key to move it.

## Settings and rollback

- Most flush and compression settings take effect as configured; `configuration.md` lists the defaults.
- A hard crash, force-kill or host freeze can lose more than `flush-frequency` + 1 second of edits. An edit first waits for the server to hand the chunk to the region file (the chunk autosave, once per `auto-save-interval`, 1200 ticks by default, with a per-tick cap), and only then for the Linear age gate (`flush-frequency` + 1 second, about 11 seconds at the default) and the write itself. Every flush syncs the file and then its folder after the rename, so a flush that finished is on disk. Saves, chunk unloads and a clean `stop` write everything at once, and a forced flush also waits for flushes that were already running.
- Player data (`playerdata`, `stats`, `advancements`) is saved on its own schedule, separate from the chunk schedule above, unless `coupled-save.enabled` is on (next point). After a hard crash a player file can be newer or older than the chunks around it, so an item that moved between a player and a block in the last minute or so can be lost or duplicated. Only `save-all flush` (every leg and host) and a clean `stop` order the two (chunks first, then players); its marker (`linear-checkpoint/save-all.done`) is the point a backup can trust. With `durable-player-data` on, each player file is whole after a crash (old or new, never missing), but that does not tie it to its chunks, and a save made on the tick thread is not fsynced: it becomes durable on the filesystem's own commit (ext4: 5 s), so a crash right after it can bring back the previous save, whole.
- `linear.coupled-save.enabled` (default on) closes one direction of that gap. What it guarantees and what it does not:
  - Guaranteed: a player file is never written before the chunks of its region are durable, so a place or deposit right before a crash becomes at worst a duplicate instead of a loss. A player who leaves a region after changing it (teleport, portal, respawn) triggers a capture of the region being left first. Chunks unloaded shortly before a capture are covered (details in `architecture.md`).
  - Storage formats: Linear and B_LINEAR folders (the forced flush renames and directory-syncs each file) and ANVIL folders: the forced storage flush calls `RegionFile.flush`, which fsyncs every open `.mca` (`FileChannel.force`), and a region file closed earlier was forced by its `close` (checked per Paper dev bundle by `HorizonSaveBarrierTest`, and in the Folia/CanvasMC sources). ANVIL caveats: an in-place sector write can still tear on power loss (vanilla behaviour), and the rename of an oversized-chunk `.mcc` file is not directory-synced. The server logs the scope at enable: `Coupled save scope: ...`.
  - Not covered: the other direction (an item mined, withdrawn or picked up can still be lost, because a chunk can become durable on its own while the player file waits up to `coupled-save.interval`); items that cross regions between two captures (in both or neither); a region that splits while its player walks away (window: chunk autosave interval plus the Linear age gate); player saves outside a capture (stop, `saveData` from another thread, `Server#savePlayers`, any save during `save-off`), which are written at once as before.
  - Withheld files: a chunk write that failed and is waiting for its retry holds back every staged player file, with no upper bound. A crash meanwhile rolls those players back to their last written file. At `stop` the staged files are written after the final chunk save, but before the worlds close their region files, like the stop's direct player saves: a crash or power loss during `stop` has no player/region ordering guarantee (a clean `stop` that completes leaves both on disk).
  - Runbook: watch for `Coupled save: N player file(s) withheld` (WARN, ERROR after 60 s) and `coupled-withheld=N` in `/linear stats`; fix the disk or the broken region; then wait for the next barrier or run `stop`.
  - Horizon: the fences read Moonrise's private chunk I/O task map; a host where that fails keeps coupled save off with an ERROR. The departure hook targets exist only on Folia/CanvasMC hosts and are not checked per dev bundle (`MIXIN-CONTRACT.md`).
  - Evidence: core unit tests (fake fences, 0 player writes before their barrier in 200 random trials) and leg tests. Live check: `scripts/coupled-order-check.py` (start the server with `-Dlinearmc.coupled-save.debug=true`); a run with a real player has not been done yet.
- The save-all marker is per region, not one global instant: regions save on their own threads in turn, so an item that crossed from one region to another while `save-all` ran can be in both or neither of the saved files.
- On Horizon on Paper, `save-all flush` runs on the main thread, so the server tick is frozen while it waits for chunk writes and staged coupled saves, at most 20 s (below Paper's 60 s watchdog), then it reports failure. On the forks the wait runs off the region threads (60 s budget); with `coupled-save.enabled` off, each region thread still waits for its own forced storage flush, which shows as a region MSPT spike per `save-all flush`.
- Small `save-all` differences between the forks: CanvasMC skips regions with no live sections or that are dead and prints `Saving regions (N region sections)`; Folia keeps `hasNoAliveSections()` private, so the Folia fork queues every region and prints only `Saving the game...` (an empty region's task does nothing). The save-off player gate also differs: the forks skip a region's player autosave when that region's world has saving off, while Horizon skips it when any world has saving off (`save-off` flips every world, so the two agree unless a plugin turns off one world with `World#setAutoSave(false)`).
- On Horizon on a Folia or CanvasMC host, `save-all` runs the same region-scheduled barrier as the forks, through the host's region API read by reflection (it is not in the Paper dev bundles, so only live runs prove it; a host where it does not resolve makes `save-all` fail with an error instead of saving off-thread). On Folia, Horizon registers `save-all` (Folia upstream leaves it out). On the Folia fork and Horizon on Folia, `save-all` saves chunks and players only; level and global data keep Folia's own schedule (the stop). On CanvasMC both save the global and world data first, as Canvas does.
- The periodic player autosave gate during `save-off` checks every world on Horizon (any world with saving off pauses it) and the ticking region's own world on the forks. The two agree for `save-off`; they differ only when a plugin turns autosave off for a single world (`World#setAutoSave(false)`).
- The save-off lease (`save-off-max-duration`) turns saving back on even when a backup is still copying. Set it above the longest backup, or 0 if the backup tool always runs `save-on` itself.
- The log-flush-batches setting is inert in this release and changes nothing.
- Rollback rung B needs a stock server jar that you supply. The script starts with a placeholder path and stops with instructions until you point it at a real jar. Rung A needs no extra jar. The operator manual explains the rungs, and `concepts.md` defines the terms.
- Every rollback rung needs a verified backup that contains a verification marker and at least one region folder; otherwise the script refuses to run.
- `configuration.md` has the setting defaults and the exact warning lines, and the operator notes have the recovery steps.

## Maintenance

- The code has to be rebased on every new server release. `architecture.md` lists the places that tend to break.
- A compression dictionary was scoped and then parked; this release has none.
