#!/usr/bin/env python3
"""Provenance record (Phase 1b): how minecraft-0018 + paper-0012 were built.

Method (NOT re-runnable blindly — it consumes a live decoupled fork tree):
1. Old net/linear states: cumulative `--include net/linear/*` apply of the old
   stack (see scripts/stage-core.sh) into a scratch repo.
2. Old adapter states: pristine upstream files (paperweight
   `taskCache/runFoliaSetup` cache) + old stack applied per-file in filename
   order with `git apply --include=<path>`.
3. New states: the decoupled fork tree (post-suite-green) + supplement src/tests.
4. 0018/paper-0012 hunks: `git diff --no-index` old->new per file, headers
   normalized to `a/<path> b/<path>`, `index` lines stripped.
5. Proof: fresh clone + full new stack + compile + 119-test suite green
   (docs/refactor/03-folia/TEST-REPORT.md Phase 1b entry).

Kept as documentation; the proof (re-application) is the authority, not this file.
"""
print(__doc__)
