# Shared storage core

Owns what all backends share: open, read, write, clear, and sync behavior, file extension dispatch between old and new formats, the on-disk header with current writer and backward reader, whole-region compression, chunk count checks, oversize payload rejection without sidecar drift, and the age-gated flush policy.

Hard rule enforced by build gates: zero server imports. The core compiles standalone and loads both inside fork jars and under the plugin class loader.
Server adapters stay thin and delegate here.
Concurrency and memory rules come from the architecture doc: shared bounded pool, leaf-ordered locks never held across input output, direct buffers end to end on hot paths.

Extracted from the Folia format, observability, coordinator, pipeline, and save-drain patches.

Test coverage runs per module:

- Format module covers keys, format, policy, factory, and storage probe.
- Codec module covers codec, streams, suppliers, envelope, and write path.
- Config module covers policy.
- Flush module covers timings, suppliers, coordinator age gate versus forced flush, eviction, flush listener, and high file count pressure.
- Convert module covers pure helpers plus round trip and protection paths through a fake region opener with no server code.
- Command module covers job queue and command behavior.

Known gaps, accepted as tech debt:

- Conversion tests use an in-memory fake old-format opener. Real old-format reads still need a server classpath.
- Multi-file crash resume policy has no multi-file integration test.
- Parallel flush pooling has unit pressure coverage only, no threading stress test.
- Corrupt header and torn footer rejection is covered indirectly through the round trip path only.

For details see docs/architecture.md. For settings see docs/configuration.md. For monitoring see docs/observability.md.
