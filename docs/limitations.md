# Limitations and open questions

What this release does not cover. Read it before a rollout, not after.

## What was not measured

- No live tick timing with players connected, and no side-by-side comparison with the old format on a warm server. Judge a rollout on warm-server player-facing lag, not on cold-start numbers, since cold cache and slow first compression make the first minutes look worse than steady state.
- No multi-day soak above the default compression level. The highest level has clean boots and a clean manual save behind it, but the cost of running it for days is still unmeasured.
- Shutdown flush time has only one paired sample, showing the new format taking slightly longer. A single sample proves nothing, so treat shutdown cost as unmeasured.
- The broken-symlink guard has never fired on a real server. It is tested with fixtures only. The first live fire will stop the server on purpose, so treat it as a drill and check symlinks first.

## Where you can run it

- Local disk on a single node only. Shared disks, network filesystems, two servers sharing one world folder, and cold archiving are all untested. Never run two servers against the same world folder.
- There is no cold archiver in this release.
- Plan on about two and a half times the current world size in free space before converting or rolling back, so copies, backups, and rewritten files all fit at once.

## Conversion

- The only converter in this repository runs automatically at startup. When a world is set to the new format, leftover old-format region files are converted, checked, and then deleted, with a few retries and a full stop if errors keep happening.
- Worlds explicitly set to the old format never convert.
- The offline two-way converter used for earlier measurements is not part of this repository.
- Empty zero-byte region files are skipped and reported, never converted. They are usually the cause when a single file reports a conversion error, and a failed file leaves the live world untouched.
- Always confirm a converted copy shows zero differences before trusting it.
- One live region file has a permanent compression error and cannot be converted. It stays in the old format and the server regenerates the affected chunk.
- The write path creates no sidecar files for oversized chunks (there are no `.mcc` files), so carry any sidecars from an offline conversion along with manual copies.

## Write path edges that lose data

- If a stored chunk header disagrees with the file header, that chunk is regenerated and its old contents are lost. If the chunk matters, restore the region file from backup and note the coordinates from the log.
- Chunks larger than 500 megabytes are rejected rather than saved.
- The symlink guard described above stops the server rather than risk treating a broken link as an empty region and regenerating it.

## Settings and rollback

- Most flush and compression settings are accepted and take effect. See the configuration reference for defaults.
- The log-flush-batches setting is inert in this release and changes nothing.
- Rollback rung B needs a stock server jar that you supply yourself. The script points at a placeholder path and stops with instructions until you point it somewhere real. Rung A needs no extra jar. (Rungs explained in the operator manual; terms in concepts.md.)
- Every rollback rung needs a verified backup. It must contain a verification marker and at least one region folder, or the script will refuse to run.
- For setting defaults and the exact warning lines, see the configuration reference. For recovery steps, see the operator notes.

## Maintenance

- The code must be rebased on each new server release. The places that tend to break are listed in the architecture document.
- Planned work on a compression dictionary was scoped and parked. There is no dictionary in this release.
