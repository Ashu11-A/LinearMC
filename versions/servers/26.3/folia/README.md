# Folia server leg, line 26.3

Supported release leg.

Upstream pin: Folia branch ver/26.3.x at eca40f9 (build 26), game version 26.3.
Replaces the archived 26.1.x leg (versions/servers/removed/26.1.x); Folia 26.1.2 hosts use the Horizon plugin.
Pin source: upstream.properties in the parent folder.

Patches live with each fork and are applied at build time. Hunks used by more
than one leg live once in the `common/` folder next door.

Core storage logic lives in `linear-core` at the repo root, and the patches
stay thin and delegate to it. Build gates enforce the split: patches hold no
new storage code, and the core holds no server-specific code.

For format, behavior and settings, see `docs/architecture.md`,
`docs/configuration.md`, `docs/observability.md` and `docs/limitations.md`,
plus the operator notes and release notes under `release/`.
