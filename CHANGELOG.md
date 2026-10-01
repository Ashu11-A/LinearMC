# Changelog

Tags are project versions (`v1.0.0`). Each release attaches one jar + `.sha256`
per supported version line, named `folia-linear-<mcversion>-<project>.jar`.

## v1.0.0 - 2026-10-01

Initial release: Linear-format region compression for Folia and Canvas
servers, plus the Horizon plugin for Paper-style servers. Linear is on by
default, leftover Anvil files convert automatically on boot, and live
compression figures are one command away. Built from `versions/26.1.x` at
Folia `62dc0f2` (MC 26.1.2), Java 25.

- **Linear by default:** new worlds default to `region-format.format: LINEAR`
  with `linear.compression-level: 6`. Worlds with an explicit `format: ANVIL`
  keep working untouched (dual-read, ANVIL fail-safes, and the `null → ANVIL`
  fallback are all preserved and tested).
- **Startup auto-conversion:** on boot, before any plugin code runs, worlds
  whose active format resolves to LINEAR convert leftover `.mca` files to
  level-6 `.linear`. Explicit-ANVIL worlds never convert.
- **Conversion pipeline:** per-file CONVERT → VALIDATE → DELETE stages run
  in parallel across files on a bounded pool; per-file order is strict (never
  delete unvalidated). Validation requires target existence, header sanity,
  chunk-count parity, and full payload decompression. Failures re-queue up
  to 3 attempts; exhaustion enters protection mode (descriptive terminal
  alert) and halts the server for inspection. Valid shadows and divergent
  pairs escalate, never auto-delete; crash orphans (`new_*` dirs) are
  cleaned on the next run.
- **Save-drain wiring:** unload passes (`ChunkMap.processUnloads`), explicit
  saves and shutdown (`RegionShutdownThread` post-`stopServer`) drain via
  `flushAllDirty`/`evictAll`, so dirty files at stop persist as `.linear`.
  Periodic drains submit to the shared flush pool without joining, so file
  flushes never stall chunk generation; forced and stop drains keep the
  synchronous barrier and join in-flight batches. Clean shutdown stays
  zero-loss and the crash window is unchanged (up to one `flush-frequency`).
- **Flush core:** shared bounded flush pool behind `flush-max-threads` with a
  caller-participates durability barrier (byte-identical serial path when
  `<= 1`); real age-based flush behind `flush-frequency` (first-dirty age,
  longest-unflushed first; eviction forces, bound pressure ignores age);
  zstd writer `setWorkers` / `setLong` and reader `setLongMax(27)` tuning.
  The level-6 default was chosen after flush-battery testing; the conclusions
  that hold live in `docs/performance.md`.
- **Observability:** `/linearstats` Adventure panel (per-world
  region/poi/entities, human units, dirty bar, level + millis-since, TOTALS)
  with `LinearRegionFlushCompletedEvent` bridge call site and `LinearStats`
  Paper API delegate for plugins. `FolderSnapshot` carries `rawBytes`,
  `compressedBytes`, `flushP50Micros`, `flushP99Micros`,
  `millisSinceLastFlush`.
- **Baseline:** `versions/26.1.x/` layout, `net.linear` namespace, CI matrix
  and scripts. Linear region format in Folia chunk I/O: `LinearRegionFile`
  (v2 writer, v1/v2 reader), LZ4 hot path, whole-region zstd flush,
  checksums, atomic tmp-force-move saves. Dual-read dispatch probes `.mca`
  first, then `.linear`, independently of the configured format.
  `LinearFlushCoordinator`: per-folder bounded dirty set, `MAX_DIRTY = 512`,
  eviction on unload. Config surface: `region-format.format`,
  `compression-level`, `crash-on-broken-symlink`, `flush-frequency`,
  `flush-max-threads`. Three Kaiiju defects fixed rather than ported: the
  `||` dual-read predicate, enum identity in the symlink guard, and the
  spaceless `(mca|linear)` regex (grep-gated against reintroduction).
  Lock-free timing instrumentation (`LongAdder`/`LongAccumulator` only, Anvil
  path byte-identical); `LinearFolderNames` helper. `--forceUpgrade` and
  `--recreateRegionFiles` honour the world format.
- **License:** `LICENSE` (GPL-3.0-only), matching the Paper/Folia/Kaiiju
  lineage.
- **CI:** `build.yml` (tags/releases/dispatch, paperclip + release assets)
  and `test.yml` (every push/PR: static gates, preflight, unit suites,
  config checks); `scripts/preflight.sh` local CI mirror. CI builds on `v*`
  tags and attaches the paperclip jar plus its sha256.
- **Release package:** revert procedure (flag flip, convert back, stock jar
  swap) behind backup, free-space and sha gates; operator notes; config
  templates.
- **Upgrading:** pin `format: ANVIL` on worlds you do NOT want converted
  before first boot, or they convert automatically. Downgrade after
  converting is one-way until re-conversion completes (do not run an older
  jar on converted worlds and expect the new files to be picked up).
