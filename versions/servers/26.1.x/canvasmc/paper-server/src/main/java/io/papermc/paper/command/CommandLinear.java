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
import net.linear.command.InvalidUsageException;
import net.linear.command.JobQueue;
import net.linear.command.JobState;
import net.linear.command.JobStatusView;
import net.linear.command.LinearAction;
import net.linear.command.LinearCommandExecutor;
import net.linear.command.LinearCommandParser;
import net.linear.command.LinearPermissions;
import net.linear.command.LinearTabCompleter;
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
 *   <li>{@code convert --execute} runs
 *       {@link LinearRegionConverter#convertRegionFolder} (towards LINEAR) or
 *       {@link LinearRegionConverter#convertRegionFolderReverse} (towards MCA,
 *       via the NMS {@code AnvilChunkSink}) off-tick (daemon worker, never the
 *       tick thread) with per-file job updates and pause polling in the
 *       listener; dry runs plan only (core executor message, no conversion).
 *       Reverse sets a {@code LinearFormatOverride} ANVIL on the world so new
 *       writes land in {@code .mca} while unconverted {@code .linear} files
 *       keep serving reads, cleared when the worker settles. A quiesce gate
 *       ({@code flushAllDirty(true)} then dirty-depth zero) guards both
 *       execute paths. Operator lines route through the core
 *       {@link JobStatusView} templates.</li>
 * </ul>
 */
public class CommandLinear extends Command {

    // Storage folders converted per world (mirrors the startup pass).
    private static final List<String> STORAGE_FOLDERS = List.of("region", "entities", "poi");

    // In-memory convert-job registry, shared by every /linear invocation.
    static final JobQueue QUEUE = new JobQueue();

    private final LinearCommandExecutor executor =
        new LinearCommandExecutor(QUEUE, LinearFlushCoordinator::snapshots);

    public CommandLinear(String name) {
        super(name);
        this.setDescription("Linear region management (stats/convert/queue/help)");
        this.setUsage("/linear stats [world] | /linear convert <world> [--to-linear|--to-mca] [--level 1..22] [--threads N] [--execute|--dry-run] | /linear queue list|clear|pause|resume|cancel [jobId] | /linear help");
        this.setPermission(LinearPermissions.BASE);
        this.setPermissionMessage(LinearPermissions.denied("linear"));
    }

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
        // Help, queue ops, and dry-run convert plans (no conversion runs).
        this.executor.execute(new BukkitSender(sender), action);
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
        int total = 0;
        final boolean reverse = convert.direction() == ConvertDirection.LINEAR_TO_MCA;
        for (Path folder : folders) {
            total += reverse ? countLinearFiles(folder) : countMcaFiles(folder);
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
        final Thread worker = new Thread(
            () -> runConvertWorker(sender, job, folders, effectiveLevel, convert),
            "linear-convert-" + job.id().toString().substring(0, 8));
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static void runConvertWorker(CommandSender sender, ConvertJob job,
            List<Path> folders, int effectiveLevel, LinearAction.ConvertAction convert) {
        final boolean reverse = convert.direction() == ConvertDirection.LINEAR_TO_MCA;
        if (reverse) {
            setReverseOverride(convert.world(), folders);
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
                    convert, folders, reverse);
                return;
            }
            if (dirty != 0) {
                failConvert(sender, job, "quiesce gate: " + dirty
                    + " dirty file(s) remain after flush; convert NOT started",
                    convert, folders, reverse);
                return;
            }
            runConversion(sender, job, folders, effectiveLevel, convert.direction(), convert.world());
        } finally {
            if (reverse) {
                clearReverseOverride(convert.world(), folders);
            }
        }
    }

    private static void failConvert(CommandSender sender, ConvertJob job, String reason,
            LinearAction.ConvertAction convert, List<Path> folders, boolean reverse) {
        try {
            job.fail(reason);
        } catch (IllegalStateException ignored) {
        }
        sender.sendMessage("Convert " + job.shortId() + " FAILED: " + reason
            + " " + JobStatusView.failedAction(reason));
        if (reverse) {
            clearReverseOverride(convert.world(), folders);
        }
    }

    // Off-tick worker: one folder at a time through the converter, per-file
    // outcomes forwarded to the job by the listener. Completion/failure and
    // the operator's sender message settle here. Reverse holds the ANVIL
    // override for the run; it is cleared in the finally below (and on the
    // quiesce-fail arms above, which never start the worker).
    private static void runConversion(CommandSender sender, ConvertJob job,
            List<Path> folders, int level, ConvertDirection direction, String scope) {
        final boolean reverse = direction == ConvertDirection.LINEAR_TO_MCA;
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
                final LinearRegionConverter.ConversionSummary summary = reverse
                    ? LinearRegionConverter.convertRegionFolderReverse(folder, level, listener)
                    : LinearRegionConverter.convertRegionFolder(folder, level, listener);
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

    private static int countMcaFiles(Path folder) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "r.*.mca")) {
            int count = 0;
            for (Path ignored : stream) {
                count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int countLinearFiles(Path folder) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "r.*.linear")) {
            int count = 0;
            for (Path ignored : stream) {
                count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
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
        return LinearTabCompleter.suggest(args, QUEUE, () -> {
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
    }
}
