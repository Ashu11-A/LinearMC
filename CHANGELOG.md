# Changelog

Tags are project versions (`v1.0.0`, `v1.1.0`, `v1.1.1`, `v1.1.2`). Each release attaches one jar
per matrix leg: fork legs are named `linearmc-<server>-<mcversion>-<project>.jar`
(e.g. `linearmc-folia-26.1.2-1.0.0.jar`); the Horizon plugin ships one jar
for every supported MC line, `linearmc-horizon-<pluginver>-<project>.jar`
(e.g. `linearmc-horizon-1.0.0-1.0.0.jar`), built against the oldest bundle
with `api-version` set to its major.minor.

## v1.1.3

### Direct-memory exhaustion fix (all legs, V1/V2/V3)

Under sustained generation load (~500 chunk I/O/s) every chunk read and
write allocated fresh direct buffers with no deterministic free point, so
allocation outran GC-triggered Cleaner runs until the direct cap
(`MaxDirectMemorySize`, default = Xmx) and all chunk I/O threw
`OutOfMemoryError: Cannot reserve direct buffer memory` (TPS 9–17 at 28%
CPU — an I/O-memory stall, not worldgen math). One core fix heals all legs
(Canvas/Folia vendor core sources at stage time, Horizon embeds via JiJ).

- **Read:** the returned stream now frees its direct buffer on `close()`
  (`net.linear.io.LinearDirect`, `Unsafe.invokeCleaner`, best-effort with GC
  fallback); reads after close fail instead of touching freed memory.
- **Write:** the staging copy moved to the heap (LZ4 compresses heap source
  into a direct destination); only the retained per-slot packed image stays
  off-heap — one direct buffer per slot instead of two.
- **V1 flush:** streams slots into the zstd frame through per-slot heap
  scratch instead of assembling the whole region image in one multi-MB
  direct buffer. V2/V3/Pumpkin flush was already heap-only; all versions
  share the fixed read/write/load paths.
- **Load/close:** the whole-file load buffer and the master body are freed
  after parsing; region close frees the retained packed set (roots are kept
  alongside shared views because the Cleaner API rejects slices).
- **Proof:** new `DirectMemoryTest` (5 tests: close-frees, use-after-close
  guard, single retained buffer, close-frees-retained, 1200-cycle soak for
  V1/V2/V3) — all 5 fail pre-fix, all pass post-fix; suite 363 green.

## v1.1.2

### Horizon plugin parity, one jar for 26.1–26.3

The Horizon plugin lagged the server legs: five `config.yml` keys existed
in its template but did nothing, and its internal version sat at `1.0.0`
under `v1.1.x` tags. This release closes the gap and proves every MC line.

- **Wired settings:** `flush-max-threads`, `flush-frequency` (coordinator
  age gate; the heartbeat already followed the file),
  `compression-workers` and `long-distance-matching` now register the same
  live core seams the Canvas leg registers, with the documented defaults
  (1 / 10 / 0 / 0). `crash-on-broken-symlink: false` is honored (forensic
  boot skips the guard); the default `true` still throws fail-closed.
  The flush-completion listener stays no-op: this leg has no event bridge
  to deliver to.
- **Version follows the tag:** the Gradle project version, `paper-plugin.yml`
  and `horizon.plugin.json` stamp `LINEARMC_PROJECT_VERSION` at build time
  (tag builds; local builds keep `1.0.0-SNAPSHOT`).
- **One jar for all MC lines:** the shipped jar builds against the oldest
  bundle (`26.1.2.build.74-stable`, `api-version: 26.1`) and loads on
  26.1–26.3 — the six NMS contact points are identical across those
  bundles (verified by `javap`), and the plugin only uses API present in
  the oldest one. No per-MC assets.
- **Per-bundle proof:** `test.yml` runs the full plugin build + unit suite
  against every pinned bundle (26.1.2, 26.2, 26.3), including a reflection
  patcher check that every mixin selector target exists in that bundle's
  NMS. The unit-test plugin harness is version-proofed too (no
  `JavaPlugin` ctor, whose 26.1/26.2 shape needs a live server).
