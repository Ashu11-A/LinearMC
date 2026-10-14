# Canvas server leg, line 26.3

Supported release (ships under v1.1.0 as `linearmc-canvas-26.3-1.1.0.jar`).

Upstream pin: Canvas `main` at `cc4e7611`, game version 26.3.
Pin source: upstream.canvas.properties in the parent folder.
There is no Folia leg for this line (no 26.3 Folia source exists upstream).

Same post-apply overlay model as the 26.1/26.2 Canvas legs.
Upstream source patches stay pristine. Linear hunks land after upstream apply with a check gate.
See OVERLAY.md next to this file.

Core storage logic comes from linear-core at the repo root at stage time and is not stored here.
Shared overlay files come from versions/servers/common/canvas/ (hub);
this leg keeps only seam adapters (see OVERLAY.md for the 26.3 seam list).
Paper-side new files beyond the hub: `LinearStartupConversion.java` only.
Tests come from the hub (this leg carries no tests of its own).

For format, settings, and operation see docs/architecture.md, docs/configuration.md, release operator notes, and release notes.
