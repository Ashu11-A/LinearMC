---
name: linearmc-test
description: Choose and run the right LinearMC tests - core JUnit, fork Linear suites, Horizon mixin/unit tests per dev bundle, mixin site audit, and the live Paper/Folia/Canvas server matrix with migration scenarios. Use before claiming any change works or when validating a release jar.
---

# Testing LinearMC

## Test pyramid (cheapest first)

| Layer | Command | Covers |
| --- | --- | --- |
| Core | `CORE_LIBS=$PWD/proof/corelibs linear-core/build.sh` | NMS gate plus every core suite (~10 s) |
| Horizon | `cd versions/plugins/horizon/1.0.0 && ./gradlew clean test -PdevBundle=<pin>` for each `versions/plugins/horizon/upstream.horizon-*.properties` (`HORIZON_DEV_BUNDLE`) | Plugin logic plus `MixinDescriptorsTest` (selectors/targets exist on that bundle) |
| Static gates | `scripts/preflight.sh`, `scripts/parity-check.sh`, `scripts/lint-java.py` | Inventory, pins, hunk determinism, patch parse, config-key, parity contract and Java import lint |
| Fork suites | `scripts/validate-all.sh [--jars]` (stages, compiles and runs `LinearNmsTestSuite` on both forks, one at a time; needs ~5 GB free RAM) | Folia and Canvas Linear suites, optional paperclip jars for live tests |
| Mixin sites | `scripts/audit-mixin-sites.sh <server.jar>` | Every `new RegionFile` site in the real host class is redirected |
| Live smoke | `scripts/server-smoke.sh <dir> <loadgen\|forceload\|idle> <sec> [horizon] [format]` | Boot, write path, flush, stop on real servers |
| Live migration | `scripts/migration-smoke.sh <template-dir> <server.jar\|horizon.jar>` | Pre-existing `.mca` world → LINEAR without regeneration, new `.linear` region, clean stop without `save-all` (markers A–D), final stop drain, live `/linear convert` never deleting an open `.mca` (markers E–G, with a control region proving the job ran) |
| Live unload flush | `scripts/unload-smoke.sh <template-dir> <server.jar\|horizon.jar>` | After `forceload remove` + flush-frequency wait, both region `.linear` files are on disk (fresh mtime, header+footer magic) while the server still runs; restart keeps markers U/V |

## Live matrix (max ~5 min per server)

Server directories go under `tests/` (git-ignored). For each host:
1. Paper + Horizon wrapper + plugin.
2. Folia + Horizon + plugin.
3. Canvas + Horizon + plugin.
4. Folia fork jar.
5. Canvas fork jar.

Load with `~/Documents/GitHub/folia-loadgen` on Folia/Canvas, `forceload` on Paper.

Scenarios. Fresh worlds alone are not enough:
- **Fresh world, LINEAR** (`server-smoke.sh`): `.linear` files appear and the `.mca` count is 0.
- **Migration and clean stop** (`migration-smoke.sh`): run it for every host template. It must PASS. A FAIL with `.mca`+`.linear` siblings means the read path missed existing files. The `v1.1.4-release.1` plugin jar reproduces that failure.
- **Revert**: `/linear convert <world> --to-mca --execute` converts `region/`, `entities/` and `poi/`.
- **Permissions**: `/linear stats` with only `linear.command.linear`, only `linear.command.linearstats`, and as op.

Console notes: Paper logs `say` output, Canvas does not. Prove state through command feedback such as `Changed the block at …`, as `migration-smoke.sh` does. Folia may log a harmless `captureTreeGeneration` NPE for console `setblock`.

Read-back without a server: write a small JUnit or `jshell` snippet using `net.linear.LinearRegionFile` (there is no CLI entry point). Record results in `tests/RESULTS.md` and summarize them in the PR.

## Rules

- **A test runs to completion; never kill a server as a test step.** No SIGKILL/`kill` to simulate a crash and no `timeout` wrapper cutting a run short. Stop only with the `stop` console command and wait for exit (`scripts/lib/server.sh` `server_stop`). Killing is allowed only for cleanup when the server is already hung and cannot process `stop`: `server_stop` then sends TERM, then `-9`, marks the test FAILED (`SERVER_STUCK`) and the report must say so. `kill -0` liveness checks are fine.
- Every JVM runs with `-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError` (dump in `<run dir>/oom` or `$OOM_DIR`); an OOM exits with code 3 and is reported as FAIL with the dump path. Delete large `.hprof` files once their histogram is recorded (disk is tight).
- `server-smoke.sh ... loadgen` refuses to start without a non-empty `plugins/LoadGen/config.yml`; copy templates atomically (`cp -a` to a temp dir, then `mv`).
- Max 2 servers at once.
- Durability is proved while the server runs (unload flush) or after a clean `stop`, never by a crash/kill.
- A test that passes on a fresh world proves nothing about migration paths.
- Never skip a dev bundle. Mixin targets drift per MC version.
- Report failures with their output. Never mark a parity entry `supported` on unit tests alone when the behaviour is a save, stop or migration path; note "live pending" in `test`.
