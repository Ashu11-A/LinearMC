# Canvas post-apply layer, line 26.1

Upstream owns the per-file source patches for files we also touch.
A same-named file would silently replace upstream work, so this leg keeps zero files in that upstream directory.
All Linear changes apply after upstream apply, with a check gate before each apply.

Layout, relative to this folder:

| What | Where it lands | How |
| --- | --- | --- |
| Storage hunks | Generated canvas server minecraft tree | Check gate then apply |
| Paper loader and command hunks | Paper server tree | Check gate then apply |
| Config hunks, derived from the applied tree | Repo root paths | Check gate then apply |
| Paper-side new files | Paper server source tree | Verbatim copy |
| Core file copies | Generated canvas server storage folder | Verbatim copy |
| Tests | Test tree preserving packages | Copy tree |

Designed divergences, all proven by the suite:

- Stop drain hunk is Linear only and never duplicates the upstream hunk for the same file.
- Live format reads the Canvas world config, not the Paper world config. Operators use the Canvas worlds file.
- Startup conversion maps the paper format choice to the core format choice at the call site.
- Command registration is hand placed after the tick command line because upstream context drifted.
- Paper global and world flush keys were retired. No live readers remain. The trigger lives in the server startup hunk plus the startup conversion file.
- Tests are Folia ports plus Canvas adaptations for global defaults, startup conversion, default format, upgrade scan, and broken link guard.

Proven on the pinned ref with full suite green plus region file emission and Anvil opt-out boot checks.

For behavior and settings see docs/architecture.md and docs/configuration.md.
