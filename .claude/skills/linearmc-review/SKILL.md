---
name: linearmc-review
description: Expert review of a LinearMC change with a 0-10 score per dimension backed by file:line evidence, iterated until 10. Use after implementing anything non-trivial, before merging or releasing, or when asked to review a diff.
---

# Expert review loop (ai-memory implementation rule)

Research → implement → **review (0–10, evidence-backed)** → fix every flagged issue → re-review until each dimension scores 10 → extensive tests (skill `linearmc-test`).

Use at most 3 parallel reviewer sub-agents. Prompts are in English, and each reviewer gets the diff (`git diff main...HEAD`), not your conclusions.

## Dimensions

| Reviewer | Scores | Must check |
| --- | --- | --- |
| Data safety | 0–10 | Read paths find every existing format; atomic writes; stop/save durability; no negative-cache poisoning; revert path; crash windows |
| Leg parity | 0–10 | Same behaviour on Folia, Canvas and Horizon (all 3 hosts); `parity/capabilities.yml` updated; `scripts/parity-check.sh` green |
| Core and modularity | 0–10 | Logic in `linear-core`, not duplicated in legs; NMS-free core; seams instead of imports; tests per public behaviour |
| Upstream robustness | 0–10 | Mixin targets verified on 26.1.2/26.2/26.3 bundles; `require = 0` only for host-optional targets; patches `git am`-clean; `MIXIN-CONTRACT.md` and `OVERLAY.md` updated |
| Performance | 0–10 | No new per-chunk allocation or syscalls on the hot path without a measurement |

## Scoring rules

- Justify every score, high or low, with facts: `path:line`, the code, or a command output.
- A score below 10 lists the exact fixes needed. A 10 states why nothing is left.
- Unverified claims ("should work on Folia") cap the dimension at 7 until a test or live run proves them.
- Final output: a table of dimension, score and evidence, then the fix list. Iterate until every dimension is 10, then run the full test pyramid.
