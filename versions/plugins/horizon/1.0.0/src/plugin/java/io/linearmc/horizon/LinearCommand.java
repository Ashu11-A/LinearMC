package io.linearmc.horizon;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.linear.LinearFlushCoordinator;
import net.linear.command.CommandSender;
import net.linear.command.ConvertDirection;
import net.linear.command.ConvertJob;
import net.linear.command.InvalidUsageException;
import net.linear.command.JobQueue;
import net.linear.command.JobRunner;
import net.linear.command.JobStatusView;
import net.linear.command.LinearAction;
import net.linear.command.LinearCommandExecutor;
import net.linear.command.LinearCommandParser;
import net.linear.command.LinearPermissions;
import net.linear.command.LinearStatsCompact;
import net.linear.command.LinearStatsHealth;
import net.linear.command.LinearTabCompleter;
import net.linear.config.LinearFileConfig;
import net.linear.config.LinearFormatOverride;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * Paper-half {@code /linear} command tree.
 *
 * <p>Thin Bukkit adapter over command: the grammar
 * ({@link LinearCommandParser}), the queue/help/dry-run dispatch
 * ({@link LinearCommandExecutor}), the stats panel
 * ({@link LinearStatsCompact}), conversion execution (both directions) and
 * the quiesce check ({@link JobRunner}), and the permission nodes
 * ({@link LinearPermissions}) are core-owned. This class only adapts the
 * Bukkit sender, resolves world folders, hops heavy work off the main
 * thread, and relays progress lines. Stats rows are compact proposal-A
 * lines colour-coded with Bukkit {@link ChatColor}:
 * health OK/WARN/CRIT maps to GREEN/YELLOW/RED, worlds/levels to BLUE,
 * unknown na and ages to GRAY, header/totals labels to GOLD.
 */
public class LinearCommand implements TabExecutor, BasicCommand {

    /** Shared in-memory queue (never persisted; restarts drop it). */
    private static final JobQueue QUEUE = new JobQueue();

    private final JavaPlugin plugin;
    private final LinearCommandParser parser = new LinearCommandParser();
    private final LinearCommandExecutor executor =
        new LinearCommandExecutor(QUEUE, LinearFlushCoordinator::snapshots);

    public LinearCommand(JavaPlugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        this.plugin = plugin;
    }

    /** Shared queue (also the {@code /linear queue} view). */
    static JobQueue queue() {
        return QUEUE;
    }

    @Override
    public boolean onCommand(
        org.bukkit.command.CommandSender sender, Command command, String label, String[] args) {
        final CommandSender core = adapt(sender);
        final LinearAction action;
        try {
            action = parser.parse(args);
        } catch (InvalidUsageException bad) {
            core.sendMessage(bad.getMessage());
            return true;
        }
        if (action instanceof LinearAction.StatsAction stats) {
            runStats(core, stats);
        } else if (action instanceof LinearAction.ConvertAction convert) {
            runConvert(sender, core, convert);
        } else {
            // Queue ops + help stay core-owned (permission checks included).
            executor.execute(core, action);
        }
        return true;
    }

    private void runStats(CommandSender sender, LinearAction.StatsAction action) {
        if (!sender.hasPermission(LinearPermissions.STATS)
            && !sender.hasPermission(LinearPermissions.BASE)) {
            sender.sendMessage(LinearPermissions.denied("linearstats"));
            return;
        }
        for (LinearStatsCompact.CompactRow row : LinearStatsCompact.renderCompact(
            LinearFlushCoordinator.snapshots(), action.worldFilter(), LinearCommand::resolveCompressionLevel)) {
            sender.sendMessage(render(row));
        }
    }

