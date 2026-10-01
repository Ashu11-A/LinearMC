# Canvas post-apply layer, line 26.2

Upstream pin: Canvas branch ver/26.2 at 4a0ed14. Pin source is the parent upstream file.

Same model as the 26.1 Canvas leg: zero files in the upstream source patch directory, every Linear hunk applies after upstream apply with a check gate.
Proven on the pinned ref with all checks clean, most hunks carried verbatim and a few seams relocated with intent unchanged. All storage logic stays in linear-core.

Seam relocations for 26.2:

- Startup conversion resolves the level key through the level stem registry because the old paper loader helper is gone upstream.
- Region storage hunk keeps narrowed access levels matching upstream.
- Shutdown thread hunk follows the new disconnect handling line.
- Command hunk hand places the two linear lines after the tick line because upstream dropped a neighboring registration.
- Global config hunk anchors on the region scheduler tail ahead of the chunk system section.

Carried divergences from 26.1, re-verified:

- Stop drain stays Linear only.
- Live format reads the Canvas world config. Operators use the Canvas worlds file.
- Startup conversion file plus the pre-plugin server trigger remain.
- Command lines sit after the tick line.

Tests stage into the canvas server test tree because line 26.2 dropped the old shared test wiring and has no folia server module.

For behavior and settings see docs/architecture.md and docs/configuration.md.
