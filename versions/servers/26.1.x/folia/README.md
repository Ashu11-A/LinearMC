# Folia server leg, line 26.1

Supported release leg.

Upstream pin: Folia branch ver/26.1.x at 62dc0f2, game version 26.1.2.
Pin source: upstream.properties in the parent folder.

Patches live per fork and are applied at build time.
Shared hunks used by more than one leg live once in the common folder next door.

Core storage logic lives in linear-core at the repo root.
Patches stay thin and delegate there.
Rule enforced by build gates: patches hold no new storage code, core holds no server-specific code.

For format, behavior, and settings see docs in the repo root docs folder:
architecture, configuration, observability, limitations, plus operator notes and release notes under release.
