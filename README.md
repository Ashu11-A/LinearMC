<div align="center">

# LinearMC

![License](https://img.shields.io/github/license/Ashu11-A/LinearMC?style=for-the-badge&color=302D41&labelColor=f9e2af&logoColor=302D41)
![Stars](https://img.shields.io/github/stars/Ashu11-A/LinearMC?style=for-the-badge&color=302D41&labelColor=f9e2af&logoColor=302D41)
![Last Commit](https://img.shields.io/github/last-commit/Ashu11-A/LinearMC?style=for-the-badge&color=302D41&labelColor=b4befe&logoColor=302D41)
![Repo Size](https://img.shields.io/github/repo-size/Ashu11-A/LinearMC?style=for-the-badge&color=302D41&labelColor=90dceb&logoColor=302D41)

![Tests](https://img.shields.io/github/actions/workflow/status/Ashu11-A/LinearMC/test.yml?label=tests&style=for-the-badge&color=302D41&labelColor=a6e3a1&logoColor=302D41)
![Java 25](https://img.shields.io/badge/java-25-302D41?style=for-the-badge&color=302D41&labelColor=f9e2af&logoColor=302D41)
![Folia 26.1](https://img.shields.io/badge/folia-26.1-302D41?style=for-the-badge&color=302D41&labelColor=a6e3a1&logoColor=302D41)
![Canvas 26.1-26.2](https://img.shields.io/badge/canvas-26.1--26.2-302D41?style=for-the-badge&color=302D41&labelColor=89b4fa&logoColor=302D41)
![Horizon 1.0.0](https://img.shields.io/badge/horizon-1.0.0-302D41?style=for-the-badge&color=302D41&labelColor=cba6f7&logoColor=302D41)

<br>

<p align="center">
<strong>Minecraft worlds use less disk space — with no change to play.</strong>
<br>
<sub>
LinearMC stores each region as one tightly compressed <code>.linear</code> file
instead of many small padded pieces. You save up to <strong>45% disk</strong>
on large survival worlds. Players, plugins, and Bedrock players all see the same world.
</sub>
</p>

</div>

---

## ✨ At a glance

* **What changes:** region files become compact `.linear` files; gameplay stays identical.
* **What stays:** old `.mca` files keep working side by side with new `.linear` files.
* **Way back:** any world can switch back to the normal format; swapping in the stock server jar completes leaving these custom server files entirely.
* **Proof:** check live compression figures in game with `/linearstats` or `/linear stats`.

## 🧩 Compatibility

* **Folia 26.1.2** — supported.
* **Canvas 26.1.2, 26.2** — supported, in progress.
* **Horizon plugin 1.0.0** — supported, no server rebuild needed. [What is Horizon?](https://github.com/CraftCanvasMC/Horizon).

## 🚀 Quick start

You do **not** need to compile anything. Every server type ships as a ready-to-run file on the [GitHub Releases page](https://github.com/Ashu11-A/LinearMC/releases).

1. Install **Java 25** and check it with `java -version`.
2. Download the file for your server type (see Compatibility above).
3. Start the server once with the Linear flag — no config editing, no boot-stop-configure cycle. The startup flag beats the config files, so regions write Linear from the very first boot. Accept the EULA if this is a brand-new server, then let it boot:

   ```bash
   java -Dlinearmc.format=LINEAR -jar <your-downloaded-jar> --nogui
   ```

4. After a save, look for new `.linear` files in your world folder.
5. Check live compression figures in game with `/linearstats` or `/linear stats`.

If you want to build from source instead, `CONTRIBUTING.md` documents the one build command.

## ⌨️ Commands

| Command | What it does |
| --- | --- |
| `/linearstats [filter]` | Live compression figures for every tracked folder, optionally filtered |
| `/linear stats [world]` | Same figures, optionally limited to one world |
| `/linear convert <world> [--to-linear\|--to-mca] [--level 1..22] [--threads N] [--execute\|--dry-run]` | Queues a world conversion job; dry-run is the default, `--execute` really converts |
| `/linear queue list\|status\|pause\|resume\|cancel\|clear` | Manages background conversion jobs by id |
| `/linear help` | Prints an in-game summary of the above |

Operators hold every permission by default; anyone else needs the exact node (`docs/commands.md` lists them all).

## 🏁 Startup parameters

Two JVM flags, passed on the `java` command line before `-jar`. Each one beats the matching config-file value; if the flag is absent the config file wins, and if that is absent too the safe shipped default applies. A restart is required after changing them.

| Parameter | Values | Effect |
| --- | --- | --- |
| `-Dlinearmc.format` | `LINEAR` or `ANVIL` | Format for newly written regions. Unknown or absent values fall back to `ANVIL`. On Horizon this flag is the whole on/off switch. |
| `-Dlinearmc.compression-level` | `1`–`22` | Compression for newly written regions, overriding the config default of `6`. Levels `12` and above are unsafe. |

Example:

```bash
java -Xmx4G -Dlinearmc.format=LINEAR -jar folia-linear-26.1.2-<version>.jar --nogui
```

## ⚙️ Settings

You control everything with two settings under `region-format` in `paper-world-defaults.yml`. You can override them per world.

```yaml
region-format:
  format: LINEAR
  linear:
    compression-level: 6
```

Set `format` to `LINEAR` for compact files or `ANVIL` for normal files. It only affects new files. Set `compression-level` from 1 to 22. Stay at 6 unless you accept risk. Stay below 12. In a hurry? `-Dlinearmc.format=LINEAR` on the command line sets the format without touching any file (see Startup parameters).

## ↩️ Reverting

You are never locked in, and reverting needs no script — the conversion commands above handle it:

1. **Stop writing Linear:** set the world's format back to `ANVIL` (or drop `-Dlinearmc.format` on Horizon). Existing `.linear` files keep loading through the dual reader; only new writes go to `.mca`.
2. **Convert files back:** run `/linear convert <world> --to-mca --execute` in game. Each file is converted, validated, and only then replaces the original; without `--execute` it just reports what it would do. Do one world at a time, then run a save cycle and restart to confirm.
3. **Leave entirely:** once every world is back on `.mca`, swap in the stock server jar.

Back up every world before any revert step and keep the backup until players confirm the world is intact.

## 🛡️ Safety & limits

* The default compression level is **6**. Levels **12 and above are unsafe** and have caused chunk loss in testing.
* Tools that only read `.mca` files cannot see chunks stored in `.linear` files.
* LinearMC is crash-safe, not crash-proof. A server killed mid-save can still lose recent edits. **Keep backups.**

## 📚 Docs map

* `docs/` holds concepts, settings, operations, limits, checks, and speed notes.
* `docs/architecture.md` explains how saving connects to the format.
* `release/OPERATOR-NOTES.md` explains how to opt worlds out, roll back, and watch health.
* `CONTRIBUTING.md` explains patches, ports, and releases.

## 🤝 Contributing

Patches, ports, and releases are documented in `CONTRIBUTING.md`. Start there before changing build, format, or release behavior.

## 📜 License and credits

GPL-3.0, matching the Paper/Folia/Kaiiju lineage.

Full text: [LICENSE](LICENSE).

* [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools)
and [LinearPaper](https://github.com/xymb-endcrystalme/LinearPaper) for the
format and the reference implementation.
* [Kaiiju](https://github.com/KaiijuMC/Kaiiju) for the dual-format architecture.
* [paper-zstd](https://github.com/UltraVanilla/paper-zstd) for the codec
constraints.
* Spottedleaf's [SectorTool](https://github.com/PaperMC/SectorTool) as a spec
reference, and the GC rules established during its review for hot-path buffers.
