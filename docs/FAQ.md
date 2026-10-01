# Frequently Asked Questions

This page answers common questions in plain English. Details live in the guides linked from docs/README.md.

## What does LinearMC do?

It changes how your world files are stored on disk. Instead of many loosely packed files with wasted padding, each region is saved as one tightly squeezed file. The world itself stays the same, only the storage is smaller.

## Will players notice anything different?

No. Players see the same blocks, items, and world behavior as before. There is no change to gameplay, and Bedrock players connecting through Geyser see the same world too.

## Which servers does it work with?

Today it works with Folia and Canvas on Minecraft 26.1.2. Canvas on 26.2 is in progress, and the Horizon plugin (version 1.0.0) brings the same storage savings to Paper-style servers without rebuilding them.

## How much disk space will I save?

On one large survival world, measurements showed roughly 30 to 45 percent less disk use. Your savings will vary with world size and how much has been explored. You can compare folder sizes and file counts before and after a save cycle.

## Is it safe to use?

It is crash-safe but not crash-proof. Normal saves and clean stops protect your data, but a server that is force-killed in the middle of a save can still lose recent edits. Keep regular backups, as you should with any server.

## What happens if the server crashes?

You may lose only the most recent writes, about one save interval worth per file plus one save already started. With default settings that window is about ten seconds. A clean shutdown loses nothing because it waits for all pending saves to finish.

## Can I go back to the old format?

Yes. You can switch any single world back to the old format at any time, and you can convert back with `/linear convert <world> --to-mca --execute` for going back to a normal server entirely. Rollback always requires a verified backup first, and a full rollback also needs the normal server file from the server maker.

## Do my old world files still work?

Yes. Existing old-format files are left untouched and keep working side by side with the new files. The server reads both kinds, so old chunks keep serving after you switch. Only newly written data uses the new file ending.

## Do map editors and outside tools still work?

Tools that open the old files directly will not see chunks stored in the new files. The game itself and plugins are unaffected, but any outside program that reads world files by itself needs to understand the new format first.

## What compression level should I use?

Use level 6, which is the default for new worlds. Levels 1 through 22 are accepted, with anything out of range falling back to 6. Levels 12 and above are unsafe because in testing the server ran out of shutdown time and lost chunks.

## Why is world generation stuttering?

An older version could pause the game while big files were being rewritten. Saving now runs in the background, so the game no longer waits for it. If you still see stalls, ask whoever runs the server to check the stats command and consider lowering the compression level.

## Does it change gameplay or plugins?

No. Gameplay and the plugin interface are unchanged. Running some worlds in the new format and some in the old format inside one server is fully supported, not a degraded setup.

## Does it work for Bedrock players through Geyser?

Yes. Bedrock clients see the same world as Java players. The storage change is invisible to all clients.

## What does it cost in extra computer power?

The default level 6 is the only setting tested long-term. Higher levels use more computer power per save and are untested over the long term. Test on your own live server before trusting them — the first minutes after boot always look worse than steady play. See the Limits guide.

## Where do I get help?

Start with the guides in the docs folder and the operator notes in the release folder, which cover setup, monitoring, and rollback. In game, operators can run the built-in stats command to see live compression figures and whether new-format files are being written.
