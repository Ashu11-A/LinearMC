# Configuration

This page is the source of truth for Linear settings: where each one
lives, its shipped default, and what a bad value does. The defaults and
fallbacks match `linear-core/config/src/net/linear/config/LinearPolicy.java`
and the shipped template in `release/region-format.yml`.

## How settings resolve

A system property wins over the file, and the file wins over the shipped
default. Nothing reloads live, so restart the server after editing a file
or changing a system property.

## Where each setting lives

World settings have a fallback and an override. The fallback is in
`paper-world-defaults.yml` and applies to every world that does not set
its own value. A world overrides it in its own `paper-world.yml`; if that
file has no `region-format` block, the world inherits the fallback.

Server-wide settings live only in `paper-global.yml` and cannot be set
per world. Putting them in a world file does nothing.

## Settings

The first three settings are per world. The rest are global only.

| Key | Where | Default | Bad value does what |
| --- | --- | --- | --- |
| region-format.format | paper-world-defaults.yml, paper-world.yml | LINEAR | Unknown value logs a warning and runs that world as ANVIL |
| linear.compression-level | paper-world-defaults.yml, paper-world.yml | 6 | Outside 1-22 logs a warning and runs at 6 |
| linear.crash-on-broken-symlink | paper-world-defaults.yml, paper-world.yml | true | Missing value uses true; no fallback log line |
| linear.flush-frequency | paper-global.yml | 10 seconds | Below 1 logs a warning and uses 10. Age of the first unsaved write before a region file is flushed; it is not the whole crash window (see below) |
| linear.flush-max-threads | paper-global.yml | 1 | Negative means processor count plus the value, floored at 1; zero becomes 1; no fallback log line |
| linear.compression-workers | paper-global.yml | 0 | Negative logs a warning and uses 0 (serial) |
| linear.long-distance-matching | paper-global.yml | 0 | Negative logs a warning and uses 0 (off) |
| linear.log-flush-batches | paper-global.yml | false | Currently inert; the value is accepted but changes nothing |
| linear.implementation | paper-global.yml | V1 | Unknown value logs a warning and writes V1 |
| linear.grid-size | paper-global.yml | 8 | Outside 1, 2, 4, 8, 16, 32 logs a warning and uses 8 |
| linear.save-off-max-duration | paper-global.yml | 120 seconds | Negative logs an error and uses 120; 0 turns the lease off. A `save-off` older than this turns saving back on with a WARN and an ops broadcast |
| linear.durable-player-data | paper-global.yml | true | Missing value uses true; no fallback log line. `false` restores the vanilla player file replace. No fsync on the tick thread (see "Save barrier and player data") |
| linear.coupled-save.enabled | paper-global.yml | true | Missing value uses true; no fallback log line. `true` writes a player file only after its region's chunks are durable (see "Coupled save order" below) and implies `durable-player-data` |
| linear.coupled-save.interval | paper-global.yml | 20 seconds | Below 1 logs an error and uses 20. Time between two captures of one region |

> Warning: with `coupled-save.enabled` on, a chunk write that keeps failing holds back every staged player file with no upper bound; a crash meanwhile rolls those players back. Watch for `Coupled save: N player file(s) withheld` and `coupled-withheld` in `/linear stats`. The ordering covers Linear, B_LINEAR and ANVIL folders, with ANVIL caveats (torn in-place sector writes, `.mcc` renames not directory-synced; see `limitations.md`); the server logs the scope at enable.

On the Canvas fork, the same keys live in Canvas' own files: the per-world
ones in `config/canvas-worlds.yml` (and `<dimension>/canvas-patch.yml`)
under `region-format`, the global ones in Canvas' global configuration
under `linear` (camelCase: `flushFrequency`, `implementation`, `gridSize`,
`saveOffMaxDuration`, `durablePlayerData`, `coupledSave.enabled`,
`coupledSave.interval`, ...). Every `-Dlinearmc.*` override is named once in the boot log.

The format setting chooses the file extension used for new writes:
`ANVIL` (`.mca`), `LINEAR` (`.linear`), or `B_LINEAR` (`.b_linear` plus a
`.b_linear.swp` swap file that travels beside the master; never delete or
move one without the other). Reads always probe every extension
(`.mca`, then `.linear`, then `.b_linear`), so changing the format never
hides existing data. One server can run worlds in different formats.
Watch out for one thing: the shipped default is LINEAR, but the fallback
for a bad value is ANVIL, so a misspelled format runs that world on Anvil.

The compression level is the zstd level used when a dirty region
flushes. Existing files need no conversion; the next flush rewrites them
at the new level. The symlink setting guards against data loss. With the
default `true`, a broken symlink under a linear-family path stops the
server, so the chunk system cannot regenerate the region as if it were
empty. Set it to `false` only to boot for forensics, then fix the path.

