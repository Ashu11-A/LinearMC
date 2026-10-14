# Configuration

This page is the single source of truth for Linear settings: where each
setting lives, its shipped default, and what happens with a bad value.
Every default and fallback below was checked against
`linear-core/config/src/net/linear/config/LinearPolicy.java` and the
shipped template in `release/region-format.yml`.

## How settings resolve

Resolution order is system property, then file, then shipped default.
A system property beats whatever is in the file, and the file beats the
built-in default. There is no live reload. Restart the server after
editing any file or changing any system property.

## Where each setting lives

World settings have a fallback and an override. The fallback lives in
`paper-world-defaults.yml` and applies to every world that does not say
otherwise. A single world overrides it in its own `paper-world.yml`.
Leaving a per-world file without a `region-format` block means that
world inherits the fallback.

Server-wide settings live only in `paper-global.yml`. They cannot be
set per world. Putting them in a world file has no effect.

## Settings

The first three settings are per-world. The rest are global-only.

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

The format setting chooses the file extension used for new writes:
`ANVIL` (`.mca`), `LINEAR` (`.linear`), or `B_LINEAR` (`.b_linear` plus a
`.b_linear.swp` swap file that travels beside the master; never delete or
move one without the other). Reads always probe every extension
(`.mca`, then `.linear`, then `.b_linear`), so changing the format never
hides existing data. Mixed worlds in one server are supported. Note the
trap: the shipped default is LINEAR, but the bad-value fallback is ANVIL,
so a misspelled format runs that world on Anvil, not on Linear.

The compression level is the zstd level used when a dirty region
flushes. Existing files need no conversion; they are rewritten at the
new level the next time they flush. The symlink setting is a data-loss
guard: with the default true, a broken symlink under a linear-family
path halts the server instead of letting the chunk system regenerate the
region as if it were empty. Set it to false only to boot for forensics,
then fix the path.

The implementation selects the on-disk writer: `V1` (single compressed
blob, the reference format), `V2`/`V3` (bucketed, DivineMC-compatible;
readers accept all versions regardless, and V2/V3 files rewrite as V1
only if the implementation is later set back). The grid size packs
V2/V3 writes into a grid of buckets (default 8, DivineMC-compatible).
Both are writers-only and global: existing files keep their layout until
rewritten. Readers never need configuration to open any version.

Flush frequency is the age gate for the deferred flush: only files that
have been dirty at least that many seconds flush on a save. It also sets
the plugin flush-heartbeat rhythm: without it, lines whose storage
flush/close paths never fire would hold writes in RAM indefinitely. Flush
threads size the shared flush pool, with 1 meaning serial. Compression
workers of 0 means single-threaded compression for that flush.
Long-distance matching of 0 means it is off. The log-flush-batches flag
stays inert for now.

File values for the format are matched exactly after trimming, so
lowercase `linear` in a file is an unknown value.

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

Pumpkin shares the `.linear` extension but migrates explicit-only:
only `/linear convert <world> --from pumpkin --to <target> --execute`
copies Pumpkin files, and startup auto-conversion never rewrites them.
A Pumpkin target ignores `--level` (fixed at 1) and defaults the grid
to 2; `v2`/`v3` targets default the grid to 8. There are no settings
for any of this: conversion takes the world's configured level and the
per-job flags above, and a convert without `--execute` only reports
what it would do.

## Command-line overrides

Four settings can be overridden from the JVM command line. The system
property wins over both the file and the default. An invalid or blank
property value is ignored and the file value stays in effect.

The properties are `-Dlinearmc.format` for the world format,
`-Dlinearmc.compression-level` for the zstd level,
`-Dlinearmc.implementation` for the writer (`V1`, `V2`, `V3`), and
`-Dlinearmc.grid-size` for the bucket grid. There are currently
no system properties for the other keys.

## Bad-value log lines

Each line below is quoted exactly as it appears in the code. The final
number or word in each line is the offending value that was supplied.

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

A boot with none of these lines is the healthy case. The first line
means the world is running on Anvil despite the intent, so fix the
spelling. The others mean the server is running on the stated fallback
while the file still holds the bad value.

## Horizon plugin difference

The Horizon plugin does not read `paper-world-defaults.yml`,
`paper-world.yml`, or `paper-global.yml`. It uses `plugins/LinearMC/config.yml`
with the same keys, plus the same system properties above, set on the
server JVM. All four overrides work there: `-Dlinearmc.format`,
`-Dlinearmc.compression-level`, `-Dlinearmc.implementation`, and
`-Dlinearmc.grid-size`.

On Horizon, durability does not depend on storage flush/close (verified
live: neither fires on that line). A repeating async heartbeat persists
age-gated files on the flush-frequency rhythm, and disabling the plugin
force-flushes everything, so a clean stop loses nothing.

## Example

One `region-format` block, as it appears at the top level of each file.
The first three keys go in the world files; the rest go only in
`paper-global.yml`. Copy each group into its target file.

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
