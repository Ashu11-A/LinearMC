package io.papermc.paper.command;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionConverter;
import net.linear.command.ConvertDirection;
import net.linear.command.ConvertJob;
import net.linear.command.parse.InvalidUsageException;
import net.linear.command.queue.FolderClaims;
import net.linear.command.queue.JobQueue;
import net.linear.command.queue.JobStatusView;
import net.linear.command.LinearAction;
import net.linear.command.LinearCommandExecutor;
import net.linear.command.parse.LinearCommandParser;
import net.linear.command.parse.LinearPermissions;
import net.linear.command.parse.LinearTabCompleter;
import net.linear.config.LinearPolicy;
import net.linear.config.LinearFormatOverride;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * Linear {@code /linear} tree: stats / convert / queue / help.
 *
 * <p>Thin Bukkit leg over the fork-owned core command model
 * ({@code net.linear.command}): args parse via {@link LinearCommandParser},
 * convert/queue/help execute via {@link LinearCommandExecutor} against a
 * static in-memory {@link JobQueue} singleton (never persisted: jobs vanish
 * on restart, see {@link JobQueue#droppedByRestartLine}), stats snapshots
 * come from {@link LinearFlushCoordinator#snapshots()}, and permission nodes
 * come from {@link LinearPermissions}. The native Bukkit sender is adapted to
 * the core sender interface (name clash, so the core type is referenced
 * fully-qualified below).</p>
 *
 * <p>Two legs need more than the core executor and are handled here:</p>
 * <ul>
 *   <li>{@code stats} reuses the pinned {@link CommandLinearStats} rendering
 *       by calling it (no fork). The grammar's optional world filter is
 *       accepted but the shared panel renders all tracked folders.</li>
 *   <li>{@code convert --execute} runs the core
 *       {@link net.linear.command.queue.JobRunner} off-tick (daemon worker,
 *       never the tick thread), which routes by direction (towards MCA via
 *       the NMS {@code AnvilChunkSink}) or by the explicit
 *       {@code --from/--to/--grid} (the only way Pumpkin migrates), records
 *       per-file job updates and honours pause; dry runs plan only (core
 *       executor message, no conversion).
 *       Reverse sets a {@code LinearFormatOverride} ANVIL on the world so new
 *       writes land in {@code .mca} while unconverted {@code .linear} files
 *       keep serving reads, cleared when the worker settles. A quiesce gate
 *       ({@code flushAllDirty(true)} then dirty-depth zero) guards every
 *       execute path (legacy and explicit alike). Operator lines route through the core
 *       {@link JobStatusView} templates.</li>
 * </ul>
 */
public class CommandLinear extends Command {

    // In-memory convert-job registry, shared by every /linear invocation.
    static final JobQueue QUEUE = new JobQueue();

    private final LinearCommandExecutor executor =
        new LinearCommandExecutor(QUEUE, LinearFlushCoordinator::snapshots);

    public CommandLinear(String name) {
        super(name);
        this.setDescription("Linear region management (stats/convert/queue/help)");
        this.setUsage(LinearCommandParser.USAGE);
        // No command-level node: a convert-only or queue-only holder must
        // reach their subcommand (core rule LinearPermissions.allows, ROOT);
        // each subcommand checks its own action.
        this.setPermissionMessage(LinearPermissions.denied("linear"));
    }

    @Override
    public boolean testPermissionSilent(CommandSender target) {
        return LinearPermissions.allows(target::hasPermission, LinearPermissions.Action.ROOT);
    }

    /**
     * Pumpkin rule, surfaced on {@code /linear help} beside the core help
     * text: Pumpkin migrates explicit-only and startup never rewrites
     * Pumpkin files.
     */
    private static final String PUMPKIN_RULE_LINE =
        "Pumpkin (.linear wire-2) migrates explicit-only:"
        + " /linear convert <world> --from pumpkin --to <target> --execute;"
        + " startup never rewrites Pumpkin files.";

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        final LinearAction action;
        try {
            action = new LinearCommandParser().parse(args);
        } catch (InvalidUsageException bad) {
            sender.sendMessage(bad.getMessage());
            return true;
        }
        if (action instanceof LinearAction.StatsAction stats) {
            // Single rendering path: the pinned panel, gated by the core rule
            // for /linear stats (linearstats OR linear node).
            if (!LinearPermissions.allows(sender::hasPermission, LinearPermissions.Action.LINEAR_STATS)) {
                sender.sendMessage(LinearPermissions.denied("linearstats"));
                return true;
            }
            final String[] statsArgs = stats.worldFilter()
                .map(world -> new String[]{world})
                .orElseGet(() -> new String[0]);
            CommandLinearStats.renderPanel(sender, statsArgs);
            return true;
        }
        if (action instanceof LinearAction.ConvertAction c && !c.dryRun()) {
            return executeConvert(sender, c.world(), c.level(), c.threads(), c.direction(),
                c.direction().plainDirection(),
                folder -> countLegacyCandidates(folder, c.direction().targetFormat()), null);
        }
        if (action instanceof LinearAction.ExplicitConvertAction e && !e.dryRun()) {
            return executeConvert(sender, e.world(), e.level(), e.threads(), placeholderForExplicit(e.to()),
                "explicit " + e.from().name().toLowerCase(java.util.Locale.ROOT)
                    + " to " + e.to().name().toLowerCase(java.util.Locale.ROOT),
                folder -> countExplicitCandidates(folder, e.from(), e.to()), e);
        }
        // Help, queue ops, and dry-run convert plans (no conversion runs).
        this.executor.execute(new BukkitSender(sender), action);
        if (action instanceof LinearAction.HelpAction) {
            sender.sendMessage(PUMPKIN_RULE_LINE);
        }
        return true;
    }

    /**
     * Real conversion execution for legacy and explicit jobs (dry runs never
     * reach here). Claims every storage folder of the world first (core
     * {@link FolderClaims}); the quiesce-gated worker then routes by the job's
     * direction, or by its explicit {@code from/to/grid} when {@code explicit}
     * is set (core {@code JobRunner} switches per folder). Reverse holds a
     * {@code LinearFormatOverride} ANVIL for the run, cleared on settle.
     */
    private static boolean executeConvert(CommandSender sender, String worldName, int parsedLevel, int threads,
                                          ConvertDirection direction, String label,
                                          java.util.function.ToIntFunction<Path> countCandidates,
                                          LinearAction.ExplicitConvertAction explicit) {
        if (!LinearPermissions.allows(sender::hasPermission, LinearPermissions.Action.CONVERT)) {
            sender.sendMessage(LinearPermissions.denied("linear convert"));
            return true;
        }
        final org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("Unknown world '" + worldName + "'.");
            return true;
        }
        final Path worldFolder = world.getWorldFolder().toPath();
        final List<Path> folders = new ArrayList<>(net.linear.probe.LinearFolderNames.STORAGE_FOLDERS.size());
        for (String folderName : net.linear.probe.LinearFolderNames.STORAGE_FOLDERS) {
            folders.add(worldFolder.resolve(folderName));
        }
        final Path conflict = FolderClaims.claimAll(folders);
        if (conflict != null) {
            sender.sendMessage(FolderClaims.busyMessage(conflict));
            return true;
        }
        boolean handedOff = false;
        try {
            int total = 0;
            for (Path folder : folders) {
                total += countCandidates.applyAsInt(folder);
            }
            final String sysLevel = System.getProperty(LinearPolicy.SYSPROP_LEVEL);
            final int effectiveLevel = LinearPolicy.resolveLevel(
                sysLevel, parsedLevel, net.linear.RegionFileFormat.DEFAULT_COMPRESSION_LEVEL);
            if (sysLevel != null && effectiveLevel != parsedLevel) {
                sender.sendMessage(LinearPolicy.overrideLine(LinearPolicy.SYSPROP_LEVEL, sysLevel,
                    String.valueOf(parsedLevel), String.valueOf(effectiveLevel), "global"));
            }
            final ConvertJob job = QUEUE.submit(new JobQueue.Spec(
                direction, worldName, effectiveLevel, threads, false, total));
            if (explicit != null) {
                job.setExplicit(explicit.from(), explicit.to(), explicit.grid());
            }
            job.start();
            final long queuedMs = System.currentTimeMillis();
            Bukkit.getLogger().info("[Linear] Queued convert " + job.shortId() + " (" + label + " " + worldName
                + " level " + effectiveLevel + " threads " + threads + "); running off-tick.");
            sender.sendMessage("Queued convert " + JobStatusView.queueListLine(job, queuedMs)
                + "; running off-tick.");
            // The quiesce gate (flushAllDirty + dirty check) and the conversion
            // run inside the worker, never on the tick thread: a whole-region
            // zstd flush can exceed the watchdog limit.
            final Thread worker = new Thread(
                () -> {
                    try {
                        runConvertWorker(sender, job, folders, effectiveLevel, direction, worldName);
                    } finally {
                        FolderClaims.releaseAll(folders);
                    }
                },
                "linear-convert-" + job.id().toString().substring(0, 8));
            worker.setDaemon(true);
            worker.start();
            handedOff = true;
            return true;
        } finally {
            if (!handedOff) {
                FolderClaims.releaseAll(folders);
            }
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

    private static void runConvertWorker(CommandSender sender, ConvertJob job,
            List<Path> folders, int effectiveLevel, ConvertDirection direction, String world) {
        final boolean reverse =
            direction.targetFormat() == net.linear.RegionFileFormat.ANVIL;
        if (reverse) {
            setReverseOverride(world, folders);
        }
        try {
            // Quiesce gate: force-drain, then require zero dirty files before
            // the conversion starts (same barrier the save path uses).
            LinearFlushCoordinator.flushAllDirty(true);
            int dirty = 0;
            try {
                for (net.linear.LinearRegionTimings.LinearFolderSnapshot snapshot
                        : LinearFlushCoordinator.snapshots().values()) {
                    dirty += snapshot.dirtyDepth();
                }
            } catch (RuntimeException gate) {
                failConvert(sender, job, "quiesce gate unreadable (" + gate + "); convert NOT started",
                    world, folders, reverse);
                return;
            }
            if (dirty != 0) {
                failConvert(sender, job, "quiesce gate: " + dirty
                    + " dirty file(s) remain after flush; convert NOT started",
                    world, folders, reverse);
                return;
            }
            runConversion(sender, job, folders, effectiveLevel);
        } finally {
            if (reverse) {
                clearReverseOverride(world, folders);
            }
        }
    }

    private static void failConvert(CommandSender sender, ConvertJob job, String reason,
            String world, List<Path> folders, boolean reverse) {
        try {
            job.fail(reason);
        } catch (IllegalStateException ignored) {
        }
        sender.sendMessage("Convert " + job.shortId() + " FAILED: " + reason
            + " " + JobStatusView.failedAction(reason));
        if (reverse) {
            clearReverseOverride(world, folders);
        }
    }

    // Off-tick worker: core JobRunner converts every folder (direction and
    // explicit routing owned by core, same as Folia and Horizon) and settles
    // the job; the reverse override is cleared by runConvertWorker.
    private static void runConversion(CommandSender sender, ConvertJob job, List<Path> folders, int level) {
        net.linear.command.queue.JobRunner.run(job, folders, level, Math.max(1, job.threads()));
        final long nowMs = System.currentTimeMillis();
        final String status = JobStatusView.statusLine(job, nowMs);
        Bukkit.getLogger().info("[Linear] Convert " + job.shortId() + " settled: " + status);
        sender.sendMessage("Convert " + job.shortId() + " " + status);
        for (String line : JobStatusView.detailLines(job, nowMs)) {
            sender.sendMessage(line);
        }
    }

    // Reverse holds a LinearFormatOverride ANVIL for the run so new writes
    // land in .mca: world-name scope plus every converted storage-folder
    // path scope (the same plain toString() the NMS resolution sites
    // consult — they have no Bukkit world handle at construction).
    private static void setReverseOverride(String world, List<Path> folders) {
        LinearFormatOverride.set(world, "ANVIL");
        for (Path folder : folders) {
            LinearFormatOverride.set(folder.toString(), "ANVIL");
        }
    }

    private static void clearReverseOverride(String world, List<Path> folders) {
        LinearFormatOverride.clear(world);
        for (Path folder : folders) {
            LinearFormatOverride.clear(folder.toString());
        }
    }

    /**
     * Legacy progress denominator: union-per-target convertible non-target files
     * matching {@code REGION_NAME} (mirrors
     * {@code collectForwardCandidates/collectReverseCandidates} extension rule
     * read-only; counting stays an estimate for progress only).
     * Toward LINEAR counts {@code .mca + .b_linear}, toward B_LINEAR counts
     * {@code .mca + .linear}, toward ANVIL counts {@code .linear + .b_linear}
     * (fixes the old single-extension miss where {@code .b_linear} made the
     * denominator too small and progress exceed the real queue).
     * Same-coords ambiguous groups (two sources sharing coords) and
     * target-occupied coords (source beside an existing target) count 0 for that
     * coords: the real collection escalates valid shadows/ambiguous to FAILED
     * (0 queued) and only requeues invalid-shadow orphans after file-IO validation
     * (not leg-visible); when in doubt UNDERcount. Folder-dirty skips and Pumpkin
     * layout detection (needs detector IO) can only reduce the real queue further.
     */
    private static int countLegacyCandidates(Path folder, net.linear.RegionFileFormat target) {
        final String targetExt;
        if (target == net.linear.RegionFileFormat.B_LINEAR) {
            targetExt = net.linear.RegionFileFormat.B_LINEAR_EXTENSION;
        } else if (target == net.linear.RegionFileFormat.ANVIL) {
            targetExt = net.linear.RegionFileFormat.ANVIL_EXTENSION;
        } else {
            targetExt = net.linear.RegionFileFormat.LINEAR_EXTENSION;
        }
        final java.util.Map<String, List<String>> sourcesByCoords = new java.util.HashMap<>();
        final Set<String> targetCoords = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final String name = file.getFileName().toString();
                final java.util.regex.Matcher m = net.linear.probe.LinearStorageProbe.REGION_NAME.matcher(name);
                if (!m.matches()) {
                    continue;
                }
                final String coords = m.group(1) + "," + m.group(2);
                if (name.endsWith(targetExt)) {
                    targetCoords.add(coords);
                } else if (name.endsWith(net.linear.RegionFileFormat.ANVIL_EXTENSION)
                    || name.endsWith(net.linear.RegionFileFormat.LINEAR_EXTENSION)
                    || name.endsWith(net.linear.RegionFileFormat.B_LINEAR_EXTENSION)) {
                    sourcesByCoords.computeIfAbsent(coords, k -> new ArrayList<>()).add(name);
                }
            }
        } catch (Exception ignored) {
            return 0;
        }
        int count = 0;
        for (java.util.Map.Entry<String, List<String>> e : sourcesByCoords.entrySet()) {
            if (e.getValue().size() > 1) {
                continue;
            }
            if (targetCoords.contains(e.getKey())) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * Explicit progress denominator: extension-level {@code --from} filtering plus
     * target exclusion with the anchored {@code REGION_NAME} gate (mirrors
     * {@code collectExplicitCandidates} read-only; counting stays an estimate for
     * progress only, never duplicates core collection).
     * Same-extension {@code .linear} targets (V1/V2/V3/PUMPKIN share {@code .linear}
     * per FR7) need detector IO to tell already-target layouts from same-path swaps,
     * so same-extension {@code .linear} files are excluded (UNDERcount) with this
     * comment. Same-coords ambiguous and target-occupied groups count 0 (real escalates
     * to FAILED, 0 queued). Pumpkin AUTO skip ({@code --from auto} with a non-Pumpkin
     * target skips Pumpkin sources) and folder-dirty skips need file/detector IO and can
     * only reduce the real queue further.
     */
    private static int countExplicitCandidates(Path folder,
        LinearRegionConverter.ConvertFormat from, LinearRegionConverter.ConvertFormat to) {
        if (to == null) {
            return 0;
        }
        final String targetExt = to.extension();
        final java.util.Map<String, List<Path>> byCoords = new java.util.HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final String name = file.getFileName().toString();
                final java.util.regex.Matcher m = net.linear.probe.LinearStorageProbe.REGION_NAME.matcher(name);
                if (!m.matches()) {
                    continue;
                }
                if (!name.endsWith(net.linear.RegionFileFormat.ANVIL_EXTENSION)
                    && !name.endsWith(net.linear.RegionFileFormat.LINEAR_EXTENSION)
                    && !name.endsWith(net.linear.RegionFileFormat.B_LINEAR_EXTENSION)) {
                    continue;
                }
                if (!fromMatchesExtension(name, from, targetExt)) {
                    continue;
                }
                // Same-extension .linear targets: detector needed to separate already-target
                // layouts from convertible same-path swaps; UNDERcount by excluding.
                if (targetExt.equals(net.linear.RegionFileFormat.LINEAR_EXTENSION)
                    && name.endsWith(net.linear.RegionFileFormat.LINEAR_EXTENSION)) {
                    continue;
                }
                if (name.endsWith(targetExt)) {
                    continue;
                }
                final String coords = m.group(1) + "," + m.group(2);
                byCoords.computeIfAbsent(coords, k -> new ArrayList<>()).add(file);
            }
        } catch (Exception ignored) {
            return 0;
        }
        int count = 0;
        for (java.util.Map.Entry<String, List<Path>> e : byCoords.entrySet()) {
            // Ambiguous same-coords sources mapping onto one target path: real escalates
            // to FAILED with 0 queued; UNDERcount as 0.
            if (e.getValue().size() > 1) {
                continue;
            }
            count++;
        }
        // Target-occupied coords (a file already at the explicit target path) need
        // validation IO to separate valid-shadow escalation (0) from orphan-delete + requeue (1);
        // leg-visible check: if the target path exists on disk, UNDERcount that coords as 0.
        // Re-scan for target-path occupancy (cheap, name-only, no file IO beyond existence).
        for (java.util.Map.Entry<String, List<Path>> e : byCoords.entrySet()) {
            if (e.getValue().size() != 1) {
                continue;
            }
            final Path source = e.getValue().get(0);
            final String sourceName = source.getFileName().toString();
            String base = sourceName;
            for (String known : new String[] {net.linear.RegionFileFormat.ANVIL_EXTENSION,
                net.linear.RegionFileFormat.LINEAR_EXTENSION,
                net.linear.RegionFileFormat.B_LINEAR_EXTENSION}) {
                if (base.endsWith(known)) {
                    base = base.substring(0, base.length() - known.length()) + targetExt;
                    break;
                }
            }
            try {
                if (Files.isRegularFile(source.resolveSibling(base))) {
                    count--;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return Math.max(0, count);
    }

    /**
     * Extension-level {@code --from} gate (leg-visible part of
     * {@code matchesFrom} without detector IO; detector-level narrowing within
     * {@code .linear} (V1/V2/V3/PUMPKIN) is not leg-visible and stays an estimate
     * with the UNDERcount comment above).
     */
    private static boolean fromMatchesExtension(String fileName,
        LinearRegionConverter.ConvertFormat from, String targetExt) {
        if (from == null || from == LinearRegionConverter.ConvertFormat.AUTO) {
            return true;
        }
        if (from == LinearRegionConverter.ConvertFormat.MCA) {
            return fileName.endsWith(net.linear.RegionFileFormat.ANVIL_EXTENSION);
        }
        if (from == LinearRegionConverter.ConvertFormat.B_LINEAR) {
            return fileName.endsWith(net.linear.RegionFileFormat.B_LINEAR_EXTENSION);
        }
        // V1/V2/V3/PUMPKIN/LINEAR all share .linear (family wildcard for LINEAR);
        // extension-level match only, detector narrows further in the real
        // collection. Honest direction: a layout-specific --from (v1/v2/v3,
        // pumpkin) may OVERcount here when other layouts share the folder;
        // every other rule in this counter UNDERcounts. Progress-only either way.
        return fileName.endsWith(net.linear.RegionFileFormat.LINEAR_EXTENSION);
    }

    // Converter listener: forwards per-file outcomes to the job and parks the
    // worker while the job is PAUSED (pause polling). A record racing a
    // pause retries after the unpause; terminal states drop the record.
    // Bukkit-to-core sender adaptation (plain strings only).
    private static final class BukkitSender implements net.linear.command.CommandSender {
        private final CommandSender sender;

        private BukkitSender(CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public void sendMessage(String message) {
            this.sender.sendMessage(message);
        }

        @Override
        public boolean hasPermission(String permission) {
            return this.sender.hasPermission(permission);
        }

        @Override
        public String name() {
            return this.sender.getName();
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        List<String> base = LinearTabCompleter.suggest(args, QUEUE, () -> {
            try {
                final Set<String> names = new HashSet<>();
                for (org.bukkit.World w : Bukkit.getWorlds()) {
                    names.add(w.getName());
                }
                return names;
            } catch (RuntimeException empty) {
                return Collections.emptySet();
            }
        });
        return withExplicitConvertFlags(args, base);
    }

    /**
     * Suggests the explicit any-to-any flags ({@code --from/--to/--grid}) in
     * the convert flag slot. Core completion owns the values after each flag
     * and the legacy flag list (frozen); this leg only adds the three flag
     * names where a flag is being typed, never in a value or world slot.
     */
    private static List<String> withExplicitConvertFlags(String[] args, List<String> base) {
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
}
