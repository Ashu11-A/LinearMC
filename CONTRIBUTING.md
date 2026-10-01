# Contributing

This repository is a patch supplement, not a full fork checkout. Changes here
are patch files, test sources and tooling that get staged into a pinned Folia
clone at build time.

## Run preflight before pushing

A CI build takes about ten minutes. Most failures so far were staging mistakes
that a local check catches in seconds.

```bash
scripts/preflight.sh            # inventory, hunk determinism, patch parsing, workflow checks
scripts/preflight.sh --compile  # the above, plus fork compileJava
scripts/preflight.sh --tests    # the above, plus the Linear suites
```

- Default `--folia-dir` is `/tmp/folia-linear/folia`. Pass your own with
  `scripts/preflight.sh --folia-dir /path/to/folia`.
- `--compile` and `--tests` run Gradle in that directory and need network
  access. The plain run does not.
- Exit code 0 means push-ready. Anything else means fix it locally first.
- The CI `static` job runs the same checks; `unit` and `config-check` only
  run after it passes.

## Patch conventions

- `minecraft-*.patch` goes to `folia-server/minecraft-patches/features/`,
  `paper-*.patch` to `folia-server/paper-patches/features/`. Numbering continues
  the supplement's own sequence (`minecraft-0009`..`0017`, `paper-0008`..`0011`
  in v1.0.0), so a new NMS patch after `minecraft-0017` is `minecraft-0018`.
  The module map is `linear-core/README.md` (per-leg staging notes live next
  to each leg, e.g. `versions/servers/26.1.x/canvasmc/OVERLAY.md`).
- Filenames are `<area>-<NNNN>-<Subject>.patch`, matching the patch's own
  `Subject:` line.
- Patches apply through paperweight, which uses `git am`. `git am` rejects hunk
  header counts that `git apply` tolerates. Count every body line: `old` is
  space plus minus lines, `new` is space plus plus lines, and a blank context
  line must be a single space, not an empty line. Two CI failures came from
  exactly this.
- Context has to be real context from the target file, not assumed context.
  Several patch headers carry `BASE-TREE ASSUMPTIONS` notes from when the base
  checkout was unavailable; if you touch one of those hunks, verify against the
  actual file.
- Keep the Anvil path byte-identical unless the change is specifically about
  Anvil. Behaviour changes belong behind the `LINEAR` format flag.

## The build-file hunk

`versions/<line>/folia/build-hunk.py` splices two things into Folia's own
`folia-server/build.gradle.kts.patch`: the `net/linear` test source
directory, and the zstd-jni dependency.

- That file uses the paperweight hunk convention, which plain `git apply` cannot
  parse, so the wiring is done by script rather than by a patch.
- The script is deterministic. Preflight proves it by running it against a
  pristine `HEAD` copy and diffing the result against the working file.
- It aborts on upstream drift rather than producing a plausible but wrong file.

## Porting to a new MC version

Each supported version line lives in its own `versions/<branch>/` folder with
its own upstream pins (`upstream.properties` for Folia, `upstream.<server>.properties`
for other servers), per-server legs (`folia/`, `canvas/`, each with `patches/`,
`tests/`, `build-hunk.py`), and shared byte-identical patches in `common/`.
Start a new line with the script, never by hand-copying:

```bash
scripts/new-version.sh 26.2.x ver/26.2.x <ref>   # copies newest folder, re-pins, runs applyAllPatches
scripts/new-version.sh --dry-run 26.2.x ver/26.2.x <ref>  # plan only, changes nothing
scripts/new-version.sh --help
```

Porting checklist:

- Same patch name and number per feature across folders. If a fix lands as
  `minecraft-0013` in `versions/26.1.x/folia/patches/`, the same feature is
  `minecraft-0013` in every other `versions/*/folia/patches/` folder, so diffs
  between lines stay reviewable. Renumbering one line orphans the mapping.
- `minecraft-*.patch` goes to `minecraft-patches/features/`, `paper-*.patch`
  to `paper-patches/features/`; the `<area>-<NNNN>-<Subject>.patch` naming and
  hunk-count rules from Patch conventions apply verbatim in every folder.
- `upstream.properties` carries all four keys (`FOLIA_REPO`, `FOLIA_BRANCH`,
  `FOLIA_REF`, `MC_VERSION`). It is the single source of truth; the workflow
  pins and `release/RELEASE.md` section 2 mirror it, they do not replace it.
- `new-version.sh` already ran `applyAllPatches` against the new pin, so
  rebase conflicts surfaced at copy time. Port each rejected hunk to the new
  base, keeping behaviour identical unless the new base forces a change.
- Run `scripts/preflight.sh` (it checks every `versions/*/` folder:
  inventory, property completeness, hunk determinism, patch parsing, and the
  three inherited-bug grep gates), then build the new line explicitly:
  `scripts/build.sh --mc 26.2.x`. Plain `scripts/build.sh` builds all lines.

## Tests

Test sources in `versions/<line>/folia/tests/` are copied into
`folia-server/src/test/java/net/linear/` during staging.

- `LinearNmsTestSuite.java` is the suite wiring. The fork's Gradle task only
  runs classes matching `**TestSuite`, so a test not reachable from the suite
  never executes.
- New Linear tests need to be added to the suite, not just dropped in the
  directory.
- CI runs `./gradlew :folia-server:test --tests "net.linear.LinearNmsTestSuite"`
  as a separate gate after the full build.

## CI

Two workflows. `test.yml` runs on every push/PR: `static` (YAML/shell validity,
no-compile preflight gates), `unit` (staging, `applyAllPatches`, fork compile,
`LinearNmsTestSuite`), `config-check`. `build.yml` runs on `v*` tags,
published releases and manual dispatch: `discover` (version matrix), `build`
(pinned clone, `applyAllPatches`, full build, paperclip jar, Linear suites,
ANVIL + LINEAR boot smokes), `release` (jar + sha256 attached to the GitHub
release on tags).

Two self-checks exist because of past breakage and are enforced in both the
workflow and `preflight.sh`:

- No over-specific jar globs. Use `folia-paperclip-*.jar`; the suffix has
  changed before.
- Every `gh release` invocation carries `--repo`.

## Cutting a release

1. Preflight green, then push to `main`.
2. Tag `v1.x.y` and push the tag.
3. The tag push triggers `build.yml`, which attaches
   `folia-linear-<mcversion>-<project>.jar` and its sha256 to the GitHub
   release (one pair per supported version line). `v1.0.0` produces
   `folia-linear-26.1.2-1.0.0.jar`.
Update `CHANGELOG.md` in the same change that introduces the behaviour, not
at tag time.

## Documentation

- `README.md` is the entry point and should stay usable end to end without
  opening anything else.
- `docs/` holds the detail: concepts, architecture, configuration, commands,
  observability, performance, limitations, FAQ.
- `release/` holds operator-facing material and ships with the release package.
- Claims need a source in the repository: a patch, a test, a script, a log
  string or a measurement. If something has not been measured, say so rather
  than estimating.
