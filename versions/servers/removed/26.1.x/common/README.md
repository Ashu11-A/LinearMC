# Shared hunks, line 26.1

Home for byte-identical hunks shared across server legs.

Each server leg references these at stage time instead of carrying its own copy.
Near-identical hunks with mapping drift stay per server as thin adapters that delegate to linear-core.

Rules:

- Every shared hunk must pass full patch apply plus the storage test suite on each leg that uses it.
- Never share at the cost of changing the Anvil path.
- First candidates are the small ones: flush unlock behavior and compression tuning.

For what the shared code does see docs/architecture.md.
For settings see docs/configuration.md.
