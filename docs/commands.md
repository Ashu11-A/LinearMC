# Commands

This page lists every command Linear adds, what it does and who may run it.
`observability.md` explains each field of the two stats commands.

## Permissions

Operators have all of these by default. Everyone else needs the exact node.
`linear.command.linear` is the fallback for the whole family and also grants
convert, queue and stats. Every leg applies the same rule through one core
function, `LinearPermissions.allows`: `/linear` itself needs any one of the
four nodes, so someone who holds only `linear.command.convert` or
`linear.command.queue` can still reach that subcommand.

| Node | Grants |
| --- | --- |
| `linear.command.linearstats` | `/linearstats` and `/linear stats`; nothing else grants `/linearstats` |
| `linear.command.linear` | The whole `/linear` family plus `/linear stats` |
| `linear.command.convert` | `/linear convert` |
| `linear.command.queue` | `/linear queue ...` |

## Stats

- `/linearstats [filter]` shows every tracked folder, or only paths that contain the filter word.
- `/linear stats [world]` shows the same panel, optionally for one world.

Both are read-only. `observability.md` covers every field and the health thresholds.

## Convert

`/linear convert <world> [--to-linear|--to-mca|--to-blinear] [--level 1..22] [--threads N] [--execute|--dry-run]`

`/linear convert <world> --from mca|v1|v2|v3|blinear|pumpkin|linear|auto --to mca|v1|v2|v3|blinear|pumpkin|linear [--grid 1|2|4|8|16|32] [--level 1..22] [--threads N] [--execute|--dry-run]`

This queues a conversion job for one world. By default it converts from the old format to the new one, at level 6, on 1 thread. **Dry-run is the default**: without `--execute` the job only reports what it would do. Pass `--execute` to convert for real. The explicit form converts any layout to any other; `--to linear` means `v1`, and `--from linear` matches every `.linear` layout. Pumpkin files migrate only through the explicit form, and startup conversion never rewrites them (see `configuration.md`).

Converting rewrites live world data, so treat it like a rollout. Take a quiesced backup with the verification marker first (see the operator manual), convert one world at a time, then run a save cycle and restart to confirm. Each file is converted and checked before it replaces the original. If a file fails validation, the job stops and the live world is left untouched.

On every leg, only one conversion can run per storage folder at a time. A second `--execute` on a folder that is already converting is rejected with `conversion already running for <folder>` and blocks nothing. Folia also runs passes for different worlds one after another on its single convert worker, while Canvas and Horizon run them in parallel.

Progress totals and dry-run candidate lists are estimates. They count union-per-target files matching `r.X.Z.(mca|linear|b_linear)` (toward LINEAR: `.mca + .b_linear`; toward B_LINEAR: `.mca + .linear`; toward ANVIL: `.linear + .b_linear`), honoring `--from` at extension level for explicit jobs. Ambiguous same-coordinate groups and source-beside-target shadows are excluded (the real collection escalates those to FAILED with nothing queued; only file-IO validation could requeue an invalid-shadow orphan). Pumpkin layout detection and folder-dirty skips need file/detector IO and can only shrink the real queue further, so if anything the estimate undercounts. On Folia, legacy and explicit dry-runs both print their candidates through the same path.

Every leg converts all three storage folders of the world (`region/`, `entities/`, `poi/`), so a full revert to `.mca` leaves nothing behind. Converting `entities/` and `poi/` while the server runs touches files the server reopens often, so prefer a quiet server (or convert right after a restart), and follow the backup rule above.

## Queue

Conversion jobs run in the background. You manage them by job id, and the first 8 characters are enough to name one.

- `/linear queue list` lists all known jobs.
- `/linear queue status <jobId>` shows the details of one job.
- `/linear queue pause <jobId>` and `/linear queue resume <jobId>` hold a job and start it again.
- `/linear queue cancel <jobId>` stops a job.
- `/linear queue clear` forgets finished jobs.

## Help

`/linear help` prints a short plain-language summary of these commands in game.
