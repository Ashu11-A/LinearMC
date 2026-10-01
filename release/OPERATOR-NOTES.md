# LinearMC Operator Manual

For whoever is holding the pager during a rollout. Keep a verified backup before changing any world. Every world starts on Linear unless you pin it back. Readers look in both `.mca` and `.linear` files, so old Anvil data keeps working after you switch.

## Installing

Install on a clean machine before any rollout. You need Java 25, checked with `java -version`.

Keep roughly 2.5 times the world size free on disk. A full rollback needs about twice the live worlds size as working room, so short disk means stop.

Stage the server directory, copy in the paperclip jar, and check its sha256 against the shipped value. The check must print OK before you go further.

Boot once to generate the stock configs, then accept the EULA and stop the server. Do not configure before this first boot.

Lay the shipped region-format defaults over the generated configs. The paper-world-defaults part goes into `paper-world-defaults.yml` and the paper-global part goes into `paper-global.yml`. Leave the per-world block out so worlds inherit Linear. Only paste the per-world block where you want Anvil. Check each edited file parses as YAML.

Boot again. A healthy boot shows no `[region-format]` lines in the log. If you see any such line, fix the config before rolling out. The shipped defaults are Linear format, compression level 6, broken-symlink guard on, and a flush interval of 10 seconds.

## Rolling out world by world

Work one world at a time, lowest risk first. Never start with the spawn world, and never flip all worlds at once. Mixed worlds in one server are supported, not degraded.

Quiesce the world first. Stop the server or quiet the world so nothing is writing, then copy that world's `region`, `poi`, and `entities` folders to a backup location such as `/backup/<world>/`.

Record the snapshot with `date -u +%FT%TZ > /backup/<world>/BACKUP_VERIFIED`. Never start a revert without this marker file plus a region folder tree inside the backup.

Check health before flipping. There must be no zero-byte `r.*.mca` files, and `find <world>/region <world>/poi <world>/entities -xtype l` must print nothing. A broken symlink must be fixed from backup before you proceed, because the guard halts the server on purpose.

Record a baseline with `du -sb region poi entities` plus counts of `*.mca` files against `*.linear` files. You need this to compare after the switch.

Flip at most one world, then restart and watch the log. Soak at least one full save cycle and one more restart. Linear files update on the save rhythm and the restart proves they reopen. Only then move to the next world, leaving the busiest worlds for last.

## Monitoring

After each save cycle and restart, check that new `r.X.Z.linear` files exist and grow. Use `ls <world>/region/*.linear | wc -l` and `du -sb` before and after a save. Stock software never creates that extension, so growth proves Linear writes are landing.

Confirm the on-disk marker with this optional deep check (a normal `ls` showing `.linear` files growing is proof enough day to day): `head -c 8 <file>.linear | od -A x -t x1z` must start with `c3 ff 13 18 3c ca 9d 9a`. Check the tail of the file too: the same eight bytes repeat as a trailer. This proves the format independent of the logs.

Check timing with `ls -l --time-style=full-iso` across a save cycle. Linear file times should advance on the save rhythm, not on every edit. That rhythm is the design: changes collect in memory and flush on world save.

Check `/linearstats` with no arguments. It needs the permission `linear.command.linearstats`, granted to operators by default. Without it you get a denial message and no rows. The message `linearstats: no Linear folders tracked (no linear I/O yet).` on an Anvil-only world is normal, not a fault.

Warning-only config lines mean the world booted but not as intended. Fix the spelling in the YAML:

- `[region-format] Unknown region format, expected ANVIL or LINEAR. Falling back to ANVIL.` The format value is misspelled or wrong case, for example lowercase `linear`. The world is running Anvil.
- `[region-format] linear.compression-level must be 1-22, got <v>. Falling back to 6.` The level is out of range and the server runs at 6.
- `[region-format] linear.flush-frequency must be >= 1, got <v>. Falling back to 10.` The interval is below 1 and the server runs at 10.

## When something looks wrong

| What you see | What it means and what to do |
| --- | --- |
| `Linear region file <path> is a broken symbolic link, crashing to prevent data loss` | The symlink guard halted the server to avoid regenerating chunks. Restore or fix the symlink from backup. The guard defaults to halting; only turn it off briefly for forensics, then fix the link properly. |
| `Failed to flush linear region file for folder <dir>, will retry on next save` | A delayed write failed and stays queued for the next save. Check disk space, inodes, and permissions on that folder. If it repeats on every save, stop and flip the world back to Anvil. |
| `Linear region files cannot be recalculated, regenerating chunk <pos>` | The header in a Linear file did not match, so that chunk was regenerated and its old contents are lost. Note the coordinates and restore that region file from backup if the chunk matters. A steady storm of these lines means restore from backup. |
| `Chunk at (<x>,<z>) in regionfile '<name>' exceeds max size of <n>MiB, it has been deleted from disk` | An oversized chunk was deleted, matching stock behavior. Occasional hits are expected on corrupt or oversized chunks. Frequent hits mean investigate the world generator or a plugin. |
| Converter reports `ERROR converting <path>: ...` | That single file failed to convert and the live world was left untouched. Fix the file, usually a zero-byte region file, and run the conversion again. |

## Opting a world out

To keep a world on Anvil, set `region-format.format: ANVIL` in that world's own `paper-world.yml` only. Use the per-world Anvil override block from the shipped `region-format.yml` as the template.

Follow the same safe order: quiesced backup with the BACKUP_VERIFIED marker, symlink and zero-byte checks, baseline sizes, one world at a time, soak one save cycle plus a restart. Repeat one world at a time if more worlds need pinning, busiest last.

## Rolling back

Every path below requires a verified backup first: a snapshot containing `BACKUP_VERIFIED` and a region folder tree. Also check the jar sha and free disk space before changing anything. You restart the server yourself when ready.

Flip flags back to Anvil on the Linear jar for the fast path: set `region-format.format: ANVIL` in the world's config (or drop `-Dlinearmc.format` on Horizon). Restart and confirm no severe `[region-format]` lines. New chunks land as `r.X.Z.mca` and existing Linear files stay readable.

For the full path back to stock, convert the files back with `/linear convert <world> --to-mca --execute`, verify the report shows no diffs, then swap in the stock server jar. Any diffs mean the live tree stays untouched — fix the failing file and convert again. After a clean boot you may remove stale Linear files.

To re-apply Linear to named worlds later, set the format back to Linear, restart, then confirm Linear file growth as in the Monitoring section.

## Crash-safety window

Delayed writes collect for up to one flush interval, 10 seconds by default. A crash can lose about that window per file plus the single write in flight. Background saving did not change this window — it only stopped slow saves from pausing the game.

A clean stop is zero-loss: shutdown waits for queued writes to finish rather than interrupting them, with a 30 second settle period. Always stop the server cleanly instead of killing it, and keep the verified backup until the next world has soaked a save cycle and a restart.
