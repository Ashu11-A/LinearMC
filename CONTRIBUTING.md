# Contributing

This repository holds a patch supplement and the shared core, not a full fork
checkout. Changes here are patch files, overlay files, test sources and tooling
that get staged into a pinned upstream clone at build time.

## Run preflight before pushing

A CI build takes about ten minutes, and most past failures were staging
mistakes that a local check catches in seconds.

```bash
scripts/preflight.sh            # inventory, hunk determinism, patch parsing, workflow checks
scripts/preflight.sh --compile  # the above, plus fork compileJava
scripts/preflight.sh --tests    # the above, plus the Linear suites
```

- Default `--folia-dir` is `proof/fork-folia` (create it with
  `scripts/stage.sh folia`), else the legacy `/tmp/folia-linear/folia`.
  Pass your own with `scripts/preflight.sh --folia-dir /path/to/folia`.
- `--compile` and `--tests` run Gradle in that directory and need network
  access. The plain run does not.
- Exit code 0 means you can push. Any other code means fix it locally first.
- The CI `static` job runs the same checks, and `unit` and `config-check` only
  run after it passes.

## Patch conventions

- `minecraft-*.patch` goes to `folia-server/minecraft-patches/features/`,
  `paper-*.patch` to `folia-server/paper-patches/features/`. Numbering continues
  the supplement's own sequence (`minecraft-0009`..`0017`, `paper-0008`..`0011`
  in v1.0.0), so a new NMS patch after `minecraft-0017` is `minecraft-0018`.
  The module map is `linear-core/README.md` (per-leg staging notes live next
  to each leg, e.g. `versions/servers/26.3/canvasmc/OVERLAY.md`).
- Filenames are `<area>-<NNNN>-<Subject>.patch`, matching the patch's own
  `Subject:` line.
- Patches apply through paperweight, which uses `git am`. `git am` rejects hunk
  header counts that `git apply` tolerates. Count every body line: `old` is
  space plus minus lines, `new` is space plus plus lines, and a blank context
  line must be a single space, not an empty line. Two CI failures came from
  exactly this.
- Context lines must be copied from the target file. Several patch headers
  carry `BASE-TREE ASSUMPTIONS` notes from a time when the base checkout was
  unavailable; if you touch one of those hunks, check it against the actual
  file.
- Keep the Anvil path byte-identical unless the change is specifically about
  Anvil. Behaviour changes belong behind the `LINEAR` format flag.

## The build-file hunk

`versions/servers/<line>/folia/build-hunk.py` splices three things into
Folia's own `folia-server/build.gradle.kts.patch`: the `net/linear` test
source directory, the zstd-jni dependency, and the OpenHFT XXH64 dependency.
`scripts/stage.sh canvas` wires the same two dependencies into the
post-apply `canvas-server/build.gradle.kts`; the Horizon plugin JiJ-embeds
them from its own build file.

- That file uses the paperweight hunk convention, which plain `git apply` cannot
  parse, so a script does the wiring instead of a patch.
- The script is deterministic. Preflight runs it against a pristine `HEAD` copy
  and diffs the result against the working file.
- If upstream has drifted, it aborts instead of producing a file that looks
  right but isn't.

## Porting to a new MC version

Each supported version line lives in its own `versions/servers/<line>/` folder
with its own upstream pins (`upstream.properties` for Folia,
`upstream.canvas.properties` for Canvas), per-server legs (`folia/`,
`canvasmc/`, each with `patches/` or `overlay/`, `tests/`, `build-hunk.py`),
and shared byte-identical patches in `common/`. The active matrix is Folia
26.3 + CanvasMC 26.3 + Horizon 1.0.0 (26.1-26.3); removed legs are archived
under `versions/servers/removed/` and must not be re-added without porting.
Start a new line with the script; never copy folders by hand:

```bash
scripts/new-version.sh 26.4.x ver/26.4.x <ref>   # copies newest Folia folder, re-pins, runs applyAllPatches
scripts/new-version.sh --dry-run 26.4.x ver/26.4.x <ref>  # plan only, changes nothing
scripts/new-version.sh --help
```

Porting checklist:

- Same patch name and number per feature across folders. If a fix lands as
  `minecraft-0013` in `versions/servers/26.3/folia/patches/`, the same
  feature keeps that number in every other supported
  `versions/servers/*/folia/patches/` folder, so diffs between lines stay
  reviewable. Renumbering in one line breaks that mapping.
- `minecraft-*.patch` goes to `minecraft-patches/features/`, `paper-*.patch`
  to `paper-patches/features/`; the `<area>-<NNNN>-<Subject>.patch` naming and
  hunk-count rules from Patch conventions apply verbatim in every folder.
- `upstream.properties` carries `FOLIA_REPO`, `FOLIA_BRANCH`, `FOLIA_REF`,
  `FOLIA_BUILD`, `MC_VERSION`; `upstream.canvas.properties` carries the
  `CANVAS_*` equivalents plus `CANVAS_BUILD`. They are the single source of
  truth; the workflow pins and `release/RELEASE.md` section 2 mirror them.