The implementation picks the on-disk writer: `V1` writes a single
compressed blob (the reference format), and `V2`/`V3` write bucketed,
DivineMC-compatible files. Readers accept every version, and V2/V3 files
are rewritten as V1 only if the implementation is later set back. The
grid size sets the bucket grid for V2/V3 writes (default 8, also
DivineMC-compatible). Both settings are global and only affect writers:
existing files keep their layout until they are rewritten, and readers
need no configuration to open any version.

Flush frequency is the minimum age for the deferred flush: a save only
flushes files that have been dirty for at least that many seconds. It
also sets the interval of the flush heartbeat, which matters on lines
whose storage flush/close paths never fire; without the heartbeat they
would keep writes in RAM indefinitely. Flush threads size the shared
flush pool, and 1 means serial. Compression workers set to 0 means
single-threaded compression for that flush. Long-distance matching set
to 0 means it is off. The log-flush-batches flag does nothing for now.

Format values in a file are matched exactly after trimming, so a
lowercase `linear` in a file counts as an unknown value.

## Conversion

`/linear convert` copies one world's region files to another layout.
There are five migration formats: `mca` (plain Anvil), `v1` (single
compressed blob, the shipped default), `v2` and `v3` (bucketed,
DivineMC-compatible), and `blinear` (buffered). The quick form picks a
direction (`--to-linear`, `--to-mca`, `--to-blinear`); the explicit form
names both ends (`--from mca|v1|v2|v3|blinear|pumpkin|linear|auto --to
mca|v1|v2|v3|blinear|pumpkin|linear`) plus an optional bucket grid
(`--grid 1|2|4|8|16|32`). `--to linear` means `v1`; `--from linear`
matches any `.linear` layout.

Pumpkin shares the `.linear` extension, but its files only migrate on
explicit request: `/linear convert <world> --from pumpkin --to <target> --execute`
is the only way to copy them, and startup auto-conversion never rewrites
them. A Pumpkin target ignores `--level` (it is fixed at 1) and uses a
default grid of 2; `v2`/`v3` targets default to a grid of 8. None of this
has a setting. Conversion uses the world's configured level plus the
per-job flags above, and a convert without `--execute` only reports what
it would do.

## Command-line overrides

Four settings can be set from the JVM command line, and the system
property wins over both the file and the default. An invalid or blank
value is ignored, and the file value stays in effect.

The properties are `-Dlinearmc.format` for the world format,
`-Dlinearmc.compression-level` for the zstd level,
`-Dlinearmc.implementation` for the writer (`V1`, `V2`, `V3`), and
`-Dlinearmc.grid-size` for the bucket grid. The other keys have no
system property.

## Bad-value log lines

These lines are quoted exactly as they appear in the code. `<value>` is
the bad value that was supplied.

> [region-format] Unknown region format, expected ANVIL, LINEAR or B_LINEAR. Falling back to ANVIL.
>
> [region-format] linear.compression-level must be 1-22, got `<value>`. Falling back to 6.
>
> [region-format] linear.flush-frequency must be >= 1, got `<value>`. Falling back to 10.
>
> [region-format] linear.compression-workers must be >= 0, got `<value>`. Falling back to 0.
>
> [region-format] linear.long-distance-matching must be >= 0, got `<value>`. Falling back to 0.
>
> [region-format] linear.implementation must be V1, V2 or V3, got `<value>`. Falling back to V1.
>
> [region-format] linear.grid-size must be one of 1, 2, 4, 8, 16, 32, got `<value>`. Falling back to 8.

A healthy boot prints none of them. The first line means the world is
running on Anvil even though you asked for something else, so fix the
spelling. The others mean the server is using the stated fallback while
the file still holds the bad value.

## Horizon plugin difference

The Horizon plugin does not read `paper-world-defaults.yml`,
`paper-world.yml`, or `paper-global.yml`. It uses `plugins/LinearMC/config.yml`
with the same keys (shipped default `format: LINEAR`, like the forks), plus
the same system properties, set on the server JVM. The plugin reads the
file during bootstrap, before the server initializes, so startup conversion
and the first world storages already use it.

Per-world values go in a `worlds:` section keyed by dimension id (quote the
key, since it contains ':'). Only `format` and `compression-level` can be
set per world, and system properties still win:

```yaml
worlds:
  "minecraft:the_nether":
    format: ANVIL
  "minecraft:the_end":
    compression-level: 9
```

