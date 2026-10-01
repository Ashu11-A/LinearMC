# Commands

Every command Linear adds, what it does, and who may run it. The two stats
commands are described field by field in observability.md; this page covers
the full set plus permissions.

## Permissions

Operators hold these by default. Anyone else needs the exact node;
`linear.command.linear` is the family-wide fallback that also grants
convert, queue, and stats.

| Node | Grants |
| --- | --- |
| `linear.command.linearstats` | `/linearstats` only — nothing else grants it |
| `linear.command.linear` | The whole `/linear` family plus `/linear stats` |
| `linear.command.convert` | `/linear convert` |
| `linear.command.queue` | `/linear queue ...` |

## Stats

- `/linearstats [filter]` — every tracked folder, optionally filtered to paths containing the filter word.
- `/linear stats [world]` — same panel, optionally filtered to one world.

Both are read-only. See observability.md for every field and the health thresholds.

## Convert

`/linear convert <world> [--to-linear|--to-mca] [--level 1..22] [--threads N] [--execute|--dry-run]`

Queues a conversion job for one world. Direction defaults to old-format-to-new, level defaults to 6, threads default to 1. **Dry-run is the default**: without `--execute` the job only reports what it would do and changes nothing. Pass `--execute` to really convert.

Converting rewrites live world data, so treat it like a rollout: quiesced backup with the verification marker first (see the operator manual), one world at a time, then a save cycle and a restart to confirm. Each file converts, is checked, and only then replaces the original; a file that fails validation stops the job with the live world untouched.

## Queue

Conversion jobs run in the background and are managed by job id (the first 8 characters are enough to name one):

- `/linear queue list` — all known jobs.
- `/linear queue status <jobId>` — detail for one job.
- `/linear queue pause <jobId>` / `/linear queue resume <jobId>` — hold or restart a job.
- `/linear queue cancel <jobId>` — stop a job.
- `/linear queue clear` — forget finished jobs.

## Help

`/linear help` prints a plain-words summary of the above in game.
