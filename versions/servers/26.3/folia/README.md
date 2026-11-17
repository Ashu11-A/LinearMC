# Folia server leg, line 26.3

Supported release leg.

Upstream pin: Folia branch ver/26.3.x at 7337820 (build 8), game version 26.3.
Replaces the archived 26.1.x leg (versions/servers/removed/26.1.x); Folia 26.1.2 hosts use the Horizon plugin.
Pin source: upstream.properties in the parent folder.

Patches live per fork and are applied at build time.
Shared hunks used by more than one leg live once in the common folder next door.

Core storage logic lives in linear-core at the repo root.
Patches stay thin and delegate there.
Rule enforced by build gates: patches hold no new storage code, core holds no server-specific code.

For format, behavior, and settings see docs in the repo root docs folder:
architecture, configuration, observability, limitations, plus operator notes and release notes under release.