- `new-version.sh` has already run `applyAllPatches` against the new pin, so
  rebase conflicts show up at copy time. Port each rejected hunk to the new
  base and keep behaviour identical unless the new base forces a change.
- Run `scripts/preflight.sh` (it checks every `versions/servers/*/` line:
  inventory, property/build-number completeness, hunk determinism, patch
  parsing, and the three inherited-bug grep gates), then build the new Folia
  line explicitly: `scripts/build.sh --mc 26.4.x`. Plain `scripts/build.sh`
  builds all Folia lines and skips Canvas-only lines.

## Leg parity

A new or changed player-facing surface (command grammar, flags,
permissions, config keys with defaults, `linear$` seams) must land on the
Folia patches, the Canvas overlay and the Horizon plugin in the same change. The machine-readable contract is
`parity/capabilities.yml`, with one entry per capability and leg plus
evidence and tests. `scripts/parity-check.sh` validates it on every push, and
its `--release` mode (no `gap` allowed) must pass before the release upload. Data-path changes
(open, save, stop, convert) also need a live `scripts/migration-smoke.sh`
PASS on the affected hosts. The Horizon `plugins/LinearMC/config.yml` template
(`versions/plugins/horizon/1.0.0/src/plugin/resources/config.yml`) must stay
key-identical with the `docs/configuration.md` settings table. The
plugin-internal version (`paper-plugin.yml`, `horizon.plugin.json`,
`build.gradle.kts`) must follow the project tag; a `v1.1.x` tag carrying a
`1.0.0` plugin version is drift. `scripts/preflight.sh` enforces the
config-key and seam halves of this rule.

## Tests

Test sources in `versions/<line>/folia/tests/` are copied into
`folia-server/src/test/java/net/linear/` during staging.

- `LinearNmsTestSuite.java` is the suite wiring. The fork's Gradle task only
  runs classes matching `**TestSuite`, so a test not reachable from the suite
  never executes.
- Add every new Linear test to the suite; a file dropped in the directory
  alone never runs.
- CI runs `./gradlew :folia-server:test --tests "net.linear.LinearNmsTestSuite"`
  as a separate gate after the full build.

## CI

There are two workflows. `test.yml` runs on every push and PR: `static` (YAML/shell validity,
no-compile preflight gates), `unit` (staging, `applyAllPatches`, fork compile,
`LinearNmsTestSuite`), `config-check`. `build.yml` runs on `v*` tags,
published releases and manual dispatch: `discover` (version matrix), `build`
(pinned clone, `applyAllPatches`, full build, paperclip jar, Linear suites,
ANVIL + LINEAR boot smokes), `release` (jars attached to the GitHub
release on tags).

Two checks exist because of past breakage, and both the workflow and
`preflight.sh` enforce them:

- No over-specific jar globs. Use `folia-paperclip-*.jar`, because the suffix
  has changed before.
- Every `gh release` invocation carries `--repo`.

## Cutting a release

Tag scheme:

- New release tags use `vX.Y.Z-release.N`, starting at N = 1
  (`v1.1.4-release.1` is the first).
- Historical `v1.0.0`..`v1.1.3` tags stay exactly as they were. Do not
  create aliases for them, move them or delete them.
- There is no new plain `vX.Y.Z` tag; the release tag is always
  `vX.Y.Z-release.N`.

1. Preflight green, then push to `main`.
2. Verify the pin files carry `<PREFIX>_BUILD` (Folia/Canvas) and
   `HORIZON_BUILD` (Horizon). The release body and asset suffix use them.
3. Tag `vX.Y.Z-release.N`, and publish the GitHub release.
4. `build.yml` attaches one jar per matrix leg:
   fork legs as
   `linearmc-<server>-<mcversion>-<project>+build.<N>.jar` (e.g.
   `linearmc-folia-26.3-1.3.0+build.8.jar`), the Horizon plugin as
   `linearmc-horizon-26.1-26.3-<project>+build.<N>.jar` (one generic jar
   for Paper/Folia/CanvasMC 26.1-26.3). The `#N` form appears in the release
   body, because GitHub rewrites `#` in asset filenames.
5. The `release-notes` job injects/refreshes the upstream build table in the
   release body, so every release names the exact server build it shipped.

Supported matrix: Folia 26.3, CanvasMC 26.3, Horizon plugin for 26.1-26.3.
Unsupported server legs are archived under `versions/servers/removed/`; do
not add them back to the build matrix without re-validating their patches.

Update `CHANGELOG.md` in the same change that introduces the behaviour, not
when you tag.

## Documentation

- `README.md` is the entry point, and a reader should be able to install and
  run LinearMC from it alone.
- `docs/` holds the detail: concepts, architecture, configuration, commands,
  observability, performance, limitations, FAQ.
- `release/` holds operator-facing material and ships with the release package.
- Every claim needs a source in the repository: a patch, a test, a script, a
  log string or a measurement. If something has not been measured, say so
  instead of estimating.
