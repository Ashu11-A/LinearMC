# LinearMC operator manual

This manual is for whoever is on call during a rollout. Keep a verified backup before you change any world. Every world runs on Linear unless you pin it back to Anvil. Readers look for both `.mca` and `.linear` files, so existing Anvil data keeps working after the switch.

## Installing

Install on a clean machine before any rollout. You need Java 25; check it with `java -version`.

Keep about 2.5 times the world size free on disk. A full rollback needs about twice the size of the live worlds as working room, so if disk is short, stop here.

Stage the server directory, copy in the paperclip jar, and check its sha256 against the published value. Don't continue until the check prints OK.

Boot once to generate the stock configs, accept the EULA, and stop the server. Don't configure anything before this first boot.

Apply the shipped region-format defaults on top of the generated configs: the paper-world-defaults part goes into `paper-world-defaults.yml` and the paper-global part into `paper-global.yml`. Leave out the per-world block so worlds inherit Linear, and paste it only into worlds that should stay on Anvil. Check that each edited file still parses as YAML.

Boot again. A healthy boot logs no `[region-format]` lines; if you see one, fix the config before rolling out. The shipped defaults are the Linear format, compression level 6, the broken-symlink guard on, and a 10-second flush interval.

## Rolling out world by world

Convert one world at a time, lowest risk first. Never start with the spawn world, and never switch every world at once. Running worlds in different formats on one server is fully supported.

Quiesce the world first: stop the server, or make sure nothing is writing to the world, then copy its `region`, `poi` and `entities` folders to a backup location such as `/backup/<world>/`.

Record the snapshot with `date -u +%FT%TZ > /backup/<world>/BACKUP_VERIFIED`. Never start a revert unless the backup has this marker file and a region folder tree.

Check health before switching. There must be no zero-byte `r.*.mca` files, and `find <world>/region <world>/poi <world>/entities -xtype l` must print nothing. Fix any broken symlink from backup before you go on, because the guard stops the server on purpose when it finds one.

Record a baseline with `du -sb region poi entities` and the counts of `*.mca` and `*.linear` files, so you can compare after the switch.

Switch one world, restart and watch the log. Let it run through at least one full save cycle and one more restart: Linear files update on the save schedule, and the restart proves they reopen. Then move to the next world, and leave the busiest worlds for last.

## Choosing V1, V2 or B_LINEAR

Stay on V1 unless you have a reason to change. It is the default writer and the reference format, and it is the easiest to reason about. V2 suits busy survival worlds where smaller rewrites matter; it writes the same `.linear` files in buckets and is DivineMC-compatible. B_LINEAR suits write-heavy worlds where you want the extra crash window of the swap file. Expect a longer shutdown drain with it, and never separate a `.b_linear` master from its `.swp` file. Readers accept every version, so worlds on different versions can share a server, and `/linear convert` can move a world later. V2 files are rewritten as V1 only if you set the implementation back.

Choose per world in the config (`region-format.linear.implementation: V1|V2|V3`, plus `grid-size` for V2/V3), or for the whole server on the Java command line (`-Dlinearmc.implementation=V2 -Dlinearmc.grid-size=8`, or `-Dlinearmc.format=B_LINEAR` for the buffered tier). A valid flag beats the config; a misspelled flag leaves the config in charge. Restart after changing either, then confirm with `/linearstats`, one save cycle and a restart before moving on.

## Monitoring

After each save cycle and restart, check that new `r.X.Z.linear` files appear and grow. Use `ls <world>/region/*.linear | wc -l`, and `du -sb` before and after a save. Stock software never creates that extension, so growth shows that Linear writes are landing.

For a deeper check, confirm the on-disk marker; day to day, seeing `.linear` files grow with `ls` is enough. `head -c 8 <file>.linear | od -A x -t x1z` must start with `c3 ff 13 18 3c ca 9d 9a`, and the same eight bytes repeat at the end of the file. This confirms the format without relying on the logs.

