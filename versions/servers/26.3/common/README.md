# Shared hunks, line 26.3

This folder holds hunks that are byte-identical across server legs. Each leg
pulls them in at stage time instead of carrying its own copy. Hunks that are
nearly identical but differ in mappings stay with each server as thin adapters
that delegate to `linear-core`.

Rules:

- A shared hunk must pass the full patch apply and the storage test suite on every leg that uses it.
- Never share a hunk if doing so changes the Anvil path.

The folder is empty for now. The former flush-unlock hunk (0011) was folded into
Folia `minecraft-0009`, because Paper 26.3 rewrote `RegionFileStorage`.

`docs/architecture.md` explains what the shared code does, and
`docs/configuration.md` covers the settings.
