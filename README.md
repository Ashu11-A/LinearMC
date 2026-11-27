<div align="center">

# LinearMC

![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-302D41?style=for-the-badge&color=302D41&labelColor=f9e2af&logoColor=302D41)
![Stars](https://img.shields.io/github/stars/Ashu11-A/LinearMC?style=for-the-badge&color=302D41&labelColor=f9e2af&logoColor=302D41)
![Last Commit](https://img.shields.io/github/last-commit/Ashu11-A/LinearMC?style=for-the-badge&color=302D41&labelColor=b4befe&logoColor=302D41)
![Repo Size](https://img.shields.io/github/repo-size/Ashu11-A/LinearMC?style=for-the-badge&color=302D41&labelColor=90dceb&logoColor=302D41)

![Tests](https://img.shields.io/github/actions/workflow/status/Ashu11-A/LinearMC/test.yml?label=tests&style=for-the-badge&color=302D41&labelColor=a6e3a1&logoColor=302D41)
![Java 25](https://img.shields.io/badge/java-25-302D41?style=for-the-badge&color=302D41&labelColor=f9e2af&logoColor=302D41)
![Folia 26.3](https://img.shields.io/badge/folia-26.3-302D41?style=for-the-badge&color=302D41&labelColor=a6e3a1&logoColor=302D41)
![Canvas 26.3](https://img.shields.io/badge/canvas-26.3-302D41?style=for-the-badge&color=302D41&labelColor=89b4fa&logoColor=302D41)
![Horizon 1.0.0](https://img.shields.io/badge/horizon-1.0.0-302D41?style=for-the-badge&color=302D41&labelColor=cba6f7&logoColor=302D41)

<br>
<br>

<p align="center">
<strong>Minecraft worlds that take less disk space and play exactly the same.</strong>
<br>
<sub>
LinearMC stores each region as one compressed <code>.linear</code> file
instead of a padded file of 4 KiB sectors. On large survival worlds that saves
up to <strong>45% of disk</strong>. Java players, Bedrock players and plugins
all see the same world, and a crash no longer leaves player inventories out of
step with the chunks around them.
</sub>
</p>

</div>

---

## At a glance

* Region files become compact `.linear` files. Gameplay does not change.
* Existing `.mca` files keep loading next to the new `.linear` files.
* Any world can go back to the normal format, and once it has, you can swap in the stock server jar.
* `/linearstats` or `/linear stats` shows live compression figures in game.
* A player file is written only after the chunks of the player's region reach the disk, so a crash cannot delete an item the player just put in a chest.
* `save-all flush` returns only when the world is on disk, and leaves a marker file that backup scripts can check.

## Compatibility

* **Folia 26.3**: supported. On Folia 26.1.2, use the Horizon plugin; the 26.1.x fork is archived.
* **CanvasMC 26.3**: supported.
* **Horizon plugin 1.0.0**: one jar for Paper, Folia and CanvasMC 26.1 through 26.3. It adapts to the host and needs no server rebuild. [What is Horizon?](https://github.com/CraftCanvasMC/Horizon)

All three legs ship the same features: format, commands, permissions, config keys, startup conversion, and save/stop durability. The contract lives in `parity/capabilities.yml`, and a release is refused if any leg is missing a feature.

## Quick start

You don't need to compile anything. Every server type has a ready-to-run jar on the [GitHub Releases page](https://github.com/Ashu11-A/LinearMC/releases).

1. Install **Java 25** and check it with `java -version`.
2. Download the jar for your server type (see Compatibility).
3. Start the server with the Linear flag. The flag takes priority over the config files, so regions are written as Linear from the first boot and you don't have to boot, stop and edit configs first. On a brand-new server, accept the EULA, then start it again:

   ```bash
   java -Dlinearmc.format=LINEAR -jar <your-downloaded-jar> --nogui
   ```

4. After a save, new `.linear` files appear in the world folder.
5. Run `/linearstats` or `/linear stats` in game to see compression figures.

To build from source, see the single build command in `CONTRIBUTING.md`.

## Commands

| Command | What it does |
| --- | --- |
| `/linearstats [filter]` | Live compression figures for every tracked folder, optionally filtered |
| `/linear stats [world]` | Same figures, optionally limited to one world |
| `/linear convert <world> [--to-linear\|--to-mca\|--to-blinear] [--level 1..22] [--threads N] [--execute\|--dry-run]` | Queues a world conversion job; dry-run is the default, `--execute` really converts. Explicit any-to-any form: `--from mca\|v1\|v2\|v3\|blinear\|pumpkin\|linear\|auto --to ... [--grid 1\|2\|4\|8\|16\|32]` (`b_linear` also accepted; `--to linear` means V1) |
| `/linear queue list\|status\|pause\|resume\|cancel\|clear` | Manages background conversion jobs by id |
| `/linear help` | Prints an in-game summary of the above |

Operators have every permission by default. Everyone else needs the exact node; `docs/commands.md` lists them.

## Startup parameters

There are four JVM flags. Put them on the `java` command line before `-jar`. A valid flag overrides the matching config value. If a flag is misspelled, blank or missing, the config file decides, and if the config has no value either, the shipped default applies. Changes take effect after a restart.

| Parameter | Values | Effect |
| --- | --- | --- |
| `-Dlinearmc.format` | `LINEAR`, `B_LINEAR` or `ANVIL` (any case) | Format for newly written regions. |
| `-Dlinearmc.compression-level` | `1`–`22` | Compression for newly written regions, overriding the config default of `6`. Levels `12` and above are unsafe. |
| `-Dlinearmc.implementation` | `V1`, `V2` or `V3` (any case) | Writer used inside newly written `.linear` files (see Versions). Unknown values log a warning and write `V1`. Does not affect `.b_linear` files. |
| `-Dlinearmc.grid-size` | `1`, `2`, `4`, `8`, `16` or `32` | Bucket grid for `V2`/`V3` writes, overriding the config default of `8`. Ignored by `V1`. |

Examples:

```bash
java -Xmx4G -Dlinearmc.format=LINEAR -jar linearmc-folia-26.3-<version>.jar --nogui
java -Xmx4G -Dlinearmc.format=LINEAR -Dlinearmc.implementation=V2 -Dlinearmc.grid-size=8 -jar linearmc-folia-26.3-<version>.jar --nogui
java -Xmx4G -Dlinearmc.format=B_LINEAR -jar linearmc-folia-26.3-<version>.jar --nogui
```

## Settings

The `region-format` section of `paper-world-defaults.yml` holds every setting, and each world can override it.

```yaml
region-format:
  format: LINEAR
  linear:
    compression-level: 6
    implementation: V1
    grid-size: 8
    save-off-max-duration: 120
    durable-player-data: true
    coupled-save:
      enabled: true
      interval: 20
```

`format` takes `LINEAR` for compact files, `B_LINEAR` for the buffered tier, or `ANVIL` for normal files. The match is exact, so a misspelled value logs a warning and that world runs on Anvil. The setting only affects new files.

`compression-level` accepts 1 to 22. Keep it at 6 unless you accept the risk, and never go to 12 or higher. `implementation` picks the writer for new `.linear` files (`V1`, `V2` or `V3`, see Versions), and `grid-size` sets the `V2`/`V3` bucket grid. The `-Dlinearmc.*` flags set these four without editing any file (see Startup parameters).

The last three keys control crash safety (see the next section). `save-off-max-duration` turns saving back on, with a warning to operators, when a `save-off` has lasted that many seconds; `0` disables the limit. On the forks these keys live in the server's global config (`paper-global.yml` on Folia, `canvas-server.yml` on CanvasMC), and on Horizon in `plugins/LinearMC/config.yml`. `docs/configuration.md` lists the exact paths.

## Crash safety

A vanilla server saves the world and the players on separate timers. If it dies between the two saves, a player can come back with an inventory from after a move and a chest from before it, and the item is gone. LinearMC closes that gap:

* With `coupled-save.enabled` (on by default), each region saves its chunks every `interval` seconds and keeps its players' files in memory until those chunks are on disk. Logout, `save-all` and a player leaving the region use the same order. A crash can still roll a player back a few seconds together with the world, and at worst duplicate an item moved just before it, but it does not lose one.
* `save-all flush` waits until every region file is written, renamed and synced, then writes `<world>/linear-checkpoint/save-all.done`.
* Player files (`.dat`, stats, advancements) are replaced with one atomic rename (`durable-player-data`), so a crash never leaves one half-written.
* After each region rename the folder itself is synced, so a power cut cannot undo the rename. A zero-length region file is moved aside to `.corrupt-<ms>`, and an unreadable one is copied to `<dimension>/linear-conflicts/` before anything else touches it.

The ordering holds within a region. It is not a transaction across regions, and `docs/limitations.md` lists the cases it does not cover.

## Versions

There are three writers and two compatibility cases. Readers accept every version, so worlds can mix them and you can convert later with `/linear convert`.

* **V1** (default) writes one compressed blob per region (`.linear`). It is the reference format; use it unless you have a reason not to.
* **V2** is a bucketed writer. It splits the 32×32 region into a grid of buckets (8×8 by default), each compressed separately with its own integrity hash, so small edits rewrite less data. It keeps the `.linear` extension and is compatible with DivineMC in both directions. A file stays V2 until it is rewritten; if you set the implementation back, the next flush writes it as V1.
* **B_LINEAR** is the buffered tier: a `.b_linear` master with a `.b_linear.swp` swap file beside it. Single-chunk writes go to the swap straight away and the master is rebuilt at flush, so after a crash there is a second copy of recent edits. In steady state only the master is on disk. It suits write-heavy worlds where you want that extra crash window. Never move, copy or delete a master without its swap file.
* **V3** (advanced) is DivineMC's wire-4 bucketed variant, selected and sized exactly like V2. Most operators never need it.
* **Pumpkin** (compatibility): files from the Pumpkin server share V1's magic and `.linear` extension and are detected automatically on open. LinearMC never rewrites them on its own; convert them explicitly if you want to.

Choose with Java flags (a flag beats the config, and the config beats the default):

```bash
java -Dlinearmc.format=LINEAR -Dlinearmc.implementation=V1 -jar <jar> --nogui
java -Dlinearmc.format=LINEAR -Dlinearmc.implementation=V2 -Dlinearmc.grid-size=8 -jar <jar> --nogui
java -Dlinearmc.format=B_LINEAR -jar <jar> --nogui
```

## Reverting

You are never locked in, and the conversion commands handle reverting without any script:

1. **Stop writing Linear.** Set the world's format back to `ANVIL` (on Horizon, `region-format.format: ANVIL` in `plugins/LinearMC/config.yml`), or start with `-Dlinearmc.format=ANVIL`. Existing `.linear` files still load through the multi-format reader; new writes go to `.mca`.
2. **Convert the files back.** Run `/linear convert <world> --to-mca --execute` in game. Each file is converted and validated before it replaces the original. Without `--execute`, the command only reports what it would do. Convert one world at a time, then run a save cycle and restart to confirm.
3. **Leave completely.** Once every world is back on `.mca`, swap in the stock server jar.

Back up every world before you start, and keep the backup until players confirm the world is intact.

## Safety and limits

* The default compression level is **6**. Levels **12 and above are unsafe** and have caused chunk loss in testing.
* Tools that read only `.mca` files cannot see chunks stored in `.linear` files.
* A crash still loses the last few seconds of play, for the world and the players alike. A disk that fails, or a host without power-loss protection, can lose more. Keep backups.

## Documentation

* `docs/` covers concepts, settings, operations, limits, health checks and performance.
* `docs/architecture.md` explains how the save path connects to the format.
* `release/OPERATOR-NOTES.md` covers opting worlds out, rolling back and watching health.
* `CONTRIBUTING.md` covers patches, ports and releases. Read it before you change build, format or release behavior.

## Credits

* [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools)
  and [LinearPaper](https://github.com/xymb-endcrystalme/LinearPaper): the format and the reference implementation.
* [Kaiiju](https://github.com/KaiijuMC/Kaiiju): the dual-format architecture.
* [paper-zstd](https://github.com/UltraVanilla/paper-zstd): the codec constraints.
* Spottedleaf's [SectorTool](https://github.com/PaperMC/SectorTool): a spec reference, and the GC rules for hot-path buffers that came out of its review.
* [DivineMC](https://github.com/BX-Team/DivineMC): the V2/V3 bucketed layout (wire bytes 3 and 4) and the per-version codec structure. LinearMC is wire-compatible with it in both directions.
* [Pumpkin](https://github.com/Pumpkin-MC/Pumpkin) (a Rust server): the Pumpkin bucketed layout (wire byte 2, dense buckets), used as the structural reference for detection and conversion.
* LinearPurpur: the practice of a hot per-chunk zstd level on the write path next to the tick.
* [Paper](https://github.com/PaperMC/Paper) / [Folia](https://github.com/PaperMC/Folia)
  and [Canvas](https://github.com/CraftCanvasMC/Canvas) /
  [Horizon](https://github.com/CraftCanvasMC/Horizon): the supported server lines.

## License

Copyright (C) 2026 Ashu11-A.

LinearMC is free software under the GNU General Public License v3.0, the same license as the Paper, Folia and Kaiiju line it builds on. The full license text is in [LICENSE](LICENSE), and the copyright notice is in [NOTICE](NOTICE).
