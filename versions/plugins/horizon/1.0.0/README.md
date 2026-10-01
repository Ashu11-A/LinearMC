# Horizon plugin

Bytecode plugin alternative to rebuilding a fork. No fork rebuild needed. Portable across recent game versions.

Targets region file and region storage behavior, plus the Moonrise region controller where present, and delegates to the shared core.

Install:

- Put both the mixin jar and the plugin jar in the plugins folder. Both are required.
- Put the format flag on the server startup command line.
- Default format is Anvil when the flag is absent.

Verification rule: trust on-disk superblock bytes, never file names.
Reason: the injector historically did not fail boot when a patch missed, so a wrongly named file can hide a miss.

Never target Horizon internals except the supported inject package, logging frameworks, or the mixin framework itself.
Required patches must fail fast when a target is missing.

Core rule enforced by build gates: plugin adapter code holds no new storage logic beyond delegation, core holds no server-specific code.

For format, settings, and monitoring see docs/architecture.md, docs/configuration.md, and docs/observability.md.
