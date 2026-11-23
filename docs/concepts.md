# Core concepts

This page defines the terms used across these docs in plain language. To set up
a server you only need this page and the operator manual
(`release/OPERATOR-NOTES.md`). You don't need any programming knowledge.

## Glossary

- **Chunk**: one small square piece of the game world. The game loads and saves the world one chunk at a time, and everything players build lives inside some chunk.
- **Region**: a group of 32 by 32 chunks stored together in one file. The file name records its position as `r.X.Z`. Grouping chunks this way avoids thousands of tiny separate files.
- **ANVIL**: the game's normal region format, with files ending in `.mca`. It is well tested and almost every world tool can read it.
- **LINEAR**: the compact format this project adds, with files ending in `.linear`. Each region is compressed more tightly, so the world takes less disk space. The game plays exactly the same either way.
- **B_LINEAR**: the buffered format. Each `.b_linear` master file has a `.b_linear.swp` swap file beside it. Single-chunk writes go to the swap straight away, and the master is rebuilt at flush time. The two files belong together: never move, copy or delete one without the other.
- **Bucket and grid**: V2 and V3 files split the 32 by 32 region into a grid of buckets (8 by 8 by default). Each bucket is compressed on its own and has its own integrity hash, so a rewrite touches less data while the chunks stay the same.
- **Flush heartbeat**: a repeating background task that writes out waiting changes on the flush-frequency schedule, so durability never depends on one save call. A clean stop flushes everything, however recent.
- **Compression and compression level**: compression packs saved world data so it uses less disk. The level runs from 1 to 22; higher levels give smaller files but slower saves. Level 6 is the default and the recommended value. Levels 12 and above are unsafe, because saves can take so long that recent work is lost.
- **Flush and flush window**: saving happens in two steps. The game first holds changes in memory, then writes them to disk. The write is the flush, and the flush window is the short period in which the waiting changes are written out together.
- **Dirty file**: a saved file that still has newer changes waiting in memory. It is not damaged, only behind until the next flush finishes. A clean shutdown leaves no dirty files.
- **Superblock**: a small header at the start of a LINEAR file that records what kind of file it is and how it was packed. The server checks it on open so it never mistakes one format for another.
- **Region folders**: each world keeps saved data in three matching folders. `region` holds the terrain, `poi` holds points of interest such as village data, and `entities` holds mobs and other moving things. All three can use the compact format.
- **Dry-run**: a practice run that inspects and reports but changes nothing on disk. Use it to preview a conversion or cleanup.
- **Server file**: the single ready-to-run file you start (a paperclip jar), which already contains everything the server needs. You build it once and then launch it.
- **Server variant**: the same compact saving added to different server programs. Folia is the main supported server and spreads work across many processor cores. Canvas is a community variant of Folia. Horizon is an add-on that brings the same saving to Paper-style servers without replacing them.
- **Disk-writer lane**: the server writes to disk through a single lane, so one slow save used to hold up everything behind it. Background saving removed that stall.
- **Crash-safe vs crash-proof**: crash-safe means a normal stop or restart keeps saved work intact. Crash-proof would mean no sudden failure could ever lose anything, and this project does not promise that. If the machine is killed in the middle of a save, very recent changes can be lost, so keep backups.
- **Save cycle**: the server's regular schedule for writing waiting changes to disk. Linear files are updated on this schedule, not on every edit.
- **Rollback option**: one of three ways back, from lightest to heaviest: switch worlds to the old format; also convert the files back and swap in a normal server file; or re-apply Linear. The operator manual has the details.
- **Sidecar file**: a helper file some tools write next to a region file for chunks too big to fit inside it. Linear never creates these when it saves, but if you have any, copy them along with the region files when you move data by hand.
- **Backup marker**: a small dated file written into a backup snapshot. It is the proof that the backup is real; never start a revert without it.
- **Zero-byte file**: an empty region file, usually left behind by a crashed save. The converter skips these and logs them instead of converting them. Delete or restore them before converting.
- **Cold archiver**: a tool that would move old, untouched world data to cheaper storage. This release does not include one.
