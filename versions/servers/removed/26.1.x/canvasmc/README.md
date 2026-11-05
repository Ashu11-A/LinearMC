# Canvas server leg, line 26.1

Supported release leg.

Upstream pin: Canvas branch ver/26.1.2 at abd7a65, game version 26.1.2.
Pin source: upstream.canvas.properties in the parent folder.

Canvas is a Folia fork with a per-file patch layout, so this leg does not use feature patches directly.
Linear changes arrive as a post-apply overlay after upstream apply finishes, applied with a check gate that fails loudly on drift.
See OVERLAY.md next to this file.

Region file patch points cover region files, region storage, region versioning, chunk storage, and the Moonrise region controller where present.

Core storage logic comes from linear-core at the repo root.
Paper-side new files live under the paper server tree, tests under the tests tree.

For format, settings, and operation see docs/architecture.md, docs/configuration.md, release operator notes, and release notes.
