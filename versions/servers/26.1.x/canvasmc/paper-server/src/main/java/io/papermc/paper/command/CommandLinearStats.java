package io.papermc.paper.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionTimings;
import net.linear.command.LinearStatsCompact;
import net.linear.command.LinearStatsHealth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Linear linearstats: compact pull-only Adventure panel (proposal A).
 *
 * <p>One row per folder with an inline world tag, e.g.
 * {@code world L6 reg d12/512 f0 p99 12.3ms 42% 3.1s W4.2s}.
 * No printf-aligned columns: Minecraft chat is proportional, so alignment
 * would not hold visually. Plain text comes from the JDK-only
 * {@link LinearStatsCompact}; this leg only maps health to colour:
 * {@code OK/WARN/CRIT -> GREEN/YELLOW/RED}, worlds/levels/numbers BLUE,
 * unknown {@code na} gray. Empty state keeps the exact legacy line.
 * Permission stays {@code linear.command.linearstats}.</p>
 */
public class CommandLinearStats extends Command {

    public CommandLinearStats(@NotNull String name) {
        super(name);
        this.setDescription("Shows Linear region timing stats (per-folder + totals)");
        this.setUsage("/linearstats");
        this.setPermission("linear.command.linearstats");
        this.setPermissionMessage("You do not have permission to use linearstats.");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        if (!this.testPermission(sender)) {
            return false;
        }
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps = LinearFlushCoordinator.snapshots();
        Optional<String> filter = args.length > 0 && args[0] != null && !args[0].isEmpty()
            ? Optional.of(args[0]) : Optional.empty();
        List<LinearStatsCompact.CompactRow> rows = LinearStatsCompact.renderCompact(
            snaps, filter, CommandLinearStats::resolveCompressionLevel);
        for (LinearStatsCompact.CompactRow row : rows) {
            sender.sendMessage(render(row));
        }
        return true;
    }

    static Component render(LinearStatsCompact.CompactRow row) {
        switch (row.kind()) {
            case EMPTY:
                return Component.text(row.text(), NamedTextColor.GRAY);
            case HEADER:
                return Component.text(row.text(), NamedTextColor.GOLD, TextDecoration.BOLD);
            case TOTALS:
                return totalsRow(row);
            default:
                return folderRow(row);
        }
    }

    private static Component folderRow(LinearStatsCompact.CompactRow row) {
        Map<String, String> p = row.parts();
        Map<String, LinearStatsHealth> h = row.health();
        return Component.text(p.getOrDefault("world", ""), NamedTextColor.BLUE)
            .append(Component.text(" L" + p.getOrDefault("level", "?"), NamedTextColor.BLUE))
            .append(Component.text(" " + p.getOrDefault("type", ""), NamedTextColor.GRAY))
            .append(Component.text(" " + p.getOrDefault("dirty", ""), healthColor(h.get("dirty"))))
            .append(Component.text(" " + p.getOrDefault("fail", ""), healthColor(h.get("fail"))))
            .append(Component.text(" " + p.getOrDefault("p99", ""), healthColor(h.get("p99"))))
            .append(Component.text(" " + p.getOrDefault("saved", "na"),
                h.containsKey("saved") ? healthColor(h.get("saved")) : NamedTextColor.GRAY))
            .append(Component.text(" " + p.getOrDefault("age", ""), NamedTextColor.BLUE))
            .append(Component.text(" " + p.getOrDefault("wage", ""), NamedTextColor.BLUE));
    }

    private static Component totalsRow(LinearStatsCompact.CompactRow row) {
        Map<String, String> p = row.parts();
        Map<String, LinearStatsHealth> h = row.health();
        String saved = p.getOrDefault("saved", "na");
        String tail = row.text();
        int at = tail.indexOf(saved);
        String rest = at < 0 ? "" : tail.substring(at + saved.length());
        return Component.text("TOTALS ", NamedTextColor.GOLD)
            .append(Component.text(p.getOrDefault("files", ""), NamedTextColor.BLUE))
            .append(Component.text(" " + p.getOrDefault("fail", ""), healthColor(h.get("fail"))))
            .append(Component.text(" " + saved,
                h.containsKey("saved") ? healthColor(h.get("saved")) : NamedTextColor.GRAY))
            .append(Component.text(rest, NamedTextColor.BLUE));
    }

    static NamedTextColor healthColor(LinearStatsHealth health) {
        if (health == null) {
            return NamedTextColor.GRAY;
        }
        switch (health) {
            case CRIT:
                return NamedTextColor.RED;
            case WARN:
                return NamedTextColor.YELLOW;
            default:
                return NamedTextColor.GREEN;
        }
    }

    /**
     * Per-world compression level without assuming the folder parent equals
     * the Bukkit world name. Folia stores dimensions at
     * {@code .../dimensions/minecraft/<id>/region}, so the compact view's
     * world tag yields the dimension id ({@code overworld},
     * {@code the_nether}, {@code the_end}) while {@code Bukkit.getWorld}
     * wants the level name. Try the direct name first, then match
     * {@code ServerLevel.dimension().identifier().getPath()} across loaded
     * worlds. Returns -1 (panel {@code L?}) when unresolvable; non-fatal.
     */
    private static int resolveCompressionLevel(String worldName) {
        try {
            org.bukkit.World direct = Bukkit.getWorld(worldName);
            if (direct instanceof org.bukkit.craftbukkit.CraftWorld cw) {
                return cw.getHandle().canvasConfig().regionFormat.compressionLevel;
            }
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                if (w instanceof org.bukkit.craftbukkit.CraftWorld cw) {
                    final String dimPath;
                    try {
                        dimPath = cw.getHandle().dimension().identifier().getPath();
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (worldName.equals(dimPath)) {
                        return cw.getHandle().canvasConfig().regionFormat.compressionLevel;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (args.length > 1) {
            return new ArrayList<>();
        }
        String prefix = args.length == 0 || args[0] == null ? "" : args[0];
        List<String> out = new ArrayList<>();
        try {
            for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
                String name = w.getName();
                if (name == null) {
                    continue;
                }
                if (prefix.isEmpty() || (prefix.length() <= name.length()
                    && name.regionMatches(true, 0, prefix, 0, prefix.length()))) {
                    out.add(name);
                }
            }
        } catch (RuntimeException empty) {
            return new ArrayList<>();
        }
        java.util.Collections.sort(out);
        return out;
    }
}