On every leg (Folia, CanvasMC, Horizon), durability does not depend on a
save, an unload or a stop. The core starts one daemon thread,
`linearmc-flush-heartbeat`, when it starts tracking the first Linear folder.
Every second, the thread hands regions older than `flush-frequency` (counted
from the first unsaved write) to the shared flush pool, so a written region
reaches disk within `flush-frequency` + 1 second plus the write time, about
11 s at the default. That clock starts when the server hands the chunk to
the region file, which happens at the chunk autosave (`auto-save-interval`,
1200 ticks by default), an unload or a save, so an edit in the world can
wait longer than 11 s before it is on disk (`limitations.md`). ANVIL worlds never start it. On Horizon, storage
flush/close never fire, so the heartbeat is the only background writer.

## Save barrier and player data

`save-all flush` is a durability barrier on the Folia and CanvasMC forks and
on Horizon on every host (Paper, Folia, CanvasMC; see the table in
`commands.md`). It returns (and prints `Saved the game`) only after every
region's chunks were saved, every chunk write still queued when the last
region finished has reached its region file, and every Linear file written
by that save, or by a flush already running when it started (an eviction
close included), is renamed and its directory synced. Then it replaces
`<world>/linear-checkpoint/save-all.done` atomically with one JSON line:
`{"id":7,"startMs":...,"endMs":...,"regions":12,"failed":0}`. The id grows
by one per `save-all flush` and continues from the existing marker after a
restart. `failed` counts regions whose save threw plus Linear flush failures
recorded during the save; any value above 0 also makes the command report
failure, so a backup script should check both the id and `failed`. A plain
`save-all` (no `flush`) writes no marker. The marker is a separate file:
region files, ANVIL ones included, are not touched by it.

On the Folia and CanvasMC forks the save runs region by region on each
region's own thread: a region's chunks are saved and flushed, then that
region's players, so a player file written by `save-all` never gets ahead of
its region. With `coupled-save.enabled` off, each region thread waits for
its own forced storage flush, so a region's tick stalls for that flush
during `save-all flush` (an extra region MSPT spike; a cron job that runs it
every minute will show one per run). With coupled save on, the region
threads only queue the saves and the barrier waits off-thread instead.
Folia upstream never registered `save-all`; the Folia fork now does, and
like Folia's own autosave it leaves level and global data to the stop. On
Horizon the barrier is wired for Paper's `save-all` (players are saved a
second time after the chunk barrier, in the same order). Paper runs
`save-all` on its main thread, so the whole server tick is frozen while the
barrier waits: up to 20 s for chunk writes and staged coupled saves (below
the 60 s watchdog), then it reports failure.

| Leg / host | `save-all flush` barrier | `save-all.done` marker | Players saved after their chunks |
| --- | --- | --- | --- |
| Folia fork | yes (off-thread wait, 60 s budget) | yes | yes |
| CanvasMC fork | yes (off-thread wait, 60 s budget) | yes | yes |
| Horizon on Paper | yes (main-thread wait, 20 s budget, tick frozen) | yes | yes |
| Horizon on CanvasMC | Canvas's own forced storage flush | no | no (Canvas order) |
| Horizon on Folia | no `save-all` command | no | n/a |

The two Horizon gaps are open parity items (`save-all-barrier-region-hosts`);
use the Folia or CanvasMC fork when a backup script needs the marker.

`save-off` now also pauses the periodic player autosave (vanilla keeps
writing player files while chunk saving is off). A `save-off` that nobody
turns back on expires after `save-off-max-duration` seconds: saving turns
back on, the console logs a WARN and operators see the same line. Only
`save-off`/`save-on` (and the management API) are leased; a plugin that turns
saving off for one world with `World#setAutoSave` is left alone.

With `durable-player-data` (default true), the current `.dat` is kept as
`.dat_old` (a hard link, so it still means "the save before this one") and
the new temp file replaces `.dat` with one atomic rename, so `.dat` always
exists and is whole. Stats and advancements are replaced the same way
instead of being rewritten in place. These saves run on the tick thread
(periodic autosave, logout, `save-all`), so they do not fsync: forcing the
temp file and the folder there would add several synchronous disk flushes
per player to a tick, and that cost has not been measured on real servers
yet. The rename is atomic either way (after a crash a file is the old or the
new save, whole) and becomes durable on the filesystem's own commit (ext4:
5 s). With `coupled-save.enabled`, the staged files are written off the tick
thread and fully synced (forced temp file, then one folder sync per folder
per batch). At boot, temp files left by an
interrupted save (`<uuid>-<digits>.dat`) are moved to
`players/data/linear-quarantine/`, never deleted; when the matching `.dat` is
missing the log says the file may hold that player's newest save. Every time
a player is loaded from `.dat_old` the console logs a WARN (vanilla does that
silently). The bytes written are the vanilla bytes, which is why this is its
own setting instead of following the region format.

### Coupled save order

