# Observability

Two commands show the same save counters.

- `/linearstats [filter]` shows every tracked folder, or only the paths that contain "filter". It takes no other arguments.
- `/linear stats [world]` shows the same panel, or only the folders whose path contains "world". Leave out the filter to see everything.

Both commands are read-only: they read the counters at the moment you run them, never trigger a save and never change data. `commands.md` covers `/linear convert`, `/linear queue` and `/linear help`.

## Empty and denied output

- Until this boot has done any Linear I/O, both commands print "linearstats: no Linear folders tracked (no linear I/O yet)."
- On a server that only uses Anvil, that line is the expected, healthy result.
- Rows appear only after real Linear I/O, normally after a save cycle.
- A sender without permission gets "You do not have permission to use linearstats." and no rows.

Permissions (operators have them by default):

- `/linearstats` needs `linear.command.linearstats`; no other node grants it.
- `/linear stats` accepts `linear.command.linearstats` or `linear.command.linear`.
- The convert and queue commands need `linear.command.convert` or `linear.command.queue` respectively (or `linear.command.linear`); see `commands.md`.

## Field glossary

For each world, the output has a header line with the world name and its most recent flush, then one block per folder type. A server-wide TOTALS line comes last.

| Field | Meaning |
| --- | --- |
| world | Parent folder name of the storage directory, from ".../world/type" |
| level | World zstd compression level, shown as "L6" style; "L?" means the level is unknown |
| folder type | One of "region", "poi", "entities"; compact rows shorten these to "reg", "poi", "ent" |
| read average | Read operation count with average time per read, or "0us" when the count is 0 |
| write average | Write operation count with average time per write, or "0us" when the count is 0 |
| flush average | Flush operation count with average time per flush, or "0us" when the count is 0 |
| loads | Region file loads |
| files | Files written by successful flushes only; clean flushes that wrote nothing add nothing |
| fail | Failed flush attempts that will be retried on a later save |
| dirty dN/512 | Current number of dirty files waiting to flush, out of a maximum of 512, with a 10-cell bar that fills as the number grows |
| saved % | Disk space saved by compression, from uncompressed size to packed size; "na" or "no bytes yet" means no flush has written bytes yet |
| p50 / p99 latency | Upper-bound estimates of flush time; clean flushes that wrote nothing are excluded |
| last-flush age | Time since the last successful flush for that folder; "never" means it has not flushed yet this boot |
| TOTALS row | Server-wide sums. The full view sums reads, writes, flushes, loads, files, and failures; the compact view shows files, failures, space saved, and folder count |

## Health thresholds

The compact view colours each metric using these fixed limits.

| Metric | OK | Warn | Critical |
| --- | --- | --- | --- |
| dirty | 50 or fewer waiting | 51 to 256 waiting | More than 256 waiting |
| fail | 0 | 1 | 2 or more |
| p99 flush latency | Below 100ms | 100ms to 500ms | Above 500ms |

A dirty count close to the 512 maximum means flushes are falling behind writes. If the fail count is above zero, check disk space and permissions. A high p99 with a normal p50 points to occasional slow flushes, not a disk that is always slow.

## Boot lines that show the effective format and level

When a startup property overrides the file config, the server logs which value won. The scope in brackets says whether that value applies to one world or the whole server.

- "[region-format] sysprop linearmc.format=LINEAR overrides file ANVIL -> effective LINEAR (world)"
- "[region-format] sysprop linearmc.compression-level=9 overrides file 6 -> effective 9 (world)"
- "[region-format] Ignoring invalid sysprop linearmc.format=BOGUS, using file value."

Lines of the same kind report bad file values and their fallbacks:

- "[region-format] Unknown region format, expected ANVIL, LINEAR or B_LINEAR. Falling back to ANVIL."
- "[region-format] linear.compression-level must be 1-22, got 99. Falling back to 6."

A boot with no "[region-format]" line at all is healthy: the file values were valid and no startup override was set.

## Warning and action lines

| Log line | Meaning | What to do |
| --- | --- | --- |
| "[region-format] Unknown region format, expected ANVIL, LINEAR or B_LINEAR. Falling back to ANVIL." | The "format:" value was misspelled or had the wrong case; that world runs on Anvil | Fix the spelling and case, then restart |
| "[region-format] linear.compression-level must be 1-22, got 99. Falling back to 6." | Level out of range; the server runs at level 6 | Set a level from 1 to 22, then restart |
| "Failed to flush linear region file for folder `<dir>`, will retry on next save" | A flush failed; the file stays dirty and is retried on the next save | Check disk space, inodes, and directory permissions; if it repeats every save, stop and roll back |
| "Linear region file `<path>` is a broken symbolic link, crashing to prevent data loss" | A Linear path is a dangling symlink; the server halted rather than treat the region as missing | Restore or repoint the symlink, then boot |
| "Linear region files cannot be recalculated, regenerating chunk `<pos>`" | A chunk header inside a Linear file did not match; that chunk was regenerated | Note the coordinates; restore that region file from backup if the chunk matters |
| "Attempting to read chunk data at `<pos>` but got chunk data for `<other>` instead! Linear region files cannot be recalculated, regenerating chunk `<pos>`" | Chunk and header disagree; the chunk was regenerated | Same as above; repeated storms mean restore from backup |

## Troubleshooting

- **An Anvil-only server shows no rows.** That is healthy. "linearstats: no Linear folders tracked (no linear I/O yet)." means no Linear folder has been touched. Switch a world to Linear and run a save cycle to see rows.
- **Linear files exist but there are still no rows.** Counters only fill on actual I/O; files sitting on disk do not count. Trigger a save, wait for the flush age gate, then run the command again.
- **Rows never update.** Flushes are age-gated, so recently dirtied files wait for the next save. Check the last-flush age column before assuming something is stuck.
- **Permission denied.** The sender gets "You do not have permission to use linearstats." Grant "linear.command.linearstats" for `/linearstats`, "linear.command.linear" for the `/linear` family, or operator status.
- **The fail counter keeps climbing.** Check disk space, inodes and write permission on that world folder. If every save fails, stop the server and roll back instead of letting the dirty queue grow.
- **A chunk was regenerated.** Find the "regenerating chunk" line, note the coordinates, and restore that region file from backup if the chunk matters.
