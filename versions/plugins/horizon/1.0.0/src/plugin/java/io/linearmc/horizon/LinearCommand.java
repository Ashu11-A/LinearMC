package io.linearmc.horizon;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionConverter;
import net.linear.RegionFileFormat;
import net.linear.command.CommandSender;
import net.linear.command.ConvertDirection;
import net.linear.command.ConvertJob;
import net.linear.command.parse.InvalidUsageException;
import net.linear.command.queue.FolderClaims;
import net.linear.command.queue.JobQueue;
import net.linear.command.queue.JobRunner;
import net.linear.command.queue.JobStatusView;
import net.linear.command.LinearAction;
import net.linear.command.LinearCommandExecutor;
import net.linear.command.parse.LinearCommandParser;
import net.linear.command.parse.LinearPermissions;
import net.linear.command.stats.LinearStatsCompact;
import net.linear.command.stats.health.LinearStatsHealth;
import net.linear.command.parse.LinearTabCompleter;
import net.linear.config.LinearFileConfig;
import net.linear.config.LinearFormatOverride;
import net.linear.config.LinearPolicy;
import net.linear.probe.LinearFolderNames;

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
        if (!LinearPermissions.allows(sender::hasPermission, LinearPermissions.Action.LINEAR_STATS)) {
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
            World direct = null;
            try {
                direct = org.bukkit.Bukkit.getWorld(worldName);
            } catch (RuntimeException ignored) {
                direct = null;
            }
            if (direct != null) {
                return LinearFileConfig.resolveLevel();
            }
            for (World w : org.bukkit.Bukkit.getWorlds()) {
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
        if (!LinearPermissions.allows(core::hasPermission, LinearPermissions.Action.CONVERT)) {
            core.sendMessage(LinearPermissions.denied("linear convert"));
            return;
        }
        // Dry-run submit wording is core-owned, so dry-runs dispatch
        // straight through instead of duplicating the submit here.
        if (action.dryRun()) {
            executor.execute(core, action);
            return;
        }
        final int level = effectiveConvertLevel(core, action.level());
        startConvertJob(core, action.world(), level, action.threads(),
            action.direction().targetFormat() == RegionFileFormat.ANVIL,
            folders -> QUEUE.submit(new JobQueue.Spec(
                action.direction(), action.world(), level,
                action.threads(), false,
                folders.stream().mapToInt(
                    folder -> countConvertFiles(targetExtension(action.direction().targetFormat()), folder)).sum())));
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
        if (!LinearPermissions.allows(core::hasPermission, LinearPermissions.Action.CONVERT)) {
            core.sendMessage(LinearPermissions.denied("linear convert"));
            return;
        }
        // Dry-run submit wording is core-owned, so dry-runs dispatch
        // straight through instead of duplicating the submit here.
        if (action.dryRun()) {
            executor.execute(core, action);
            return;
        }
        final int level = effectiveConvertLevel(core, action.level());
        startConvertJob(core, action.world(), level, action.threads(),
            action.to() == LinearRegionConverter.ConvertFormat.MCA,
            folders -> {
                final ConvertJob job = QUEUE.submit(new JobQueue.Spec(
                    placeholderForExplicit(action.to()), action.world(), level,
                    action.threads(), false,
                    folders.stream().mapToInt(folder -> countExplicitFiles(action.from(), action.to(), folder)).sum()));
                job.setExplicit(action.from(), action.to(), action.grid());
                return job;
            });
    }

    /**
     * Convert level, fork parity ({@code CommandLinear}): the
     * {@code -Dlinearmc.compression-level} sysprop wins over the parsed
     * {@code --level}/default, and the override is named to the sender.
     */
    private static int effectiveConvertLevel(CommandSender core, int parsedLevel) {
        final String sysLevel = System.getProperty(LinearPolicy.SYSPROP_LEVEL);
        final int effective = LinearPolicy.resolveLevel(
            sysLevel, parsedLevel, RegionFileFormat.DEFAULT_COMPRESSION_LEVEL);
        if (sysLevel != null && effective != parsedLevel) {
            core.sendMessage(LinearPolicy.overrideLine(LinearPolicy.SYSPROP_LEVEL, sysLevel,
                String.valueOf(parsedLevel), String.valueOf(effective), "global"));
        }
        return effective;
    }

    /**
     * Shared non-dry-run convert start (legacy and explicit): resolve every
     * storage folder of the world (region, entities, poi), claim all of them
     * or none, submit the job, then run the quiesce gate and the conversion
     * on a plain daemon worker — never on the calling (tick) thread: a
     * whole-region zstd flush can exceed the watchdog limit, and Folia/Canvas
     * region threading rejects Bukkit-scheduler async tasks from tick context.
     */
    private void startConvertJob(CommandSender core, String world, int level, int threads,
        boolean reverse, Function<List<Path>, ConvertJob> submit) {
        final List<Path> storageFolders = resolveStorageFolders(world);
        if (storageFolders == null) {
            core.sendMessage("Unknown world '" + world + "': no loaded world matches.");
            return;
        }
        if (storageFolders.isEmpty()) {
            plugin.getLogger().info("No region storage folder for world '" + world + "'.");
            core.sendMessage("No region folder for world '" + world + "'.");
            return;
        }
        final Path busy = FolderClaims.claimAll(storageFolders);
        if (busy != null) {
            core.sendMessage(FolderClaims.busyMessage(busy));
            return;
        }
        final ConvertJob job;
        try {
            job = submit.apply(storageFolders);
        } catch (RuntimeException submitFailed) {
            FolderClaims.releaseAll(storageFolders);
            throw submitFailed;
        }
        core.sendMessage(JobStatusView.queueListLine(job, System.currentTimeMillis()));
        final String senderName = core.name();
        final Thread worker = new Thread(
            () -> {
                try {
                    runConvertWorker(core, job, storageFolders, level, threads, world, reverse, senderName);
                } finally {
                    FolderClaims.releaseAll(storageFolders);
                }
            },
            "linear-convert-" + job.shortId());
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException startFailed) {
            FolderClaims.releaseAll(storageFolders);
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

    private void runConvertWorker(CommandSender core, ConvertJob job, List<Path> storageFolders,
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
        runConversion(job, storageFolders, level, threads, world, reverse, senderName);
    }

    private void runConversion(ConvertJob job, List<Path> storageFolders,
        int level, int threads, String world, boolean reverse, String senderName) {
        // Raw folder paths stay in the log; chat keeps view-template lines only.
        plugin.getLogger().info("Convert " + job.id() + " running on " + storageFolders + " ...");
        tell(senderName, JobStatusView.queueListLine(job, System.currentTimeMillis()));
        // Reverse writes must land in .mca while unconverted linear-family
        // files keep serving reads through triple-read: flip this world's
        // effective format for the duration of the job. The mixin
        // selectors scope on the storage-folder path (no world handle
        // there), so set that scope too — the same plain toString() the
        // selectors consult. Cleared below in the finally, even when the
        // run fails or is cancelled.
        if (reverse) {
            LinearFormatOverride.set(world, "ANVIL");
            for (Path folder : storageFolders) {
                LinearFormatOverride.set(folder.toString(), "ANVIL");
            }
        }
        try {
            JobRunner.run(job, storageFolders, level, threads);
        } finally {
            if (reverse) {
                LinearFormatOverride.clear(world);
                for (Path folder : storageFolders) {
                    LinearFormatOverride.clear(folder.toString());
                }
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
        final java.util.Map<String, List<String>> sourcesByCoords = new java.util.HashMap<>();
        final Set<String> targetCoords = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder)) {
            for (final Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final String name = file.getFileName().toString();
                if (!net.linear.probe.LinearStorageProbe.REGION_NAME.matcher(name).matches()) {
                    continue;
                }
                final java.util.regex.Matcher m = net.linear.probe.LinearStorageProbe.REGION_NAME.matcher(name);
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
        for (java.util.Map.Entry<String, List<String>> e : sourcesByCoords.entrySet()) {
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
        final java.util.Map<String, List<Path>> byCoords = new java.util.HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder)) {
            for (final Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final String name = file.getFileName().toString();
                if (!net.linear.probe.LinearStorageProbe.REGION_NAME.matcher(name).matches()) {
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
                final java.util.regex.Matcher m = net.linear.probe.LinearStorageProbe.REGION_NAME.matcher(name);
                m.matches();
                final String coords = m.group(1) + "," + m.group(2);
                byCoords.computeIfAbsent(coords, k -> new ArrayList<>()).add(file);
            }
        } catch (final Exception ignored) {
            return 0;
        }
        int total = 0;
        for (java.util.Map.Entry<String, List<Path>> e : byCoords.entrySet()) {
            if (e.getValue().size() > 1) {
                continue;
            }
            total++;
        }
        for (java.util.Map.Entry<String, List<Path>> e : byCoords.entrySet()) {
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

    /**
     * World name to its existing region-storage folders
     * ({@link LinearFolderNames#STORAGE_FOLDERS}: region, entities, poi), the
     * same scope the forks convert. {@code null} when no loaded world
     * matches (exact, then case-insensitive); empty when none exists yet.
     */
    private List<Path> resolveStorageFolders(String worldName) {
        final Path worldFolder = resolveWorldFolder(worldName);
        if (worldFolder == null) {
            return null;
        }
        final List<Path> out = new ArrayList<>(LinearFolderNames.STORAGE_FOLDERS.size());
        for (String name : LinearFolderNames.STORAGE_FOLDERS) {
            final Path folder = worldFolder.resolve(name);
            if (Files.isDirectory(folder)) {
                out.add(folder);
            }
        }
        return out;
    }

    /** World name to its world folder (exact match, then case-insensitive). */
    private Path resolveWorldFolder(String worldName) {
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
        return match.getWorldFolder().toPath();
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

    /**
     * No command-level node: a holder of only {@code linear.command.convert}
     * or {@code linear.command.queue} must reach their subcommand (core rule
     * {@link LinearPermissions#allows}, {@code Action.ROOT}); each subcommand
     * checks its own action.
     */
    @Override
    public String permission() {
        return null;
    }

    @Override
    public boolean canUse(org.bukkit.command.CommandSender sender) {
        return LinearPermissions.allows(sender::hasPermission, LinearPermissions.Action.ROOT);
    }
}