- **Move-together rule:** new `CONTRIBUTING.md` section requires every
  surface change (commands, config keys, `linear$` seams) to land on all
  legs in the same change; `scripts/preflight.sh` gate `4b` enforces the
  config-key and seam halves mechanically.
- **Known limits:** the `v1.1.0`/`v1.1.1` Horizon assets are unchanged
  history; fork-leg jars in this release are behaviorally identical
  rebuilds (the only core delta is additive config API no fork leg calls).

## v1.1.1

### Sub-package reorganization, zero behavior change (2026-10-14)

Structural maintenance release: every source file is under 1,000 lines and
every `src` package holds at most 5 files. The 3,658-line converter,
1,293-line flush coordinator and 1,123-line buffered region file are now
thin delegating facades over extracted modules (converter 596, coordinator
598, buffered 972). Versions share one abstract base with identical hook
names, each version has its own public decoder and encoder classes, and the
save-format sinks (`LINEAR`/`B_LINEAR`/`ANVIL`) are separated from the
version codecs (`V1`/`V2`/`V3`/Pumpkin). Same wire bytes, same config
defaults, same gameplay as v1.1.0; two internal methods and one constant
widened from package-private to public, no signature changes. One internal
guard added: buffered masters fail fast instead of staging through the
plain-linear render path.

## v1.1.0

### B_LINEAR WAL-at-rest: swap pruned on clean close, staging swept on open

The buffered tier no longer carries a persistent duplicate: clean `close()`
deletes the `.b_linear.swp` sidecar (plus `.tmp`/`.swp.tmp` staging), so
steady state on disk is the master alone and the next open rebuilds swap
state from the master. Crash-window durability is unchanged (swap retained
while open/dirty; open still verifies every sector and repairs torn slots
from the master). Related hygiene: open sweeps killed-flush/compact
`.tmp` siblings (both tiers), the startup preamble sweeps `r.*.tmp` /
`r.*.swp.tmp` / `r.*.pumpkin-tmp`, and finished B_LINEAR converts ship
master-only. Coordinator `flushOne` no longer clears unconditionally: a
file still marked after `flush()` (write raced the snapshot) is requeued
instead of dropped.

Buffered (`B_LINEAR`) region tier, selectable V2/V3 bucketed writer, and a
durability fix for lines whose storage flush/close paths never fire (found
live on Horizon: writes reached the swap but no master was ever written).
Wire-compatible with DivineMC in both directions (their V2/V3 reader opens
our files; our reader opens theirs, verified chunk-for-chunk on real
server-generated regions).

### Five-format system: V1 / V2 / V3 / B_LINEAR / PUMPKIN (2026-10-02)

Pumpkin-server region files are backward compatible: same magic, version
byte and `.linear` extension as our V1, fully auto-detected by structural
try-parse (never byte-17 sniffing), readable, convertible, and writable.
Any-to-any migration (including direct V1→V3 and V1→B_LINEAR) runs only
via explicit `/linear convert --from X --to Y`; startup never rewrites
Pumpkin files. Proven against the real Pumpkin reader/writer, both
directions, chunk-for-chunk on real server bytes.

- **DivineMC-style per-version structure:** new `net.linear.versions`
  package — `Version` (`implementation()`/`version()`/`parse()`/`flush()`,
  stateless shared codecs; IO/locks/coordinator stay in the facade),
  `V1Linear`, `V2Linear`/`V3Linear` over abstract `V2LinearBase`, plus
  `LinearBase.getShared(byte)` as the single wire→implementation map
  (`1,2→V1`, `3→V2`, `4→V3`). `LinearRegionFile` is now a thin facade;
  V1 hot path allocation-identical, full suite green with zero behavior
  change at each step.
- **Pumpkin adapter:** new `PumpkinLinear` (wire 2, 26 B superblock, LSB-first
  bitmap, same features encoding, 13 B table with fixed level 1, raw-len
  slots, dense empty buckets, fixed zstd-1 writes, u64→int32 timestamp
  clamp, fail-high hashes). Transpose vs DivineMC bucket/slot order
  (`bz*grid+bx`, cz-outer) proven against `global_chunk_index`, including
  off-diagonal chunks. Live files stay Pumpkin on flush (preserve, never
  silent rewrite); no `LinearImplementation` member (shares wire 2, resolves
  via detector per R3).
