---
name: linearmc-parity
description: Keep every LinearMC release feature-identical across Folia fork, CanvasMC fork and Horizon plugin. Use when adding or changing a user-facing capability, auditing which leg lacks a feature, editing parity/capabilities.yml, or before tagging a release.
---

# Leg parity contract

No release ships a leg with fewer capabilities (decision 2026-11-10). Contract: `parity/capabilities.yml` (its header documents the cell format). Gate: `scripts/parity-check.sh`.

```bash
scripts/parity-check.sh            # branch: evidence + test names resolve; gaps and test debt listed
scripts/parity-check.sh --matrix   # markdown matrix (✅* = supported with test debt)
scripts/parity-check.sh --release  # zero gaps or fail (build.yml runs it before any tag build)
```

## Rules

- One capability = one user-observable behaviour (command, flag, permission, config key/default, sysprop, storage folder, save/stop guarantee, stat, event).
- Evidence symbols must disappear with the feature: a method/handler name or a distinctive statement, never a comment or a generic word. In a `.patch` only added lines count.
- `test` names real `*Test` classes (suites do not count). Live-only proof is a `test_gap`, not a pass.
- "Not ported yet" is a `gap`. `exempt` is only for a host limit the user approved (`approved: who/when`).

## Adding a capability

1. Add it with `{gap: "..."}` on every leg.
2. Implement leg by leg (skill `linearmc-leg-port`), turning each cell into `{ev: [...], test: ...}`.
3. If an evidence symbol moved, fix the entry; never weaken the gate.
