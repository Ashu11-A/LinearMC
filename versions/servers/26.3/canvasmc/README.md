# Canvas server leg, line 26.3

Supported release leg (ships under v1.3.0-release.1 as
`linearmc-canvas-26.3-1.4.0+build.1047.jar`).

Upstream pin: Canvas `main` at `59357cb7`, game version 26.3, build **#1047**.
Pin source: upstream.canvas.properties in the parent folder.
The Folia leg for this line is in `../folia/`.

This leg uses the same post-apply overlay model as the earlier Canvas legs.
Upstream source patches stay untouched, and the Linear hunks are applied after
the upstream apply, each behind a check. `OVERLAY.md` next to this file has the
details.

Core storage logic comes from `linear-core` at the repo root during staging
and is not stored here. Shared overlay files come from the hub in
`versions/servers/common/canvas/`, so this leg keeps only seam adapters
(`OVERLAY.md` lists the 26.3 seams). The only Paper-side file it adds beyond
the hub is `LinearStartupConversion.java`, and all of its tests come from the
hub.

Canvas 26.1.2 and 26.2 have been unsupported since v1.1.4-release.1. Their legs
are archived under `versions/servers/removed/` and are not built or tested.

For format, settings and operation, see `docs/architecture.md`,
`docs/configuration.md`, the operator notes and the release notes.
