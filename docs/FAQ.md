# Frequently asked questions

Plain answers to common questions. The guides listed in `docs/README.md` have the details.

## What does LinearMC do?

It changes how world files are stored on disk. Instead of region files padded out with unused space, each region is saved as one tightly compressed file. The world stays the same; only the storage gets smaller.

## Will players notice anything?

No. Players see the same blocks, items and world behavior as before, and gameplay does not change. Bedrock players connecting through Geyser see the same world too.

## Which servers does it work with?

Folia 26.3 and CanvasMC 26.3, as server forks. The Horizon plugin (version 1.0.0) brings the same storage to Paper, Folia and CanvasMC 26.1 through 26.3 without rebuilding the server, and it is the option for Folia 26.1.2 hosts. The CanvasMC 26.1.2 and 26.2 forks were archived in v1.1.4 and are no longer built.

## How much disk space will I save?

On one large survival world, disk use dropped by roughly 30 to 45 percent. Your result depends on the size of the world and how much of it has been explored. To check, compare folder sizes and file counts before and after a save cycle.

## Is it safe?

It is crash-safe, not crash-proof. Normal saves and clean stops protect your data, but a server killed in the middle of a save can still lose recent edits. Keep regular backups, as you would for any server.

## What happens if the server crashes?

You can lose the most recent edits: up to `flush-frequency` plus one second, which is about 11 seconds with the default settings. A clean shutdown loses nothing, because it waits for every pending save to finish.

## Can I go back to the old format?

Yes. You can switch any world back to the old format at any time, and `/linear convert <world> --to-mca --execute` converts its files back if you want to return to a normal server completely. Every rollback needs a verified backup first, and a full rollback also needs the stock server jar from the upstream project.

## Do my old world files still work?

Yes. Existing old-format files are left alone and keep working next to the new files. The server reads both kinds, so old chunks keep loading after you switch. Only newly written data uses the new file extension.

## Do map editors and other outside tools still work?

Tools that open region files directly will not see chunks stored in the new files. The game and plugins are unaffected, but any external program that reads world files on its own has to support the new format first.

## What compression level should I use?

Use level 6, the default. Levels 1 through 22 are accepted, and anything out of range falls back to 6. Levels 12 and above are unsafe: in testing, the server ran out of shutdown time and lost chunks.

## Why does world generation stutter?

An older version could pause the game while big files were rewritten. Saving now runs in the background, so the game no longer waits for it. If you still see stalls, have the server admin check the stats command and consider a lower compression level.

## Does it change gameplay or plugins?

No. Gameplay and the plugin API are unchanged. You can run some worlds in the new format and others in the old format on the same server, and that setup is fully supported.

## Does it work for Bedrock players through Geyser?

Yes. Bedrock clients see the same world as Java players; the storage change is invisible to every client.

## How much extra CPU does it use?

Level 6, the default, is the only level tested over a long period. Higher levels use more CPU per save and have not had long-term testing. Test them on your own live server before relying on them, and keep in mind that the first minutes after boot always look worse than steady play. See `limitations.md`.

## Where do I get help?

Start with the guides in `docs/` and the operator notes in `release/`, which cover setup, monitoring and rollback. In game, operators can run the stats command to see live compression figures and check that new-format files are being written.