- **Fail-closed auto-detect:** `RegionFormatDetector` fully validates every
  candidate without decompressing (xxhash over compressed bytes, filename
  coords for Pumpkin); zero matches → corrupt error with per-candidate
  reasons, two or more → ambiguous error naming both. No fall-through, ever.
- **Converter 5-format matrix:** explicit `--from/--to/--grid` (`linear` =
  any-`.linear`-layout wildcard, `b_linear` alias completed); Pumpkin
  targets via `tmp+fsync+atomic-move`; same-path `.linear` converts
  pre-swap-validate then atomic-swap (no delete); cross-extension shadows
  escalate FAILED both-kept; validate-then-delete, 3-attempt requeue and
  protection-halt semantics preserved; halt/skip messages name the explicit
  remedy. B_LINEAR startup skips Pumpkin sources (SKIPPED, never queued).
- **Legs:** explicit converts flow through the same quiesce-gated workers
  on Horizon/Canvas/Folia; per-folder single-flight (second `--execute`
  rejected, Folia serializes via singleton executor); counting aligned to
  collection semantics; `targetFormat()==ANVIL` override checks; probe,
  symlink guard, upgrader and DFU paths verified extension-uniform.
- **Proofs:** 342 core + 47 Horizon tests green; real Pumpkin fixture
  embedded (`GOLDEN_PUMPKIN_REAL`, 5 chunks incl. off-diagonal) plus DivineMC
  MSB-bitmap golden assertion; Pumpkin's own linear suite 10/10;
  end-to-end matrix PUMPKIN→V1→V3→V1→B_LINEAR→V1 payload-identical at every
  hop via production entrypoints. Known gap: no live server boot with the
  new core (Horizon/Folia/Canvas server builds unavailable locally).

- **B_LINEAR buffered tier:** new `BufferedLinearRegionFile` (codec):
  `.b_linear` master (18 B header + single checksummed zstd frame of 1024
  inner envelopes) fronted by a persistent `.b_linear.swp` swap (17,429 B
  header: magic, version, xxhash seed, allocator high-water, 1024 x 17 B
  sector entries; per-chunk `[len, timestamp, xxhash32, payload]` envelopes
  inside LZ4 outer frames). Divergences from DivineMC, all deliberate:
  the swap is never `DELETE_ON_CLOSE`, open verifies every sector and
  repairs torn slots from the master (fail-closed when neither copy is
  usable), a failed master replace never deletes the previous master, and
  per-write sync never blocks the caller. Auto-compact above 1 MiB spare
  at 60% fragmentation; tmp-file hygiene plus `Error` re-arm on every
  failure path; close drains until clean (retryable, channel stays open).
- **V2/V3 selectable writer:** new `LinearBucketWriter` plus
  `LinearImplementation` (`V1(2)`/`V2(3)`/`V3(4)`) and `LinearBucketGrid`
  (whitelist 1/2/4/8/16/32, shared index math). `LinearRegionFile` keeps
  its V1 default and reads all versions; `linear.implementation` /
  `linear.grid-size` (global, file then `-Dlinearmc.*` sysprop) select the
  writer. Readers preserve timestamps; V2/V3 files rewrite as V1 only if
  the implementation is set back. Decompression-bomb guard
  (`MAX_IMAGE_BYTES`) enforced identically on all three writers and load.
- **Core reliability:** every core mutation path self-reports to the folder
  coordinator (no write depends on caller-side reporting); flush failure
  re-arms dirty on `Error` as well as `IOException`/`RuntimeException`;
  failed flushes delete their tmp files; single direct load allocation
  capped at 1 GiB; `close()` drains until clean instead of dropping a
  racing write; `LinearFormatOverride` accepts `B_LINEAR`.
