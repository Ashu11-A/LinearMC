# Horizon plugin

The Horizon plugin brings LinearMC to a server through bytecode mixins, so you
don't need to rebuild a fork. One jar covers Paper, Folia and CanvasMC
26.1-26.3. It never links against Canvas-only or Folia-only classes, which keeps
it portable across those hosts and across patch releases in the 26.x line.

It hooks region file and region storage behavior, plus the Moonrise region
controller where the host has one, and delegates to the shared core. The
optional Moonrise exists-path bridge is looked up by reflection. A host without
it is valid (the create path covers opens) and does not fail the boot.

Install:

- Put the mixin jar in the plugins folder. That is the only jar. The old
  `-plugin` side jar carried the Paper classes without their dependencies and
  could never load on its own, so it is no longer published.
- The shipped default format is LINEAR. Change it in
  `plugins/LinearMC/config.yml`, or with `-Dlinearmc.format` on the server
  startup command line (see `docs/configuration.md`).

Compatibility contract:

- Build target: Paper dev bundle 26.1.2 (oldest supported line), `api-version: 26.1`.
- Tested against 26.1.2, 26.2 and 26.3 on every push by `test.yml`.
- Hosts: Paper, Folia, CanvasMC and compatible forks. The enable log names
  the detected family (`host=CANVAS|FOLIA|PAPER|UNKNOWN`).
- No host-specific code path: every storage injector is required, and
  `MixinDescriptorsTest` checks each host method per dev bundle (the Folia-only
  `stopPart2` is the one target allowed to be absent).

When verifying, trust the superblock bytes on disk, never the file names. In
the past the injector did not fail the boot when a patch missed, so a file with
the expected name could hide a miss.

Never target Horizon internals, except the supported inject package, logging
frameworks or the mixin framework itself. A required patch must fail fast when
its target is missing.

Build gates enforce the core rule: plugin adapter code only delegates and holds
no new storage logic, and the core holds no server-specific code.

For format, settings and monitoring, see `docs/architecture.md`,
`docs/configuration.md` and `docs/observability.md`.