Check timing with `ls -l --time-style=full-iso` across a save cycle. Linear file times should advance on the save schedule, not on every edit. That is by design: changes collect in memory and are flushed on world save.

Run `/linearstats` with no arguments. It needs the `linear.command.linearstats` permission, which operators have by default; without it you get a denial message and no rows. On an Anvil-only world, `linearstats: no Linear folders tracked (no linear I/O yet).` is normal.

The config warnings below mean the world booted, but not the way you intended. Fix the spelling in the YAML:

- `[region-format] Unknown region format, expected ANVIL, LINEAR or B_LINEAR. Falling back to ANVIL.` The format value is misspelled or in the wrong case (for example lowercase `linear`), and the world is running Anvil.
- `[region-format] linear.compression-level must be 1-22, got <v>. Falling back to 6.` The level is out of range, and the server runs at 6.
- `[region-format] linear.flush-frequency must be >= 1, got <v>. Falling back to 10.` The interval is below 1, and the server runs at 10.

## When something looks wrong

| What you see | What it means and what to do |
| --- | --- |
| `Linear region file <path> is a broken symbolic link, crashing to prevent data loss` | The symlink guard stopped the server so it would not regenerate chunks. Restore or fix the symlink from backup. The guard is on by default; turn it off only briefly for forensics, then fix the link. |
| `Failed to flush linear region file for folder <dir>, will retry on next save` | A delayed write failed and stays queued for the next save. Check disk space, inodes and permissions on that folder. If it repeats on every save, stop and switch the world back to Anvil. |
| `Linear region files cannot be recalculated, regenerating chunk <pos>` | The header in a Linear file did not match, so that chunk was regenerated and its old contents are lost. Note the coordinates and restore that region file from backup if the chunk matters. If these lines keep coming, restore from backup. |
| `Chunk at (<x>,<z>) in regionfile '<name>' exceeds max size of <n>MiB, it has been deleted from disk` | An oversized chunk was deleted, as the stock server does. An occasional hit on a corrupt or oversized chunk is expected; frequent hits point to the world generator or a plugin. |
| Converter reports `ERROR converting <path>: ...` | That one file failed to convert, and the live world was left untouched. Fix the file (usually a zero-byte region file) and run the conversion again. |

## Opting a world out

To keep a world on Anvil, set `region-format.format: ANVIL` in that world's own `paper-world.yml` and nowhere else. The per-world Anvil override block in the shipped `region-format.yml` is the template.

Use the same safe order as a rollout: a quiesced backup with the BACKUP_VERIFIED marker, the symlink and zero-byte checks, baseline sizes, then one world at a time with one save cycle and a restart each. If more worlds need pinning, repeat for each, busiest last.

## Rolling back

Every path below needs a verified backup first: a snapshot that contains `BACKUP_VERIFIED` and a region folder tree. Also check the jar sha and the free disk space before you change anything. You restart the server yourself when you are ready.

The fast path keeps the Linear jar and switches back to Anvil: set `region-format.format: ANVIL` in the world's config (or drop `-Dlinearmc.format` on Horizon). Restart and confirm there are no severe `[region-format]` lines. New chunks are written as `r.X.Z.mca`, and existing Linear files stay readable.

The full path returns to stock. Convert the files back with `/linear convert <world> --to-mca --execute`, check that the report shows no diffs, then swap in the stock server jar. If there are diffs, the live tree is left untouched; fix the failing file and convert again. After a clean boot you can remove stale Linear files.

To apply Linear to specific worlds again later, set their format back to Linear, restart, and confirm that Linear files grow as described under Monitoring.

## Crash-safety window

Delayed writes collect in memory until they are older than the flush interval (10 seconds by default). The flush heartbeat checks every second on every leg, so a crash can lose up to `flush-frequency` + 1 second of edits, about 11 seconds at the default. Background saving did not widen this window; it only stopped slow saves from pausing the game.

A clean stop loses nothing. Shutdown waits for queued writes to finish instead of interrupting them, with a 30-second settle period. Always stop the server cleanly instead of killing it, and keep the verified backup until the next world has run through a save cycle and a restart.
