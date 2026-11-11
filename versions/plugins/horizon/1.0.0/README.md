# Horizon plugin

Bytecode plugin alternative to rebuilding a fork. No fork rebuild needed.
One jar for Paper, Folia and CanvasMC 26.1-26.3; the plugin never links
against Canvas- or Folia-only classes, so it stays portable across those
hosts and across patch releases in the 26.x line.

Targets region file and region storage behavior, plus the Moonrise region
controller where present, and delegates to the shared core. The optional
Moonrise exists-path bridge is probed reflectively: absent is a valid host
shape (create-path covers opens), not a boot failure.

Install:

- Put the mixin jar in the plugins folder. No second jar is needed: the
  former `-plugin` side-product carried the Paper classes without their
  dependencies and could never load standalone, so it is no longer
  published.
- Put the format flag on the server startup command line.
- Default format is Anvil when the flag is absent.

Compatibility contract:

- Build target: Paper dev bundle 26.1.2 (oldest supported line), `api-version: 26.1`.
- Tested against 26.1.2, 26.2 and 26.3 on every push by `test.yml`.
- Hosts: Paper, Folia, CanvasMC and compatible forks. The enable log names
  the detected family (`host=CANVAS|FOLIA|PAPER|UNKNOWN`).
- No host-specific code path: every storage injector is required, and
  `MixinDescriptorsTest` checks each host method per dev bundle (the Folia-only
  `stopPart2` is the one target allowed to be absent).

Verification rule: trust on-disk superblock bytes, never file names.
Reason: the injector historically did not fail boot when a patch missed, so a wrongly named file can hide a miss.

Never target Horizon internals except the supported inject package, logging frameworks, or the mixin framework itself.
Required patches must fail fast when a target is missing.

Core rule enforced by build gates: plugin adapter code holds no new storage logic beyond delegation, core holds no server-specific code.

For format, settings, and monitoring see docs/architecture.md, docs/configuration.md, and docs/observability.md.
