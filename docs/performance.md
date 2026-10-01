# Performance conclusions

What the measurements showed, without the raw tables. Only conclusions that still hold are repeated here.

## What we saw

- Expect typical disk savings around thirty to forty-five percent compared with the old format.
- Sparse worlds save the most. The End reached around eighty-eight percent in the best case.
- Dense, heavily built overworld areas save the least, around twenty-eight percent.
- Higher compression levels save more space but cost far more time to write. The extra space shrinks while the extra time grows quickly.

## Level choice

- Level 6 is the shipped default because it keeps most of the savings without the heavy write cost of higher levels. It is a balance point, not the smallest or the tightest option.
- Levels 12 and above are unsafe for live use. In testing, shutdown ran out of time and chunks were lost.
- Changing the level is a settings-only change. The level is stored in the superblock for information only and is never needed to read the data back, so no conversion pass is required. Existing files are simply rewritten at the new level the next time they flush. See the configuration reference for the setting name and range.

## What was not measured

- No live player-facing lag measurements with players connected.
- No testing over many days at high compression levels.
- No shared disk, network filesystem, or multi-server storage.

Judge a rollout on normal play after warm-up, not on one-time conversion or first-minute numbers.
