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

`/linear convert <world> [--to-linear|--to-mca|--to-blinear] [--level 1..22] [--threads N] [--execute|--dry-run]`

`/linear convert <world> --from mca|v1|v2|v3|blinear|pumpkin|linear|auto --to mca|v1|v2|v3|blinear|pumpkin|linear [--grid 1|2|4|8|16|32] [--level 1..22] [--threads N] [--execute|--dry-run]`

Queues a conversion job for one world. Direction defaults to old-format-to-new, level defaults to 6, threads default to 1. **Dry-run is the default**: without `--execute` the job only reports what it would do and changes nothing. Pass `--execute` to really convert. The explicit form copies any layout to any layout; `--to linear` means `v1` and `--from linear` matches every `.linear` layout. Pumpkin files only migrate through the explicit form, and startup conversion never rewrites them (see configuration.md).

Converting rewrites live world data, so treat it like a rollout: quiesced backup with the verification marker first (see the operator manual), one world at a time, then a save cycle and a restart to confirm. Each file converts, is checked, and only then replaces the original; a file that fails validation stops the job with the live world untouched.

Only one convert may run per storage folder at a time (Canvas and Horizon). A second `--execute` on an in-flight folder is rejected with `conversion already running for <folder>` — it queues nothing and blocks nothing. Folia serializes converts through its single-thread worker instead, so no second pass can overlap there.

Progress totals and dry-run candidate lists are estimates only. They count union-per-target files matching `r.X.Z.(mca|linear|b_linear)` (toward LINEAR: `.mca + .b_linear`; toward B_LINEAR: `.mca + .linear`; toward ANVIL: `.linear + .b_linear`), honoring `--from` at extension level for explicit jobs. Ambiguous same-coordinate groups and source-beside-target shadows are excluded (the real collection escalates those to FAILED with nothing queued; only file-IO validation could requeue an invalid-shadow orphan). Pumpkin layout detection and folder-dirty skips need file/detector IO and can only shrink the real queue further, so when in doubt the estimate undercounts. Folia legacy and explicit dry-runs both echo candidates on the same path.

Scope is intentional and differs by leg: Horizon converts the single `region/` folder; Canvas and Folia convert all three (`region/`, `entities/`, `poi/`). Do not "fix" the Horizon scope.

## Queue

Conversion jobs run in the background and are managed by job id (the first 8 characters are enough to name one):

- `/linear queue list` — all known jobs.
- `/linear queue status <jobId>` — detail for one job.
- `/linear queue pause <jobId>` / `/linear queue resume <jobId>` — hold or restart a job.
- `/linear queue cancel <jobId>` — stop a job.
- `/linear queue clear` — forget finished jobs.

## Help

`/linear help` prints a plain-words summary of the above in game.