- **Converter, union-per-target:** six directions (`MCA/LINEAR/BLINEAR`
  pairs) with `--to-blinear`; each `--to-*` converts every convertible
  non-target file. Swap siblings travel with masters through staging
  moves, source deletes, orphan-shadow cleanup, and attempt cleanup;
  staging coordinator registrations are evicted so quiesce gates never
  trip on ghost folders. Same-coordinates mixed pairs escalate to
  FAILED with both sides kept (never silent drop); alerts name the
  actual source/target extensions. `B_LINEAR` validation (master
  magic/version, 24 B minimum); reverse accepts both linear-family
  sources; header sanity accepts wire 1-4.
- **Config surface:** `format` accepts `B_LINEAR` (exact, case-sensitive);
  new global `linear.implementation` (default V1) and `linear.grid-size`
  (default 8); new `-Dlinearmc.implementation` / `-Dlinearmc.grid-size`
  sysprops with override log lines; unknown-format message now lists all
  three formats. Templates and `docs/configuration.md` updated
  (`docs/concepts.md` gains B_LINEAR/bucket/heartbeat entries,
  `docs/architecture.md` documents V2/V3 read + rewrite semantics).
- **Legs:** triple-read probe (`.mca`, `.linear`, `.b_linear`) and
  linear-family symlink guard on Folia, Canvas (26.1.x, 26.2) and Horizon;
  `BufferedOpener` registered beside every `LinearOpener`; upgrader
  filters, format mappers, and startup conversion route to
  `convertFolderToBuffered` for `B_LINEAR` targets; deterministic
  `probeWriteFormat` (any `.b_linear` wins, else `.linear`); DFU recreate
  mirrors every master move/delete onto its `.swp` sibling.
- **Horizon durability (the live finding):** async Bukkit timers throw
  `UnsupportedOperationException` on region-threaded lines, so the plugin
  runs its flush heartbeat on one daemon thread and force-drains plus
  shuts the shared pool on disable — clean stops lose nothing even though
  storage `flush()`/`close()` never fire there. Wrapper stream closes
  now normalize payloads (matching direct writes) and self-report;
  family-switch opens probe the other sibling instead of forking a new
  file; temp-asset directories are cleaned on close. Proven live:
  12-master B_LINEAR world, wire-3 grid-4 V2 world, and a 12/12
  `--to-blinear` convert, all chunk-verified with zero errors.
- **Validation:** 298 `linear-core` tests plus 42 Horizon tests green;
  golden V2/V3 fixtures (DivineMC-written) embedded in-repo; mixed-pair,
  truncated-swap, and quiesce-gate regressions covered.

- **Packaging (no behavior change):** the 47 shared Canvas overlay files
  now live once in `versions/servers/common/canvas/` and stage first
  (44 carried byte-identical; `CommandLinear.java`,
  `RegionFileFormat.java`, and the upgrader scan test carry the current
  B_LINEAR feature state, identical on both legs);
  Canvas 26.3 ships under this version (`linearmc-canvas-26.3-1.1.0.jar`,
  pinned to Canvas `main@cc4e7611`, legs keep only seam adapters with
  hub-shadow staging).
  each version leg keeps only its seam adapters (a drifted version shadows
  the hub copy with a loud staging note — see the hub README). Verified
  locally against Canvas main (`cc4e7611`, MC 26.3): full overlay stages,
  `compileJava`/`compileTestJava` clean, `LinearNmsTestSuite` 140/140, ANVIL
  boot → convert reboot (`Startup conversion done`, 12 `.linear` / 0 `.mca`)
  → LINEAR boot, plus a 5-minute B_LINEAR load run (297/297 `.b_linear`,
  81,992 chunks, zero errors, 35 s shutdown drain).

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
  tags and attaches the paperclip jar.
- **Release package:** revert procedure (flag flip, convert back, stock jar
  swap) behind backup, free-space and sha gates; operator notes; config
  templates.
- **Upgrading:** pin `format: ANVIL` on worlds you do NOT want converted
  before first boot, or they convert automatically. Downgrade after
  converting is one-way until re-conversion completes (do not run an older
  jar on converted worlds and expect the new files to be picked up).
