---
name: linearmc-perf
description: Measure and improve LinearMC performance - flush CPU, compression ratio, allocation/GC on the chunk hot path, bucketed V2/V3 rewrites, Horizon double compression. Use for benchmarks, profiling, compression-level questions, or any optimization claim.
---

# LinearMC performance

## Ground rules

- No optimization without a before/after number on the same machine and the same world. Store results in `docs/benchmarks/<mc>/`.
- Compression level default is 6. Levels ≥12 are documented as unsafe (`README.md`, "Safety & limits").
- Correctness gates stay on: core suites plus round-trip and fuzz tests (`RoundTripPropertyTest`, `FuzzEnvelopeTest`).

## Open opportunities

Tracked in `docs/performance.md` ("Open work"): pooled zstd contexts/buffers, recompressing only dirty V2/V3 buckets, removing the Horizon double compression.

## How to measure

- Micro benchmarks: a JUnit-driven harness in `linear-core` (encode/decode of real region samples; record ops/s and bytes allocated via `-XX:+UnlockDiagnosticVMOptions` or JFR allocation events).
- Macro benchmarks: `EXTRA_JAVA_OPTS="-Dlinearmc.implementation=V2" scripts/server-smoke.sh <dir> loadgen 120 [horizon] LINEAR`. Compare `/linearstats` flush latency (p50/p99), disk size and MSPT across ANVIL, LINEAR V1 and LINEAR V2.
- Profiling: JFR (`-XX:StartFlightRecording=duration=120s,filename=proof/perf.jfr`), then inspect the `linear-flush-*` threads.

## Report format

State the workload, MC version, leg, level, implementation and grid. Give the metric before and after, with the delta and its variance across at least 3 runs. Name the commit.
