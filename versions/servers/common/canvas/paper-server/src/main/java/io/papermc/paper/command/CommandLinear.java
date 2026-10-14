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
import net.linear.command.queue.JobQueue;
import net.linear.command.queue.JobState;
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
import net.linear.command.queue.JobRunner;

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
 *   <li>{@code convert --execute} runs
 *       {@link LinearRegionConverter#convertRegionFolder} (towards LINEAR) or
 *       {@link LinearRegionConverter#convertRegionFolderReverse} (towards MCA,
 *       via the NMS {@code AnvilChunkSink}) off-tick (daemon worker, never the
 *       tick thread) with per-file job updates and pause polling in the
 *       listener; dry runs plan only (core executor message, no conversion).
 *       Explicit any-to-any jobs ({@code --from/--to/--grid --execute}, the
 *       only way Pumpkin migrates) run
 *       {@code convertRegionFolderExplicit} through the same worker.
 *       Reverse sets a {@code LinearFormatOverride} ANVIL on the world so new
 *       writes land in {@code .mca} while unconverted {@code .linear} files
 *       keep serving reads, cleared when the worker settles. A quiesce gate
 *       ({@code flushAllDirty(true)} then dirty-depth zero) guards every
 *       execute path (legacy and explicit alike). Operator lines route through the core
 *       {@link JobStatusView} templates.</li>
 * </ul>
 */
public class CommandLinear extends Command {

    // Storage folders converted per world (mirrors the startup pass).
    private static final List<String> STORAGE_FOLDERS = List.of("region", "entities", "poi");

    // Per-folder single-flight: in-flight storage-folder keys (absolute normalized paths).
    // A second --execute on an in-flight folder is rejected with
    // "conversion already running for <folder>" (no queue, no block); entries are
    // removed in finally after the worker finishes (including failure paths).
    // Covers legacy AND explicit paths (both spawn threads).
    private static final java.util.regex.Pattern REGION_NAME =
        java.util.regex.Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.(mca|linear|b_linear)$");
    private static final java.util.Set<String> IN_FLIGHT_FOLDERS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    // In-memory convert-job registry, shared by every /linear invocation.
    static final JobQueue QUEUE = new JobQueue();

    private final LinearCommandExecutor executor =
        new LinearCommandExecutor(QUEUE, LinearFlushCoordinator::snapshots);

    public CommandLinear(String name) {
        super(name);
        this.setDescription("Linear region management (stats/convert/queue/help)");
        this.setUsage("/linear stats [world] | /linear convert <world> [--to-linear|--to-mca|--to-blinear] [--from mca|v1|v2|v3|blinear|pumpkin|linear|auto] [--to mca|v1|v2|v3|blinear|pumpkin|linear] [--grid 1|2|4|8|16|32] [--level 1..22] [--threads N] [--execute|--dry-run] | /linear queue list|clear|pause|resume|cancel [jobId] | /linear help. Pumpkin migrates explicit-only (--from pumpkin --to <target> --execute); startup never rewrites Pumpkin files.");
        this.setPermission(LinearPermissions.BASE);
        this.setPermissionMessage(LinearPermissions.denied("linear"));
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
            // Single rendering path: call the pinned panel, never fork it.
            final String[] statsArgs = stats.worldFilter()
                .map(world -> new String[]{world})
                .orElseGet(() -> new String[0]);
            return new CommandLinearStats("linear").execute(sender, commandLabel, statsArgs);
        }
        if (action instanceof LinearAction.ConvertAction convert && !convert.dryRun()) {
            return executeConvert(sender, convert);
        }
        if (action instanceof LinearAction.ExplicitConvertAction explicit && !explicit.dryRun()) {
            return executeExplicitConvert(sender, explicit);
        }
        // Help, queue ops, and dry-run convert plans (no conversion runs).
        this.executor.execute(new BukkitSender(sender), action);
        if (action instanceof LinearAction.HelpAction) {
            sender.sendMessage(PUMPKIN_RULE_LINE);
        }
        return true;
    }

    /**
     * Real conversion execution (dry runs never reach here).
     * Direction routes to the matching converter entry point
     * (core {@code JobRunner.run} switches the same way); reverse holds a
     * {@code LinearFormatOverride} ANVIL for the run, cleared on settle.
     */
    private static boolean executeConvert(CommandSender sender, LinearAction.ConvertAction convert) {
        if (!sender.hasPermission(LinearPermissions.CONVERT)
                && !sender.hasPermission(LinearPermissions.BASE)) {
            sender.sendMessage(LinearPermissions.denied("linear convert"));
            return true;
        }
        final org.bukkit.World world = Bukkit.getWorld(convert.world());
        if (world == null) {
            sender.sendMessage("Unknown world '" + convert.world() + "'.");
            return true;
        }
        final Path worldFolder = world.getWorldFolder().toPath();
        final List<Path> folders = new ArrayList<>(STORAGE_FOLDERS.size());
        for (String folderName : STORAGE_FOLDERS) {
            folders.add(worldFolder.resolve(folderName));
        }
        final Path conflict = claimFoldersOrConflict(folders);
        if (conflict != null) {
            sender.sendMessage("conversion already running for " + conflict);
            return true;
        }
        int total = 0;
        final net.linear.RegionFileFormat legacyTarget = convert.direction().targetFormat();
        for (Path folder : folders) {
            total += countLegacyCandidates(folder, legacyTarget);
        }
        final String sysLevel = System.getProperty(LinearPolicy.SYSPROP_LEVEL);
        final int effectiveLevel = LinearPolicy.resolveLevel(
            sysLevel, convert.level(), net.linear.RegionFileFormat.DEFAULT_COMPRESSION_LEVEL);
        if (sysLevel != null && effectiveLevel != convert.level()) {
            sender.sendMessage(LinearPolicy.overrideLine(LinearPolicy.SYSPROP_LEVEL, sysLevel,
                String.valueOf(convert.level()), String.valueOf(effectiveLevel), "global"));
        }
        final ConvertJob job = QUEUE.submit(new JobQueue.Spec(
            convert.direction(), convert.world(), effectiveLevel,
            convert.threads(), false, total));
        job.start();
        final long queuedMs = System.currentTimeMillis();
        Bukkit.getLogger().info("[Linear] Queued convert " + job.shortId()
            + " (" + convert.direction().plainDirection() + " " + convert.world()
            + " level " + effectiveLevel + " threads " + convert.threads()
            + "); running off-tick.");
        sender.sendMessage("Queued convert " + JobStatusView.queueListLine(job, queuedMs)
            + "; running off-tick.");
        // The quiesce gate (flushAllDirty + dirty check) and the conversion
        // itself run inside the worker below — never on the calling (tick)
        // thread. A whole-region zstd flush can exceed the watchdog limit,
        // so even the drain must stay off-tick.
        final List<Path> claimed = List.copyOf(folders);
        final Thread worker = new Thread(
            () -> {
                try {
                    runConvertWorker(sender, job, folders, effectiveLevel,
                        convert.direction(), convert.world());
                } finally {
                    releaseFolders(claimed);
                }
            },
            "linear-convert-" + job.id().toString().substring(0, 8));
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException startFailed) {
            releaseFolders(claimed);
            throw startFailed;
        }
        return true;
    }

    /**
     * Explicit any-to-any convert execution ({@code --from/--to/--grid},
     * {@code --execute} only; dry runs plan via the core executor above).
     * Mirrors {@link #executeConvert}: the same quiesce-gated worker runs the
     * job (no new guard semantics). The job carries a legacy placeholder
     * direction for display plus explicit {@code from/to/grid} routing, which
     * {@link #runConversionInner} switches on per folder; the core folder-dirty
     * per-file skip applies exactly as for legacy jobs.
     */
    private static boolean executeExplicitConvert(
        CommandSender sender, LinearAction.ExplicitConvertAction explicit) {
        if (!sender.hasPermission(LinearPermissions.CONVERT)
                && !sender.hasPermission(LinearPermissions.BASE)) {
            sender.sendMessage(LinearPermissions.denied("linear convert"));
            return true;
        }
        final org.bukkit.World world = Bukkit.getWorld(explicit.world());
        if (world == null) {
            sender.sendMessage("Unknown world '" + explicit.world() + "'.");
            return true;
        }
        final Path worldFolder = world.getWorldFolder().toPath();
        final List<Path> folders = new ArrayList<>(STORAGE_FOLDERS.size());
        for (String folderName : STORAGE_FOLDERS) {
            folders.add(worldFolder.resolve(folderName));
        }
        final Path conflict = claimFoldersOrConflict(folders);
        if (conflict != null) {
            sender.sendMessage("conversion already running for " + conflict);
            return true;
        }
        int total = 0;
        for (Path folder : folders) {
            total += countExplicitCandidates(folder, explicit.from(), explicit.to());
        }
        final String sysLevel = System.getProperty(LinearPolicy.SYSPROP_LEVEL);
        final int effectiveLevel = LinearPolicy.resolveLevel(
            sysLevel, explicit.level(), net.linear.RegionFileFormat.DEFAULT_COMPRESSION_LEVEL);
        if (sysLevel != null && effectiveLevel != explicit.level()) {
            sender.sendMessage(LinearPolicy.overrideLine(LinearPolicy.SYSPROP_LEVEL, sysLevel,
                String.valueOf(explicit.level()), String.valueOf(effectiveLevel), "global"));
        }
        final ConvertDirection placeholder = placeholderForExplicit(explicit.to());
        final ConvertJob job = QUEUE.submit(new JobQueue.Spec(
            placeholder, explicit.world(), effectiveLevel,
            explicit.threads(), false, total));
        job.setExplicit(explicit.from(), explicit.to(), explicit.grid());
        job.start();
        final long queuedMs = System.currentTimeMillis();
        Bukkit.getLogger().info("[Linear] Queued convert " + job.shortId()
            + " (explicit " + explicit.from().name().toLowerCase(java.util.Locale.ROOT)
            + " to " + explicit.to().name().toLowerCase(java.util.Locale.ROOT)
            + " " + explicit.world()
            + " level " + effectiveLevel + " threads " + explicit.threads()
            + "); running off-tick.");
        sender.sendMessage("Queued convert " + JobStatusView.queueListLine(job, queuedMs)
            + "; running off-tick.");
        // Same quiesce-gated worker as legacy convert (never the tick thread).
        final List<Path> claimed = List.copyOf(folders);
        final Thread worker = new Thread(
            () -> {
                try {
                    runConvertWorker(sender, job, folders, effectiveLevel,
                        placeholder, explicit.world());
                } finally {
                    releaseFolders(claimed);
                }
            },
            "linear-convert-" + job.id().toString().substring(0, 8));
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException startFailed) {
            releaseFolders(claimed);
            throw startFailed;
        }
        return true;
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
            runConversion(sender, job, folders, effectiveLevel, direction, world);
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

    // Off-tick worker: one folder at a time through the converter, per-file
    // outcomes forwarded to the job by the listener. Completion/failure and
    // the operator's sender message settle here. Reverse holds the ANVIL
    // override for the run; it is cleared in the finally below (and on the
    // quiesce-fail arms above, which never start the worker).
    private static void runConversion(CommandSender sender, ConvertJob job,
            List<Path> folders, int level, ConvertDirection direction, String scope) {
        final boolean reverse =
            direction.targetFormat() == net.linear.RegionFileFormat.ANVIL;
        try {
            runConversionInner(sender, job, folders, level, reverse);
        } finally {
            if (reverse) {
                clearReverseOverride(scope, folders);
            }
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

    private static void runConversionInner(CommandSender sender, ConvertJob job,
            List<Path> folders, int level, boolean reverse) {
        final ConvertListener listener = new ConvertListener(job);
        int converted = 0;
        int validated = 0;
        int deleted = 0;
        int failed = 0;
        Path current = folders.isEmpty() ? Path.of(".") : folders.get(0);
        try {
            for (Path folder : folders) {
                current = folder;
                if (job.state() == JobState.CANCELLED) {
                    break;
                }
                final LinearRegionConverter.ConversionSummary summary;
                if (job.isExplicit()) {
                    // Explicit any-to-any: layout-level from/to routing with
                    // the per-job grid (same gate above, same
                    // validate-then-delete below; the core skips folder-dirty
                    // files per file exactly as for legacy jobs).
                    summary = LinearRegionConverter.convertRegionFolderExplicit(
                        folder, level, job.gridSize(), listener, false,
                        job.fromFormat(), job.toFormat());
                } else {
                    summary = reverse
                        ? LinearRegionConverter.convertRegionFolderReverse(folder, level, listener)
                        : LinearRegionConverter.convertRegionFolder(folder, level, listener);
                }
                converted += summary.converted();
                validated += summary.validated();
                deleted += summary.deleted();
                failed += summary.failed();
            }
        } catch (LinearRegionConverter.ConversionProtectionException halted) {
            final LinearRegionConverter.ConversionSummary summary = halted.getSummary();
            final String reason = "protection halt in " + current + ": "
                + summary.failures().size() + " file(s) failed after "
                + LinearRegionConverter.MAX_ATTEMPTS + " attempts"
                + " (converted=" + summary.converted()
                + " validated=" + summary.validated()
                + " deleted=" + summary.deleted()
                + " failed=" + summary.failed() + "); sources left untouched for inspection";
            try {
                job.fail(reason);
            } catch (IllegalStateException ignored) {
            }
            // Sender messaging from the worker thread: view lines only (plain
            // strings, never world state — callers must schedule back for world
            // access); the full reason with paths and tallies goes to the log.
            Bukkit.getLogger().warning("[Linear] Convert " + job.shortId() + " FAILED: " + reason);
            sender.sendMessage("Convert " + job.shortId() + " FAILED. "
                + JobStatusView.failedAction(reason));
            return;
        } catch (RuntimeException broken) {
            final String reason = "unexpected error in " + current + ": " + broken;
            try {
                job.fail(reason);
            } catch (IllegalStateException ignored) {
            }
            Bukkit.getLogger().warning("[Linear] Convert " + job.shortId() + " FAILED: " + reason);
            sender.sendMessage("Convert " + job.shortId() + " FAILED. "
                + JobStatusView.failedAction(reason));
            return;
        }
        final String totals = "converted=" + converted + " validated=" + validated
            + " deleted=" + deleted + " failed=" + failed;
        Bukkit.getLogger().info("[Linear] Convert " + job.shortId() + " settled (" + totals + ").");
        final long settledMs = System.currentTimeMillis();
        try {
            if (job.state() == JobState.CANCELLED) {
                sender.sendMessage("Convert " + job.shortId() + " cancelled. "
                    + JobStatusView.statusLine(job, settledMs));
                return;
            }
            job.complete();
            sender.sendMessage("Convert " + job.shortId() + " done. "
                + JobStatusView.statusLine(job, settledMs));
        } catch (IllegalStateException settled) {
            sender.sendMessage("Convert " + job.shortId()
                + " settled as " + job.state() + " "
                + JobStatusView.statusLine(job, settledMs));
        }
    }

    private static String folderKey(Path folder) {
        try {
            return folder.toAbsolutePath().normalize().toString();
        } catch (RuntimeException fallback) {
            return folder.toString();
        }
    }

    /**
     * Tries to claim every folder for a convert worker. Returns null on success
     * (all claimed); otherwise returns the conflicting in-flight folder and releases
     * any folders claimed by this attempt (no partial claim, no queue, no block).
     */
    private static Path claimFoldersOrConflict(List<Path> folders) {
        final List<String> claimed = new ArrayList<>(folders.size());
        for (Path folder : folders) {
            final String key = folderKey(folder);
            if (!IN_FLIGHT_FOLDERS.add(key)) {
                for (String k : claimed) {
                    IN_FLIGHT_FOLDERS.remove(k);
                }
                return folder;
            }
            claimed.add(key);
        }
        return null;
    }

    private static void releaseFolders(List<Path> folders) {
        for (Path folder : folders) {
            try {
                IN_FLIGHT_FOLDERS.remove(folderKey(folder));
            } catch (RuntimeException ignored) {
            }
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
        final java.util.Map<String, java.util.List<String>> sourcesByCoords = new java.util.HashMap<>();
        final java.util.Set<String> targetCoords = new java.util.HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final String name = file.getFileName().toString();
                final java.util.regex.Matcher m = REGION_NAME.matcher(name);
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
        for (java.util.Map.Entry<String, java.util.List<String>> e : sourcesByCoords.entrySet()) {
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
        final java.util.Map<String, java.util.List<Path>> byCoords = new java.util.HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final String name = file.getFileName().toString();
                final java.util.regex.Matcher m = REGION_NAME.matcher(name);
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
        for (java.util.Map.Entry<String, java.util.List<Path>> e : byCoords.entrySet()) {
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
        for (java.util.Map.Entry<String, java.util.List<Path>> e : byCoords.entrySet()) {
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
    private static final class ConvertListener implements LinearRegionConverter.Listener {
        private final ConvertJob job;

        private ConvertListener(ConvertJob job) {
            this.job = job;
        }

        @Override
        public void onFile(LinearRegionConverter.FileResult result) {
            awaitUnpaused(this.job);
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    this.job.recordFile(result);
                    return;
                } catch (IllegalStateException busy) {
                    final JobState state = this.job.state();
                    if (state == JobState.DONE || state == JobState.FAILED
                            || state == JobState.CANCELLED) {
                        return;
                    }
                    awaitUnpaused(this.job);
                }
            }
        }

        @Override
        public void onRetry(Path source, int attempt, String reason) {
            awaitUnpaused(this.job);
        }

        private static void awaitUnpaused(ConvertJob job) {
            while (job.state() == JobState.PAUSED) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

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
                for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
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
