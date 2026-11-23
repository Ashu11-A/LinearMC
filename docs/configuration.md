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
| linear.flush-frequency | paper-global.yml | 10 seconds | Below 1 logs a warning and uses 10 |
| linear.flush-max-threads | paper-global.yml | 1 | Negative means processor count plus the value, floored at 1; zero becomes 1; no fallback log line |
| linear.compression-workers | paper-global.yml | 0 | Negative logs a warning and uses 0 (serial) |
| linear.long-distance-matching | paper-global.yml | 0 | Negative logs a warning and uses 0 (off) |
| linear.log-flush-batches | paper-global.yml | false | Currently inert; the value is accepted but changes nothing |
| linear.implementation | paper-global.yml | V1 | Unknown value logs a warning and writes V1 |
| linear.grid-size | paper-global.yml | 8 | Outside 1, 2, 4, 8, 16, 32 logs a warning and uses 8 |

On the Canvas fork, the same keys live in Canvas' own files: the per-world
ones in `config/canvas-worlds.yml` (and `<dimension>/canvas-patch.yml`)
under `region-format`, the global ones in Canvas' global configuration
under `linear` (camelCase: `flushFrequency`, `implementation`, `gridSize`,
...). Every `-Dlinearmc.*` override is named once in the boot log.

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
11 s at the default. ANVIL worlds never start it. On Horizon, storage
flush/close never fire, so the heartbeat is the only background writer.

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
```
