package io.linearmc.horizon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionTimings;
import net.linear.command.LinearPermissions;
import net.linear.command.LinearStatsCompact;

import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * Standalone {@code /linearstats} command (Horizon parity with Folia/Canvas).
 *
 * <p>Thin Bukkit adapter over {@link LinearStatsCompact}: the panel text,
 * health thresholds, empty-state line and permission node are core-owned.
 * This class only checks {@code linear.command.linearstats}, pulls
 * {@link LinearFlushCoordinator#snapshots()}, and colours rows via
 * {@link LinearCommand#render}. Extra arguments beyond the optional filter
 * are ignored, matching {@code CommandLinearStats} on the server legs.
 */
public class LinearStatsCommand implements TabExecutor, BasicCommand {

    private final JavaPlugin plugin;

    public LinearStatsCommand(JavaPlugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
        org.bukkit.command.CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(LinearPermissions.STATS)) {
            sender.sendMessage(LinearPermissions.denied("linearstats"));
            return true;
        }
        Optional<String> filter = args.length > 0 && args[0] != null && !args[0].isEmpty()
            ? Optional.of(args[0])
            : Optional.empty();
        Map<String, LinearRegionTimings.LinearFolderSnapshot> snaps =
            LinearFlushCoordinator.snapshots();
        for (LinearStatsCompact.CompactRow row : LinearStatsCompact.renderCompact(
            snaps, filter, LinearCommand::resolveCompressionLevel)) {
            sender.sendMessage(LinearCommand.render(row));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, Command command,
        String alias, String[] args) {
        if (args.length > 1) {
            return new ArrayList<>();
        }
        String prefix = args.length == 0 || args[0] == null ? "" : args[0];
        List<String> out = new ArrayList<>();
        try {
            for (org.bukkit.World w : plugin.getServer().getWorlds()) {
                if (w == null || w.getName() == null) {
                    continue;
                }
                String name = w.getName();
                if (prefix.isEmpty()
                    || (prefix.length() <= name.length()
                        && name.regionMatches(true, 0, prefix, 0, prefix.length()))) {
                    out.add(name);
                }
            }
        } catch (RuntimeException empty) {
            return new ArrayList<>();
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        onCommand(stack.getSender(), null, "linearstats", args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        return onTabComplete(stack.getSender(), null, "linearstats", args);
    }

    @Override
    public String permission() {
        return LinearPermissions.STATS;
    }
}
