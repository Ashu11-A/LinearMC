package io.linearmc.horizon;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionConverter;
import net.linear.RegionFileFormat;
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

    // Per-folder single-flight: in-flight region-folder keys (absolute normalized paths).
    // A second --execute on an in-flight folder is rejected with
    // "conversion already running for <folder>" (no queue, no block); entries are
    // removed in finally after the worker finishes (including failure paths).
    // Covers legacy AND explicit paths (both spawn threads).
    private static final java.util.Set<String> IN_FLIGHT_FOLDERS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Pumpkin rule, surfaced on {@code /linear help} beside the core help
     * text (core-owned wording stays frozen): Pumpkin migrates explicit-only
     * and startup never rewrites Pumpkin files.
     */
    static final String PUMPKIN_RULE_LINE =
        "Pumpkin (.linear wire-2) migrates explicit-only:"
        + " /linear convert <world> --from pumpkin --to <target> --execute;"
        + " startup never rewrites Pumpkin files.";

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
        } else if (action instanceof LinearAction.ExplicitConvertAction explicit) {
            runConvertExplicit(sender, core, explicit);
        } else {
            // Queue ops + help stay core-owned (permission checks included).
            executor.execute(core, action);
            if (action instanceof LinearAction.HelpAction) {
                core.sendMessage(PUMPKIN_RULE_LINE);
            }
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
        if (!tryClaimFolder(regionFolder)) {
            core.sendMessage("conversion already running for " + regionFolder);
            return;
        }
        ConvertJob job = QUEUE.submit(new JobQueue.Spec(
            action.direction(), action.world(), action.level(),
            action.threads(), false,
            countConvertFiles(targetExtension(action.direction().targetFormat()), regionFolder)));
        // The quiesce gate (flushAllDirty + dirty check) and the conversion
        // itself run inside the worker below — never on the calling (tick)
        // thread. A whole-region zstd flush can exceed the watchdog limit,
        // and Folia/Canvas region threading rejects Bukkit-scheduler async
        // tasks from tick context, so the worker is a plain daemon thread.
        core.sendMessage(JobStatusView.queueListLine(job, System.currentTimeMillis()));
        final String senderName = core.name();
        final Path claimed = regionFolder;
        final Thread worker = new Thread(
            () -> {
                try {
                    runConvertWorker(core, job, regionFolder, action.level(), action.threads(),
                        action.world(), action.direction().targetFormat() == RegionFileFormat.ANVIL,
                        senderName);
                } finally {
                    releaseFolder(claimed);
                }
            },
            "linear-convert-" + job.shortId());
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException startFailed) {
            releaseFolder(claimed);
            throw startFailed;
        }
    }

    /**
     * Explicit any-to-any convert ({@code --from/--to/--grid}): the same
     * quiesce-gated worker as legacy convert (no new guard semantics). The
     * job carries a legacy placeholder direction for display plus explicit
     * {@code from/to/grid} routing (mirroring the core executor's mapping);
     * {@link JobRunner#run} routes explicit jobs via {@code from/to}, so the
     * core folder-dirty per-file skip applies exactly as for legacy jobs.
     */
    private void runConvertExplicit(org.bukkit.command.CommandSender sender, CommandSender core,
        LinearAction.ExplicitConvertAction action) {
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
        if (!tryClaimFolder(regionFolder)) {
            core.sendMessage("conversion already running for " + regionFolder);
            return;
        }
        ConvertJob job = QUEUE.submit(new JobQueue.Spec(
            placeholderForExplicit(action.to()), action.world(), action.level(),
            action.threads(), false,
            countExplicitFiles(action.from(), action.to(), regionFolder)));
        job.setExplicit(action.from(), action.to(), action.grid());
        // Same quiesce-gated worker as legacy (flushAllDirty + dirty check
        // inside, never on the calling tick thread).
        core.sendMessage(JobStatusView.queueListLine(job, System.currentTimeMillis()));
        final String senderName = core.name();
        final Path claimed = regionFolder;
        final Thread worker = new Thread(
            () -> {
                try {
                    runConvertWorker(core, job, regionFolder, action.level(), action.threads(),
                        action.world(), action.to() == LinearRegionConverter.ConvertFormat.MCA,
                        senderName);
                } finally {
                    releaseFolder(claimed);
                }
            },
            "linear-convert-" + job.shortId());
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException startFailed) {
            releaseFolder(claimed);
            throw startFailed;
        }
    }

    /**
     * Legacy placeholder direction for an explicit target (display only; the
     * job's {@code from/to} owns routing). Mirrors the core executor mapping:
     * toward MCA reads as reverse, toward buffered as buffered-forward, every
     * other layout as forward.
     */
    private static ConvertDirection placeholderForExplicit(
        LinearRegionConverter.ConvertFormat to) {
        if (to == LinearRegionConverter.ConvertFormat.MCA) {
            return ConvertDirection.LINEAR_TO_MCA;
        }
        if (to == LinearRegionConverter.ConvertFormat.B_LINEAR) {
            return ConvertDirection.MCA_TO_BLINEAR;
        }
        return ConvertDirection.MCA_TO_LINEAR;
    }

    /** On-disk extension converted toward ({@code .mca}, {@code .linear} or {@code .b_linear}). */
    private static String targetExtension(final RegionFileFormat target) {
        if (target == RegionFileFormat.ANVIL) {
            return RegionFileFormat.ANVIL_EXTENSION;
        }
        if (target == RegionFileFormat.B_LINEAR) {
            return RegionFileFormat.B_LINEAR_EXTENSION;
        }
        return RegionFileFormat.LINEAR_EXTENSION;
    }

    private static String folderKey(Path folder) {
        try {
            return folder.toAbsolutePath().normalize().toString();
        } catch (RuntimeException fallback) {
            return folder.toString();
        }
    }

    private static boolean tryClaimFolder(Path folder) {
        return IN_FLIGHT_FOLDERS.add(folderKey(folder));
    }

    private static void releaseFolder(Path folder) {
        try {
            IN_FLIGHT_FOLDERS.remove(folderKey(folder));
        } catch (RuntimeException ignored) {
        }
    }

    private void runConvertWorker(CommandSender core, ConvertJob job, Path regionFolder,
        int level, int threads, String world, boolean reverse, String senderName) {
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
        runConversion(job, regionFolder, level, threads, world, reverse, senderName);
    }

    private void runConversion(ConvertJob job, Path regionFolder,
        int level, int threads, String world, boolean reverse, String senderName) {
        // Raw folder paths stay in the log; chat keeps view-template lines only.
        plugin.getLogger().info("Convert " + job.id() + " running on " + regionFolder + " ...");
        tell(senderName, JobStatusView.queueListLine(job, System.currentTimeMillis()));
        // Reverse writes must land in .mca while unconverted linear-family
        // files keep serving reads through triple-read: flip this world's
        // effective format for the duration of the job. The mixin
        // selectors scope on the storage-folder path (no world handle
        // there), so set that scope too — the same plain toString() the
        // selectors consult. Cleared below in the finally, even when the
        // run fails or is cancelled.
        final String folderScope = regionFolder.toString();
        if (reverse) {
            LinearFormatOverride.set(world, "ANVIL");
            LinearFormatOverride.set(folderScope, "ANVIL");
        }
        try {
            JobRunner.run(job, List.of(regionFolder), level, threads);
        } finally {
            if (reverse) {
                LinearFormatOverride.clear(world);
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

    /**
     * Source-file count for the convert target (union-per-target: every
     * convertible non-target file — toward {@code .mca} that is the linear
     * family, otherwise every convertible file with a different extension).
     * Progress denominator only; explicit any-to-any jobs use
     * {@link #countExplicitFiles} (with {@code --from} filtering), legacy jobs
     * map their direction first.
     * Anchored {@code ^(mca|linear|b_linear)$} gate via {@code REGION_NAME}
     * (mirrors {@code collectForwardCandidates/collectReverseCandidates} read-only).
     * Same-coords ambiguous groups and target-occupied coords count 0 for that
     * coords: the real collection escalates valid shadows/ambiguous to FAILED
     * (0 queued) and only requeues invalid-shadow orphans after file-IO validation
     * (not leg-visible); when in doubt UNDERcount. Folder-dirty skips and Pumpkin
     * layout detection (needs detector IO) can only reduce the real queue further.
     */
    private static int countConvertFiles(
        final String targetExtension, final Path regionFolder) {
        final java.util.Map<String, java.util.List<String>> sourcesByCoords = new java.util.HashMap<>();
        final java.util.Set<String> targetCoords = new java.util.HashSet<>();
        final java.util.regex.Pattern regionName =
            java.util.regex.Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear|b_linear)$");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder)) {
            for (final Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final String name = file.getFileName().toString();
                if (!regionName.matcher(name).matches()) {
                    continue;
                }
                final java.util.regex.Matcher m = regionName.matcher(name);
                // Re-match for coords (matcher above already validated).
                m.matches();
                final String coords = m.group(1) + "," + m.group(2);
                final boolean convertible = name.endsWith(RegionFileFormat.ANVIL_EXTENSION)
                    || name.endsWith(RegionFileFormat.LINEAR_EXTENSION)
                    || name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION);
                if (!convertible) {
                    continue;
                }
                if (name.endsWith(targetExtension)) {
                    targetCoords.add(coords);
                } else {
                    sourcesByCoords.computeIfAbsent(coords, k -> new ArrayList<>()).add(name);
                }
            }
        } catch (final Exception ignored) {
            // Unreadable folder: job proceeds with unknown total (percent shows calculating).
            return 0;
        }
        int total = 0;
        for (java.util.Map.Entry<String, java.util.List<String>> e : sourcesByCoords.entrySet()) {
            if (e.getValue().size() > 1) {
                continue;
            }
            if (targetCoords.contains(e.getKey())) {
                continue;
            }
            total++;
        }
        return total;
    }

    /**
     * Explicit progress denominator: extension-level {@code --from} filtering plus
     * target exclusion with the anchored gate (mirrors
     * {@code collectExplicitCandidates} read-only; counting stays an estimate).
     * Same-extension {@code .linear} targets (V1/V2/V3/PUMPKIN share {@code .linear}
     * per FR7) need detector IO to separate already-target layouts from same-path
     * swaps, so same-extension {@code .linear} files are excluded (UNDERcount).
     * Same-coords ambiguous and target-path-occupied groups count 0 (real escalates
     * to FAILED, 0 queued). Pumpkin AUTO skip and folder-dirty skips need file/detector
     * IO and can only reduce the real queue further.
     */
    private static int countExplicitFiles(
        final LinearRegionConverter.ConvertFormat from,
        final LinearRegionConverter.ConvertFormat to,
        final Path regionFolder) {
        if (to == null) {
            return 0;
        }
        final String targetExt = to.extension();
        final java.util.regex.Pattern regionName =
            java.util.regex.Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear|b_linear)$");
        final java.util.Map<String, java.util.List<Path>> byCoords = new java.util.HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder)) {
            for (final Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final String name = file.getFileName().toString();
                if (!regionName.matcher(name).matches()) {
                    continue;
                }
                if (!name.endsWith(RegionFileFormat.ANVIL_EXTENSION)
                    && !name.endsWith(RegionFileFormat.LINEAR_EXTENSION)
                    && !name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
                    continue;
                }
                if (!fromMatchesExtension(name, from)) {
                    continue;
                }
                if (targetExt.equals(RegionFileFormat.LINEAR_EXTENSION)
                    && name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
                    continue;
                }
                if (name.endsWith(targetExt)) {
                    continue;
                }
                final java.util.regex.Matcher m = regionName.matcher(name);
                m.matches();
                final String coords = m.group(1) + "," + m.group(2);
                byCoords.computeIfAbsent(coords, k -> new ArrayList<>()).add(file);
            }
        } catch (final Exception ignored) {
            return 0;
        }
        int total = 0;
        for (java.util.Map.Entry<String, java.util.List<Path>> e : byCoords.entrySet()) {
            if (e.getValue().size() > 1) {
                continue;
            }
            total++;
        }
        for (java.util.Map.Entry<String, java.util.List<Path>> e : byCoords.entrySet()) {
            if (e.getValue().size() != 1) {
                continue;
            }
            final Path source = e.getValue().get(0);
            final String sourceName = source.getFileName().toString();
            String base = sourceName;
            for (String known : new String[] {RegionFileFormat.ANVIL_EXTENSION,
                RegionFileFormat.LINEAR_EXTENSION, RegionFileFormat.B_LINEAR_EXTENSION}) {
                if (base.endsWith(known)) {
                    base = base.substring(0, base.length() - known.length()) + targetExt;
                    break;
                }
            }
            try {
                if (Files.isRegularFile(source.resolveSibling(base))) {
                    total--;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return Math.max(0, total);
    }

    private static boolean fromMatchesExtension(String fileName,
        LinearRegionConverter.ConvertFormat from) {
        if (from == null || from == LinearRegionConverter.ConvertFormat.AUTO) {
            return true;
        }
        if (from == LinearRegionConverter.ConvertFormat.MCA) {
            return fileName.endsWith(RegionFileFormat.ANVIL_EXTENSION);
        }
        if (from == LinearRegionConverter.ConvertFormat.B_LINEAR) {
            return fileName.endsWith(RegionFileFormat.B_LINEAR_EXTENSION);
        }
        // V1/V2/V3/PUMPKIN/LINEAR share .linear: extension-level match only,
        // detector narrows in the real collection. A layout-specific --from
        // may OVERcount here when other layouts share the folder;
        // progress-only either way.
        return fileName.endsWith(RegionFileFormat.LINEAR_EXTENSION);
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
        List<String> base = LinearTabCompleter.suggest(args, QUEUE, () -> {
            final Set<String> names = new HashSet<>();
            try {
                for (World w : plugin.getServer().getWorlds()) {
                    names.add(w.getName());
                }
            } catch (RuntimeException offline) {
            }
            return names;
        });
        return withExplicitConvertFlags(args, base);
    }

    /**
     * Suggests the explicit any-to-any flags ({@code --from/--to/--grid}) in
     * the convert flag slot. Core completion owns the values after each flag
     * and the legacy flag list (frozen); this leg only adds the three flag
     * names where a flag is being typed, never in a value or world slot.
     */
    static List<String> withExplicitConvertFlags(String[] args, List<String> base) {
        if (args == null || args.length < 3 || args[0] == null
            || !"convert".equalsIgnoreCase(args[0])) {
            return base;
        }
        String prev = args[args.length - 2];
        if (prev != null && (prev.equalsIgnoreCase("--level") || prev.equalsIgnoreCase("--threads")
            || prev.equalsIgnoreCase("--from") || prev.equalsIgnoreCase("--to")
            || prev.equalsIgnoreCase("--grid"))) {
            return base;
        }
        String cur = args[args.length - 1];
        if (cur == null) {
            cur = "";
        }
        if (!cur.isEmpty() && !cur.startsWith("-")) {
            return base;
        }
        List<String> out = new ArrayList<>(base);
        for (String flag : List.of("--from", "--to", "--grid")) {
            if (flag.startsWith(cur) && out.stream().noneMatch(flag::equalsIgnoreCase)) {
                out.add(flag);
            }
        }
        return out;
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
