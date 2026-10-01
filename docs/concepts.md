# Core Concepts

This page explains the words used across these docs in plain language.
If you are setting up a server you only need this page and the setup guide
(release/OPERATOR-NOTES.md).
No programming knowledge is needed to follow along.

## Glossary

- **Chunk** – A chunk is one small square piece of the game world. The game loads and saves the world one chunk at a time. Everything players build lives inside some chunk.
- **Region** – A region is a group of 32 by 32 chunks stored together as one file on disk. Its file name records its position, in the form r.X.Z. Grouping chunks this way keeps thousands of tiny pieces from becoming thousands of separate files.
- **ANVIL** – ANVIL is the normal, default way the game stores region files. Those files end in .mca. It is well tested and read by almost every world tool.
- **LINEAR** – LINEAR is the compact alternative offered by this project, with files ending in .linear. It squeezes each region file more tightly so the world takes up less disk space. The game itself plays exactly the same either way.
- **Compression and compression level** – Compression means packing saved world data smaller so it uses less disk space. The level runs from 1 to 22, where higher numbers mean smaller files but slower saving. Level 6 is the default and recommended choice, while levels 12 and above are unsafe because saving can take too long and recent work can be lost.
- **Flush and flush window** – Saving happens in two steps: first the game holds changes in memory, then it writes them to disk. That writing step is called a flush. The flush window is the short period when waiting changes are being written out together.
- **Dirty file** – A dirty file is a saved file that still has newer changes waiting in memory. It is not damaged, it is just behind until the next flush finishes. A clean shutdown leaves no dirty files.
- **Superblock** – A superblock is a small fingerprint at the start of a LINEAR file. It records what kind of file this is and how it was packed. The server checks it on startup so it never confuses one format for another.
- **Region folders** – Worlds keep their saved data in three matching folders: region, poi, and entities. The region folder holds the land itself, poi holds points of interest like village details, and entities holds creatures and other moving things. All three can use the compact format.
- **Dry-run** – A dry-run is a practice run that looks and reports but changes nothing. It is used to preview what a conversion or cleanup would do. Nothing on disk is added, removed, or rewritten during a dry-run.
- **Server file** – The single ready-to-run server file you start. It already contains everything the server needs packed inside (this kind of file is called a paperclip jar). You build it once, then launch it to play.
- **Server variant** – The same compact saving taught to different server programs. Folia is the main supported server, built to spread work across many processors. Canvas is a community variant of Folia. Horizon is an add-on that brings the same saving to Paper-style servers without replacing them.
- **Disk-writer lane** – The server writes to disk through a single lane, so one slow save used to pause everything behind it. Background saving fixed that pause.
- **Crash-safe vs crash-proof** – Crash-safe means a normal stop or restart keeps saved work intact. Crash-proof would mean no sudden failure could ever lose anything, which this project does not promise. If the machine is force-killed in the middle of a save, very recent changes can still be lost, so keep backups.
- **Save cycle** – The server's regular rhythm of writing waiting changes to disk. Linear files update on this rhythm, not on every edit.
- **Rollback option** – One of three ways back, from lightest to heaviest: just flip worlds to the old format; additionally convert files back and swap in a normal server file; or re-apply Linear. See the operator manual.
- **Sidecar file** – A helper file some tools write next to a region file for chunks too big to fit inside. Linear never creates these when it saves; carry any you have alongside manual copies.
- **Backup marker** – A small proof file written into a backup snapshot with the date. Never start a revert without it, as proof the backup is real.
- **Zero-byte file** – A region file with nothing in it, usually left by a crashed save. The converter skips these loudly instead of converting them; delete or restore them before converting.
- **Cold archiver** – A tool that would move old, untouched world data to cheaper storage. This release has none.