    /**
     * Per-world compression level, mirroring Canvas
     * {@code CommandLinearStats.resolveCompressionLevel}: try the direct
     * Bukkit world name first, then match the dimension id path across
     * loaded worlds. Horizon stores a single global level, so a known world
     * resolves to it; unknown/unresolvable worlds yield -1 (panel
     * {@code L?}). Non-fatal.
     */
    static int resolveCompressionLevel(String worldName) {
        try {
            org.bukkit.World direct = null;
            try {
                direct = org.bukkit.Bukkit.getWorld(worldName);
            } catch (RuntimeException ignored) {
                direct = null;
            }
            if (direct != null) {
                return LinearFileConfig.resolveLevel();
            }
            for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
                if (w == null) {
                    continue;
                }
                if (worldName != null && w.getName().equalsIgnoreCase(worldName)) {
                    return LinearFileConfig.resolveLevel();
                }
                if (w instanceof org.bukkit.craftbukkit.CraftWorld cw) {
                    final String dimPath;
                    try {
                        dimPath = cw.getHandle().dimension().identifier().getPath();
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (worldName != null && worldName.equals(dimPath)) {
                        return LinearFileConfig.resolveLevel();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    static String render(LinearStatsCompact.CompactRow row) {
        switch (row.kind()) {
            case EMPTY:
                return ChatColor.GRAY + row.text() + ChatColor.RESET;
            case HEADER:
                return ChatColor.GOLD + row.text() + ChatColor.RESET;
            case TOTALS:
                return totalsLine(row);
            default:
                return folderLine(row);
        }
    }

    private static String folderLine(LinearStatsCompact.CompactRow row) {
        java.util.Map<String, String> p = row.parts();
        java.util.Map<String, LinearStatsHealth> h = row.health();
        String saved = p.getOrDefault("saved", "na");
        ChatColor savedColor =
            h.containsKey("saved") ? healthColor(h.get("saved")) : ChatColor.GRAY;
        return ChatColor.BLUE + p.getOrDefault("world", "")
            + " L" + p.getOrDefault("level", "?")
            + ChatColor.GRAY + " " + p.getOrDefault("type", "")
            + healthColor(h.get("dirty")) + " " + p.getOrDefault("dirty", "")
            + healthColor(h.get("fail")) + " " + p.getOrDefault("fail", "")
            + healthColor(h.get("p99")) + " " + p.getOrDefault("p99", "")
            + savedColor + " " + saved
            + ChatColor.BLUE + " " + p.getOrDefault("age", "")
            + ChatColor.BLUE + " " + p.getOrDefault("wage", "")
            + ChatColor.RESET;
    }

    private static String totalsLine(LinearStatsCompact.CompactRow row) {
        java.util.Map<String, String> p = row.parts();
        java.util.Map<String, LinearStatsHealth> h = row.health();
        String saved = p.getOrDefault("saved", "na");
        String tail = row.text();
        int at = tail.indexOf(saved);
        String rest = at < 0 ? "" : tail.substring(at + saved.length());
        ChatColor savedColor =
            h.containsKey("saved") ? healthColor(h.get("saved")) : ChatColor.GRAY;
        return ChatColor.GOLD + "TOTALS "
            + ChatColor.BLUE + p.getOrDefault("files", "")
            + healthColor(h.get("fail")) + " " + p.getOrDefault("fail", "")
            + savedColor + " " + saved
            + ChatColor.BLUE + rest
            + ChatColor.RESET;
    }

    static ChatColor healthColor(LinearStatsHealth health) {
        if (health == null) {
            return ChatColor.GRAY;
        }
        switch (health) {
            case CRIT:
                return ChatColor.RED;
            case WARN:
                return ChatColor.YELLOW;
            default:
                return ChatColor.GREEN;
        }
    }

    private void runConvert(org.bukkit.command.CommandSender sender, CommandSender core,
        LinearAction.ConvertAction action) {
        if (!core.hasPermission(LinearPermissions.CONVERT)
            && !core.hasPermission(LinearPermissions.BASE)) {
            core.sendMessage(LinearPermissions.denied("linear convert"));
            return;
        }
        // Dry-run submit wording is core-owned, so dry-runs dispatch
        // straight through instead of duplicating the submit here.
        if (action.dryRun()) {
            executor.execute(core, action);
            return;
        }
        Path regionFolder = resolveRegionFolder(action.world());
        if (regionFolder == null) {
            core.sendMessage("Unknown world '" + action.world() + "': no loaded world matches.");
            return;
        }
        if (!Files.isDirectory(regionFolder)) {
            plugin.getLogger().info("No region folder for world '" + action.world() + "': " + regionFolder);
            core.sendMessage("No region folder for world '" + action.world() + "'.");
            return;
        }
        ConvertJob job = QUEUE.submit(new JobQueue.Spec(
            action.direction(), action.world(), action.level(),
            action.threads(), false, countConvertFiles(action, regionFolder)));
        // The quiesce gate (flushAllDirty + dirty check) and the conversion
        // itself run inside the worker below — never on the calling (tick)
        // thread. A whole-region zstd flush can exceed the watchdog limit,
        // and Folia/Canvas region threading rejects Bukkit-scheduler async
        // tasks from tick context, so the worker is a plain daemon thread.
        core.sendMessage(JobStatusView.queueListLine(job, System.currentTimeMillis()));
        final String senderName = core.name();
        final Thread worker = new Thread(
            () -> runConvertWorker(core, job, regionFolder, action, senderName),
            "linear-convert-" + job.shortId());
        worker.setDaemon(true);
        worker.start();
    }

    private void runConvertWorker(CommandSender core, ConvertJob job, Path regionFolder,
        LinearAction.ConvertAction action, String senderName) {
        // Quiesce gate: drain deferred writes, then require zero dirty depth.
        LinearFlushCoordinator.flushAllDirty(true);
        List<String> dirty = JobRunner.quiesceCheck();
        if (!dirty.isEmpty()) {
            final String reason = "quiesce gate: " + dirty.size()
                + " folder(s) still dirty after flushAllDirty(true); save-all and retry";
            try {
                job.start();
            } catch (IllegalStateException alreadyTerminal) {
                // Cancelled between submit and start; fall through to fail below.
            }
            try {
                job.fail(reason);
            } catch (IllegalStateException alreadyTerminal) {
                // Already CANCELLED; the FAILED detail would be a lie, keep the state.
            }
            core.sendMessage(JobStatusView.failedAction(reason));
            return;
        }
        runConversion(job, regionFolder, action, senderName);
    }

    private void runConversion(ConvertJob job, Path regionFolder,
        LinearAction.ConvertAction action, String senderName) {
        // Raw folder paths stay in the log; chat keeps view-template lines only.
        plugin.getLogger().info("Convert " + job.id() + " running on " + regionFolder + " ...");
        tell(senderName, JobStatusView.queueListLine(job, System.currentTimeMillis()));
        final boolean reverse = action.direction() == ConvertDirection.LINEAR_TO_MCA;
        // Reverse writes must land in .mca while unconverted .linear
        // files keep serving reads through dual-read: flip this world's
        // effective format for the duration of the job. The mixin
        // selectors scope on the storage-folder path (no world handle
        // there), so set that scope too — the same plain toString() the
        // selectors consult. Cleared below in the finally, even when the
        // run fails or is cancelled.
        final String folderScope = regionFolder.toString();
        if (reverse) {
            LinearFormatOverride.set(action.world(), "ANVIL");
            LinearFormatOverride.set(folderScope, "ANVIL");
        }
        try {
            JobRunner.run(job, List.of(regionFolder), action.level(), action.threads());
        } finally {
            if (reverse) {
                LinearFormatOverride.clear(action.world());
                LinearFormatOverride.clear(folderScope);
            }
        }
        final long nowMs = System.currentTimeMillis();
        switch (job.state()) {
            case DONE -> tell(senderName, JobStatusView.statusLine(job, nowMs));
            case FAILED -> {
                List<String> details = job.details();
                String reason = details.isEmpty() ? "FAILED" : details.get(details.size() - 1);
                tell(senderName, JobStatusView.statusLine(job, nowMs));
                tell(senderName, JobStatusView.failedAction(reason));
            }
            case CANCELLED -> tell(senderName, "Convert " + job.id() + " cancelled.");
            default -> tell(senderName, "Convert " + job.id() + " ended in " + job.state() + ".");
        }
    }

    /** Source-file count for the convert direction (forward counts .mca, reverse counts .linear). */
    private static int countConvertFiles(
        final LinearAction.ConvertAction action, final Path regionFolder) {
        final String glob = action.direction() == ConvertDirection.LINEAR_TO_MCA
            ? "r.*.linear"
            : "r.*.mca";
        int total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder, glob)) {
            for (final Path ignored : stream) {
                total++;
            }
        } catch (final Exception ignored) {
            // Unreadable folder: job proceeds with unknown total (percent shows calculating).
        }
        return total;
    }

    /** World name to its {@code region/} folder (exact match, then case-insensitive). */
    private Path resolveRegionFolder(String worldName) {
        World match = plugin.getServer().getWorld(worldName);
        if (match == null) {
            for (World w : plugin.getServer().getWorlds()) {
                if (w.getName().equalsIgnoreCase(worldName)) {
                    match = w;
                    break;
                }
            }
        }
        if (match == null) {
            return null;
        }
        return match.getWorldFolder().toPath().resolve("region");
    }

    /** Async progress line: always to the log, plus the player when still online. */
    private void tell(String senderName, String line) {
        plugin.getLogger().info(line);
        if (senderName == null || senderName.equals("CONSOLE")) {
            return;
        }
        Player player = plugin.getServer().getPlayerExact(senderName);
        if (player != null) {
            player.sendMessage(line);
        }
    }

    private static CommandSender adapt(org.bukkit.command.CommandSender sender) {
        return new CommandSender() {
            @Override
            public void sendMessage(String message) {
                sender.sendMessage(message);
            }

            @Override
            public boolean hasPermission(String permission) {
                return sender.hasPermission(permission);
            }

            @Override
            public String name() {
                return sender.getName();
            }
        };
    }

    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, Command command,
        String alias, String[] args) {
        return LinearTabCompleter.suggest(args, QUEUE, () -> {
            final Set<String> names = new HashSet<>();
            try {
                for (World w : plugin.getServer().getWorlds()) {
                    names.add(w.getName());
                }
            } catch (RuntimeException offline) {
            }
            return names;
        });
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        onCommand(stack.getSender(), null, "linear", args);
    }

    @Override
    public java.util.Collection<String> suggest(CommandSourceStack stack, String[] args) {
        return onTabComplete(stack.getSender(), null, "linear", args);
    }

    @Override
    public String permission() {
        return "linear.command.linear";
    }
}
