# Shared Canvas hub (version-agnostic)

Single implementation for every CanvasMC version leg (26.1.x, 26.2, future 26.3).
Each file here is byte-identical across all live pins and applies green on each
leg's upstream ref. Legs keep only seam adapters (see below) plus OVERLAY.md /
README.md pin strings.

Layout mirrors a canvasmc leg so staging reuses the same loops:
`overlay/{nms,paper,config}/*.patch`, `paper-server/**`, `tests/**`.
Staging order per family: hub first (check-all, apply-all), then the leg.
Shadow rule (demotion across versions): a leg file with the SAME relative
path as a hub file wins for that version; the hub copy is SKIPPED with a loud
staging NOTE (never silent). Preflight reports every shadow pair, and
MANIFEST.txt pins hub bytes, so an override can never hide. A file is either
graduated (hub owns it everywhere) or shadowed per-leg, never duplicated.

## Graduated contents (47 files, ~284 KB)
- `overlay/nms/`: 16 verbatim patches (chunk system, storage, server, upgrader
  scaffolding except the 3 adapters).
- `overlay/paper/PaperWorldLoader.java.patch`, `overlay/config/...WorldConfig...patch`.
- `paper-server/`: 9 identical new files (NOT LinearStartupConversion.java).
- `tests/`: all 20 test files (no drifted-API references; verified by grep for
  regionCache/dimensionKey/mspt).

## Per-leg adapters (stay in versions/servers/<ver>/canvasmc/)
- `overlay/nms/..._RegionFileStorage.java.patch` (upstream visibility narrowing)
- `overlay/nms/..._RegionShutdownThread.java.patch` (disconnect-line context)
- `overlay/nms/..._RegionStorageUpgrader.java.patch` (comment-only today; graduate
  once normalized to one header and --check-verified on every pin)
- `overlay/paper/..._PaperCommands.java.patch` (dropped mspt neighbor)
- `overlay/config/..._GlobalConfiguration.java.patch` (anchor drift)
- `paper-server/.../linear/LinearStartupConversion.java` (removed helper inlined)

## Graduation rule (leg file -> hub)
Promote iff ALL hold:
1. Byte-identical basename on every leg that uses it.
2. `git apply --check` + apply green on EACH pinned ref.
3. `LinearNmsTestSuite` + Anvil opt-out boot green on each leg.
4. Anvil-path audit empty (changes behind LINEAR flag only).

## Demotion trigger
Any leg needs mapping drift or a leg-only hunk: that leg carries an adapter
with the same relative path, which SHADOWS the hub copy for that version
(staging skips hub with a loud NOTE; preflight reports the pair below).
The hub copy stays for the other legs. If NO leg can use the hub copy
anymore, delete it here and drop its MANIFEST.txt line.

Canonical source for copies: the 26.1.x working tree at promotion time.
Three hub files carry in-flight working-tree evolution vs the last commit,
byte-identical on both legs at promotion (verified by cmp on all pairs):
`paper-server/.../command/CommandLinear.java` (explicit any-to-any convert,
single-flight, B_LINEAR counting),
`paper-server/.../configuration/type/RegionFileFormat.java` (B_LINEAR mapping),
and `tests/.../RegionStorageUpgraderScanTest.java` (updated in the hub to
the triple-extension REGEX form after promotion; no leg copies remain to
compare). The two paper-server files graduate because both legs agree
byte-for-byte; the feature deltas themselves are reviewed with their own
tests, not with this move.
`MANIFEST.txt` beside this file lists every shared file + sha256.
