# Release package

Ship posture: Linear default, Anvil opt-out per world. New worlds write
`.linear` unless an operator pins a world back with
`region-format.format: ANVIL` in that world's `paper-world.yml`.
Dual-read is active from the first boot, so `.mca` and `.linear` coexist.

The server jar is not copied into this directory. It is referenced by path and
sha256 below; verify the checksum before deploying it.

## 1. Contents

| Path | What |
| --- | --- |
| `RELEASE.md` | This file: pins, install, dry-run results |
| `OPERATOR-NOTES.md` | Posture, opt-out procedure, monitoring, limitations |
| `region-format.yml` | Comment-free snippet covering all blocks: ship default `LINEAR` at level `6` with symlink guard on, per-world `ANVIL` opt-out override, and the global flush keys |

Referenced but not included:

| Artifact | Where |
| --- | --- |
| Paperclip jar | Built locally, or `folia-linear-<mcversion>-<project>.jar` from the GitHub release for a project `v*` tag |
| Tree converter | `convert.py`, modes `mca2linear`, `linear2mca`, `verify`. Not part of this repository |

## 2. Version pins

Single source of truth: `versions/<line>/upstream.properties` (keys
`FOLIA_REPO`, `FOLIA_BRANCH`, `FOLIA_REF`, `MC_VERSION`). The table below is
the 26.1.x instantiation of that file; the workflow pins mirror it.

| Component | Pin |
| --- | --- |
| Folia base | `ver/26.1.x` at `62dc0f257a4f5de1ef2eae8cf1627156a769c67f` |
| Minecraft | `26.1.2` |
| Java, build and runtime | `25` (verified on Temurin 25+36 LTS) |
| zstd-jni | `1.5.6-8` |
| Config defaults | `format: LINEAR`, `compression-level: 6`, `crash-on-broken-symlink: true`, `flush-frequency: 10`, `flush-max-threads: 1` |

A local reference build produced a 60,509,601 B paperclip jar with
sha256 `3d4713a0c78a68d01f33cc9f44685cdfbbcd1b508cb1033cd4366f62b3cd618c`,
reproducible byte for byte across rebuilds. A CI release asset is a different
artifact and therefore has a different sha (see the v1.0.0 release). Always check the sha of whichever jar
you actually deployed.

## 3. Install on a clean node

```bash
# 0. Prereqs: Java 25, python3 with pyzstd if you will use the converter,
#    and roughly 2.5x the world size free.
java -version

# 1. Stage the server directory.
export SRV=/opt/folia-linear/node1
mkdir -p "$SRV"
cp <paperclip jar> "$SRV/"
(cd "$SRV" && sha256sum -c - <<<"<expected sha>  <jar name>")   # must print OK

# 2. First boot to generate stock configs, then stop.
#    cd "$SRV" && java -jar <jar> --nogui
#    Accept the EULA, stop the server.

# 3. Lay the region-format defaults over the generated configs.
#    paper-world-defaults.yml  <- the paper-world-defaults block of region-format.yml (top level)
#    paper-global.yml          <- the paper-global block of region-format.yml (top level)
#    Per-world paper-world.yml: leave the block out to inherit LINEAR; paste the
#    per-world block only to pin a world back to ANVIL.
#    Validate: python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" <file>

# 4. Boot again and confirm no [region-format] lines appear, then pin back at
#    most one world per OPERATOR-NOTES.md section 2 if needed.

# 5. Rollback readiness, before any flip:
mkdir -p /backup/<world>
cp -a <world>/region <world>/poi <world>/entities /backup/<world>/
date -u +%FT%TZ > /backup/<world>/BACKUP_VERIFIED
#    Never revert without a snapshot like this: it must contain BACKUP_VERIFIED
#    plus at least one region/ tree.
```

## 4. Knob defaults

| Key | Default | Notes |
| --- | --- | --- |
| `region-format.format` | `LINEAR` | Unknown string logs an error and falls back to `ANVIL` |
| `region-format.linear.compression-level` | `6` | Outside 1-22 logs an error and falls back to `6`, clamped in three places |
| `region-format.linear.crash-on-broken-symlink` | `true` | A broken `.linear` symlink halts the server by design |
| `region-format.linear.flush-frequency` | `10` | Age-based flush gate. Below 1 logs an error and falls back to `10` |
| `region-format.linear.flush-max-threads` | `1` | Shared flush pool size; `<=1` keeps the serial loop |
| `region-format.linear.compression-workers` | `0` | zstd workers per flush; `0` = serial |
| `region-format.linear.long-distance-matching` | `0` | zstd LDM windowLog; `0` = off |
| `region-format.linear.log-flush-batches` | `false` | Inert; warn-only logging |

The timing counters and `/linearstats` add no config keys.

Full reference: [../docs/configuration.md](../docs/configuration.md).

## 5. Dry-run results

Executed against the live files. The server itself was not booted by
this runbook.

- **Config template renders.** Each block of the single snippet parses with
  `yaml.safe_load`: pass. All keys present with ship defaults.
- **Converter smoke.** `convert.py --help`: pass. Modes `mca2linear`,
  `linear2mca`, `verify`; flags `-t/--threads`, `-c/--compression-level`.
- **Jar reference check.** sha256 matched the expected value: pass.

Abridged transcript:

```console
$ python3 convert.py --help
usage: convert.py [-h] [-t THREADS] [-c COMPRESSION_LEVEL]
                  {mca2linear,linear2mca,verify} src dst
```

## 6. Per-version builds

The supplement is version-scoped: each supported line lives in
`versions/<branch>/` with its own pin (`upstream.properties`), `patches/`,
`tests/` and `build-hunk.py`.

```bash
scripts/build.sh --mc 26.1.x   # this release line
scripts/build.sh               # every folder in versions/
```

Each line produces its own paperclip jar. Release tags carry the **project
version only** (`v1.0.0`); one release attaches one jar + `.sha256` per
supported version line, named `folia-linear-<mcversion>-<project>.jar` (so a
`v1.0.0` release containing `26.1.x` at MC `26.1.2` ships
`folia-linear-26.1.2-1.0.0.jar` + `.sha256`; a future `26.2.x` line at MC
`26.2.1` would add `folia-linear-26.2.1-1.0.0.jar` + `.sha256` to the same
release). Verify the sha256 shipped alongside the jar before deploying it.

To release only some folders (a fix that applies to one line), dispatch the
workflow manually on the tag with the `versions` input:

```bash
gh workflow run build.yml --ref v1.0.0 -f versions=26.1.x
```

Pushed tags always release every version.
