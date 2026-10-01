# Canvas server leg, line 26.2

In progress. Not yet a supported release.
The Folia leg for line 26.2 is empty and has not landed.

Upstream pin: Canvas branch ver/26.2 at 4a0ed14, game version 26.2.
Pin source: upstream.canvas.properties in the parent folder.
Folia pin for this line, when it lands: branch ver/26.2.x at acf6733, game version 26.2.

Same post-apply overlay model as the 26.1 Canvas leg.
Upstream source patches stay pristine. Linear hunks land after upstream apply with a check gate.
See OVERLAY.md next to this file.

Core storage logic comes from linear-core at the repo root at stage time and is not stored here.
Paper-side new files live under the paper server tree, tests under the tests tree.

For format, settings, and operation see docs/architecture.md, docs/configuration.md, release operator notes, and release notes.
