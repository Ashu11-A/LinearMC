---
name: linearmc-core-dev
description: Change linear-core (format, codec, config, flush, convert, command modules) safely. Use for any edit under linear-core/, new region-format versions, codec or flush behaviour, conversion pipeline, command parser/queue/stats, or when moving duplicated leg logic into the core.
---

# linear-core development

## Module boundaries (dependency order)

`format` (names, probes, policy enums; JDK only) → `codec` (zstd/lz4, V1/V2/V3/B_LINEAR/Pumpkin, buffers) → `config` → `flush` (coordinator, fan-out, timings) → `convert` (scan, pipeline, routing, validation) → `command` (parser, queue, runner, stats views).

- No NMS, Bukkit or Paper imports anywhere: `linear-core/build.sh` runs a strict grep gate.
- Host-specific behaviour enters through seams (`linear$set*Supplier`, `FlushListener`, `CommandSender`, sinks), never through imports.
- Logic that two legs implement separately belongs here. Examples: `LinearStorageProbe.resolveFileName`, `LinearFolderNames.STORAGE_FOLDERS`.

## Rules

1. Wire formats are normative: `docs/linear-format-requirements.md`. Never change bytes of an existing version. A new layout gets a new version byte, a detector rule and converter routes.
2. Readers accept every version. Writers write the configured one.
3. Hot path (per chunk read/write/flush): no per-call allocation of large buffers or zstd streams/contexts. Reuse them per thread or per file (`docs/performance.md`; SectorTool `ZstdCtxManager` in `proof/refs/SectorTool` is the model).
4. Writes are atomic: temp file, fsync, `ATOMIC_MOVE`. Never weaken this.
5. Every public behaviour gets a JUnit 4 test in `<module>/tests/net/linear/...`. `build.sh` discovers `*Test.java` automatically.

## Build and test

```bash
CORE_LIBS=$PWD/proof/corelibs linear-core/build.sh   # NMS gate + compile + all suites
```

- Dependency pins: `linear-core/*/build.gradle.kts`, mirrored in `linear-core/build.sh`, Horizon `build.gradle.kts`, Folia `build-hunk.py` and `scripts/stage.sh` (Canvas deps). Change all of them together until a single version catalog lands.
- Horizon consumes the core through `includeBuild`. After a core change, also run the Horizon tests (skill `linearmc-test`).
- The forks copy core sources at staging (`scripts/stage.sh`, which CI also calls). Compile and test both forks locally with `scripts/validate-all.sh`.

## Done means

- Core suites green, and the Horizon suite green on all three dev bundles.
- The parity contract is updated when a capability changes (skill `linearmc-parity`).
- Reviewed with skill `linearmc-review`.
