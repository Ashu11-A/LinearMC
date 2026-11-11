---
name: linearmc-release
description: "Cut a LinearMC release - tag scheme vX.Y.Z-release.N, jar asset names with +build.N, release notes carrying upstream #N builds, parity and test gates. Use when preparing, tagging, drafting notes for, or verifying a release."
---

# Releasing LinearMC

Source of truth: `release/TAGS.md`, `release/RELEASE.md`, `CONTRIBUTING.md` ("Cutting a release"), `.github/workflows/build.yml`.

## Naming (canonical: `release/TAGS.md`, do not restate it elsewhere)

- Tags are `vX.Y.Z-release.N`. A new patch set bumps `X.Y.Z`; a rebuild or an upstream companion of the same set bumps `N`.
- Never create a plain `vX.Y.Z`. Never move or delete a historical tag.
- Asset names carry `+build.<upstreamN>`. The release body uses `#N`, injected by `scripts/release-notes.py`.

## Gates (all must pass)

1. `scripts/parity-check.sh --release`: zero `gap`. `build.yml` (`discover` job) refuses a tag build otherwise.
2. CI green: `test.yml` (static, core, unit, horizon matrix, config-check) and `build.yml` (build plus boot smokes).
3. Live matrix on the built jars, including the migration and clean-stop scenarios (skill `linearmc-test`). Record sha256 and results.
4. The plugin-internal version (`paper-plugin.yml`, `horizon.plugin.json`, `build.gradle.kts`) follows the tag. CI stamps it on tags.
5. `CHANGELOG.md` has an entry. The README compatibility list matches the pins.

## Steps

Follow `CONTRIBUTING.md` "Cutting a release", which is the canonical step list. Verify afterwards:

```bash
gh release view vX.Y.Z-release.N --repo Ashu11-A/LinearMC
```

Every `gh release` call carries `--repo`. Never publish or push tags without explicit user approval.
