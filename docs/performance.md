# Performance conclusions

This page summarizes what the measurements showed, without the raw tables, and keeps only conclusions that still hold.

## Results

- Typical disk savings are about thirty to forty-five percent compared with the old format.
- Sparse worlds save the most; the End reached about eighty-eight percent in the best case.
- Dense, heavily built overworld areas save the least, about twenty-eight percent.
- Higher compression levels save more space but take much longer to write. Each step up gains less space and costs more time.

## Level choice

- Level 6 is the shipped default because it keeps most of the savings without the heavy write cost of higher levels. It is a compromise, not the smallest output.
- Levels 12 and above are unsafe on a live server. In testing, shutdown ran out of time and chunks were lost.
- Changing the level only changes a setting. The superblock records the level for information, and reading never needs it, so no conversion pass is required: existing files are rewritten at the new level the next time they flush. `configuration.md` has the setting name and range.

## What was not measured

- Lag as players feel it, measured with players connected.
- Multi-day runs at high compression levels.
- Shared disks, network filesystems and storage shared by several servers.

Judge a rollout by normal play after warm-up, not by one-time conversion or first-minute numbers.

## Open work

These were measured or reviewed on 2026-11-10 and are not implemented yet. Measure before and after any change (see the `linearmc-perf` skill).

- Pool `ZstdCompressCtx`/`ZstdDecompressCtx` and size-classed buffers; a `ZstdOutputStream` is created per bucket today (`linear-core/codec/.../versions/base/AbstractBucketedVersion.java`). Model: SectorTool `ZstdCtxManager`/`BufferChoices`.
- V2/V3: recompress only dirty buckets (DivineMC `V2LinearBase.buildBuckets`, Pumpkin `linear.rs`); every bucket is recompressed on each flush today.
- Horizon: the payload arrives already compressed by `RegionFileVersion` and is decompressed again in `LinearWritePath.normalizeWritePayload`; intercept before `RegionFileVersion.wrap`.