`coupled-save.enabled` (default `true`) ties player files to their chunks,
within a region and for saves made by the periodic capture, logout and
`save-all`. Region splits and direct writes (`stop`, `Server#savePlayers`,
`save-off`, `saveData` called off the player's thread) are not covered; see
`docs/limitations.md`. Without it, chunks and player files reach the disk on two separate clocks,
so after a host crash a player file can be newer than the chunks around it:
an item placed in a chest just before the crash is then in neither file.

With it on, every `coupled-save.interval` seconds each region (on Paper, the
whole server) runs one capture in its own tick:

1. every chunk the region holds is saved (no per-tick limit; the writes go to
   the chunk I/O thread as usual);
2. the region's players are saved, but their `.dat`, stats and advancements
   are kept in memory (staged) instead of written;
3. a background thread (`LinearMC-coupled-save`) waits until every chunk
   write that was in flight at that moment has reached its region file, runs
   the same forced storage flush as `save-all flush` (rename and folder sync
   included), and only then writes the staged files, each with a forced temp
   file, one atomic rename and a folder sync.

If that barrier fails (a region flush or chunk write failed), the player
files are held back and the barrier is retried with backoff (1 s doubling to
30 s) with a WARN; a newer capture of the same player replaces the held one.
A logout, a plugin calling `Player#saveData` on the player's own thread, and
`save-all` use the same capture, and the separate periodic player autosave
(`player-auto-save`) is skipped while the setting is on. A player who logs
back in before their staged save is written gets the staged data, not the
older file. A player who leaves a region (a teleport or portal into another
region, a respawn) triggers one extra capture of the region being left,
on that region's thread, before the player goes: the next capture in the
destination region only saves the chunks that region owns. `save-all flush`
waits for the staged saves (60 s on the forks, off-thread; 20 s on Horizon on
Paper, with the tick frozen) before it writes its marker, else it reports
failure. At `stop`, captures end first and whatever is still staged is
written after the final chunk save.

Live check: start the server with `-Dlinearmc.coupled-save.debug=true` (one
`[coupled-save-debug]` INFO line per barrier and per staged file written; off
by default) and run `scripts/coupled-order-check.py <server-dir> --seconds N`
while players play. It samples every `players/data/*.dat` write and the
region file at the player's saved position, reads the debug lines, and
exits 1 on a violation (a `.dat` whose region file does not exist, or a
staged write logged before its barrier) and 3 when no player file was
written (vacuous, never a PASS).

If a chunk write keeps failing (a full or read-only disk, a broken region
file), every staged player file stays held back and the WARN
`Coupled save: N player file(s) withheld` repeats (attempts 1, 10, 20, ...).
The log line is a WARN on the first and every 10th attempt, raised to ERROR
once the capture has been withheld for 60 s (a host crash then rolls those
players back to their last written file), and `/linear stats` shows
`coupled-withheld=N` while any capture is retrying. There is no automatic
fallback that writes the files anyway: that would break the order this
setting exists for. Fix the disk or the region, then either wait (the next successful barrier
writes them) or run `stop`, which writes everything still staged after the
final chunk save.

What this guarantees: a player file on disk is never newer than its
region's chunks, so an item placed or deposited just before a crash is at
worst duplicated, never lost. It does not cover the other direction (an item
mined or picked up just before a crash can still be lost, because a chunk can
become durable on its own after the capture), and it is not one cut across
regions: an item that moves between two regions between their captures can
be in both or neither. `coupled-save.interval` is also how far a player file
can lag behind its chunks. Saves outside a capture (the stop path, a plugin
calling `saveData` from another thread, `Server#savePlayers`, any player save
during `save-off`) are written at once as before; they replace a staged save
of the same player, never the other way round. On an ANVIL world the barrier
is the usual region file sync. Cost: one save burst per region per interval
on the region thread (chunk serialization that autosave would do later
anyway, plus in-memory player serialization); all disk work runs off the
region thread. See `limitations.md`.

Plugins are disabled before the server's final world save, so disabling the
plugin only drains what is dirty at that moment. The real stop barrier runs
after the final save and region I/O flush (`MinecraftServerMixin`, right
before `onServerExit()`), and the boot log prints `stop barrier armed`. A
clean stop without `save-all` loses nothing (`scripts/migration-smoke.sh`,
marker D).

## Example

This is one `region-format` block as it appears at the top level of a
file. The first three keys belong in the world files and the rest only in
`paper-global.yml`, so copy each group into its own file.

```yaml
region-format:
  format: LINEAR
  linear:
    compression-level: 6
    crash-on-broken-symlink: true
    flush-frequency: 10
    flush-max-threads: 1
    compression-workers: 0
    long-distance-matching: 0
    log-flush-batches: false
    implementation: V1
    grid-size: 8
    save-off-max-duration: 120
    durable-player-data: true
    coupled-save:
      enabled: true
      interval: 20
```
