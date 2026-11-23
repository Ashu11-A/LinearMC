# Limitations and open questions

This page lists what this release does not cover. Read it before a rollout.

## What was not measured

- Tick timing has not been measured with players connected, and there is no side-by-side comparison with the old format on a warm server. Judge a rollout by the lag players feel on a warm server. Cold-start numbers mislead, because a cold cache and slow first compression make the first minutes look worse than steady state.
- Nothing above the default compression level has had a multi-day soak. The highest level has booted cleanly and completed a clean manual save, but the cost of running it for days is unknown.
- Shutdown flush time has a single paired sample, in which the new format took slightly longer. One sample is not enough, so treat shutdown cost as unmeasured.
- The broken-symlink guard has never fired on a real server; only fixtures test it. When it first fires it will stop the server on purpose, so check your symlinks beforehand.

## Where you can run it

- Only local disk on a single node is tested. Shared disks, network filesystems, two servers sharing a world folder and cold archiving are all untested. Never run two servers against the same world folder.
- There is no cold archiver in this release.
- `--forceUpgrade` and `--recreateRegionFiles` work only on the Folia and CanvasMC forks, because Paper 26.x disables them on every host the Horizon plugin runs on.
- Before converting or rolling back, have free space of about two and a half times the current world size, so copies, backups and rewritten files all fit at once.

## Conversion

- In a world set to the new format, leftover old-format region files convert automatically at startup, on every leg, before plugins load. Each file is converted, checked and then deleted; failures get a few retries, and the server stops if errors keep happening. `/linear convert` converts on demand in either direction.
- A live `/linear convert --execute` away from `.mca` never deletes an `.mca` region the server opened since startup (a handle to a deleted `.mca` would lose every later write) and defers Linear regions with unflushed writes. Those files are reported as SKIPPED and kept. To finish them, restart and run the convert again before the region loads. Startup conversion finishes them only in a world set to a linear format, and only into that format.
- Worlds explicitly set to the old format never convert.
- The offline two-way converter used for earlier measurements is not part of this repository.
- Empty zero-byte region files are skipped and reported, never converted. When a single file reports a conversion error, this is usually the cause. A failed file leaves the live world untouched.
- Always confirm a converted copy shows zero differences before trusting it.
- One region file in the live test world has a permanent compression error and cannot be converted. It stays in the old format, and the server regenerates the affected chunk.
- The write path creates no sidecar files for oversized chunks (no `.mcc` files). If an offline conversion left sidecars, copy them along with the region files when you move data by hand.

## Write path edges that lose data

- If a stored chunk header disagrees with the file header, that chunk is regenerated and its old contents are lost. If the chunk matters, restore the region file from backup and note the coordinates from the log.
- Chunks larger than 500 megabytes are rejected, not saved.
- The symlink guard described above stops the server instead of treating a broken link as an empty region and regenerating it.

## Settings and rollback

- Most flush and compression settings take effect as configured; `configuration.md` lists the defaults.
- A hard crash or force-kill can lose up to `flush-frequency` + 1 second of edits (about 11 seconds at the default). The background flush writes on that schedule on every leg, while saves, chunk unloads and a clean `stop` write everything at once.
- The log-flush-batches setting is inert in this release and changes nothing.
- Rollback rung B needs a stock server jar that you supply. The script starts with a placeholder path and stops with instructions until you point it at a real jar. Rung A needs no extra jar. The operator manual explains the rungs, and `concepts.md` defines the terms.
- Every rollback rung needs a verified backup that contains a verification marker and at least one region folder; otherwise the script refuses to run.
- `configuration.md` has the setting defaults and the exact warning lines, and the operator notes have the recovery steps.

## Maintenance

- The code has to be rebased on every new server release. `architecture.md` lists the places that tend to break.
- A compression dictionary was scoped and then parked; this release has none.
