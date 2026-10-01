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

The first three settings are per-world. The last five are global-only.

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

The format setting chooses the file extension used for new writes.
Reads always probe the old extension first, so changing it never hides
existing data. Mixed worlds in one server, some on LINEAR and some on
ANVIL, are supported. Note the trap: the shipped default is LINEAR, but
the bad-value fallback is ANVIL, so a misspelled format runs that world
on Anvil, not on Linear.

The compression level is the zstd level used when a dirty region
flushes. Existing files need no conversion; they are rewritten at the
new level the next time they flush. The symlink setting is a data-loss
guard: with the default true, a broken symlink under a Linear path
halts the server instead of letting the chunk system regenerate the
region as if it were empty. Set it to false only to boot for forensics,
then fix the path.

Flush frequency is the age gate for the deferred flush: only files that
have been dirty at least that many seconds flush on a save. Flush
threads size the shared flush pool, with 1 meaning serial. Compression
workers of 0 means single-threaded compression for that flush.
Long-distance matching of 0 means it is off. The log-flush-batches flag
stays inert for now.

File values for the format are matched exactly after trimming, so
lowercase `linear` in a file is an unknown value.

## Command-line overrides

Two settings can be overridden from the JVM command line. The system
property wins over both the file and the default. An invalid or blank
property value is ignored and the file value stays in effect.

The properties are `-Dlinearmc.format` for the world format and
`-Dlinearmc.compression-level` for the zstd level. There are currently
no system properties for the other keys.

## Bad-value log lines

Each line below is quoted exactly as it appears in the code. The final
number or word in each line is the offending value that was supplied.

> [region-format] Unknown region format, expected ANVIL or LINEAR. Falling back to ANVIL.
>
> [region-format] linear.compression-level must be 1-22, got `<value>`. Falling back to 6.
>
> [region-format] linear.flush-frequency must be >= 1, got `<value>`. Falling back to 10.
>
> [region-format] linear.compression-workers must be >= 0, got `<value>`. Falling back to 0.
>
> [region-format] linear.long-distance-matching must be >= 0, got `<value>`. Falling back to 0.

A boot with none of these lines is the healthy case. The first line
means the world is running on Anvil despite the intent, so fix the
spelling. The others mean the server is running on the stated fallback
while the file still holds the bad value.

## Horizon plugin difference

The Horizon plugin does not read `paper-world-defaults.yml`,
`paper-world.yml`, or `paper-global.yml`. It uses the same system
properties above, set on the server JVM. The supported override there
is `-Dlinearmc.format`, with `-Dlinearmc.compression-level` sharing the
same file-beats-default fallback when the property is absent or invalid.

## Example

One `region-format` block, as it appears at the top level of each file.
The first three keys go in the world files; the last five go only in
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
```
