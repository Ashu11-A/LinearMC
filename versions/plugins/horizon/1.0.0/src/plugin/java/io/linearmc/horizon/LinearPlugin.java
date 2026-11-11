package io.linearmc.horizon;

import java.nio.file.Path;
import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionConverter;
import net.linear.LinearRegionFile;
import io.linearmc.horizon.event.LinearRegionFlushCompletedEvent;
import net.linear.LinearRegionTimings;
import net.linear.probe.LinearFolderNames;
import net.linear.RegionFileFormat;
import net.linear.config.LinearFileConfig;
import net.linear.config.LinearPolicy;
import org.bukkit.plugin.java.JavaPlugin;

public class LinearPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // Eager warm-up FIRST: force-load every core/leg class plus the NMS
        // mixin targets on this enable thread, so steady-state region/tick
        // threads never pay class-load, nested-jar read, native-extract, or
        // Mixin/transform costs. Missing classes are skipped, never fatal.
        getLogger().info("LinearMC: host=" + HostPlatform.detect()
            + " (generic Paper/Folia/Canvas 26.1-26.3 adapter)");
        final net.linear.probe.LinearWarmup.Result warmed = net.linear.probe.LinearWarmup.touch(
            LinearPlugin.class,
            "net.linear.RegionFileFormat",
            "net.linear.ChunkKey",
            "net.linear.AbstractRegionFile",
            "net.linear.probe.AbstractRegionFileFactory",
            "net.linear.probe.LinearStorageProbe",
            "net.linear.probe.LinearFolderNames",
            "net.linear.probe.LinearWarmup",
            "net.linear.LinearRegionFile",
            "net.linear.compression.ZstdChunkCodec",
            "net.linear.io.LinearDirectStreams",
            "net.linear.compression.LinearEnvelopeCodec",
            "net.linear.io.LinearWritePath",
            "net.linear.buffered.BufferedRegionOutput",
            "net.linear.LinearFlushCoordinator",
            "net.linear.LinearRegionTimings",
            "net.linear.flush.FlushReports",
            "net.linear.FlushEvent",
            "net.linear.LinearRegionConverter",
            "net.linear.config.LinearPolicy",
            "net.linear.config.LinearFileConfig",
            "net.linear.config.LinearFormatOverride",
            "net.linear.command.queue.JobQueue",
            "net.linear.command.ConvertJob",
            "net.linear.command.queue.JobRunner",
            "net.linear.command.parse.LinearCommandParser",
            "net.linear.command.LinearCommandExecutor",
            "net.linear.command.parse.LinearTabCompleter",
            "net.linear.command.stats.LinearStatsView",
            "net.linear.command.stats.LinearStatsCompact",
            "net.linear.command.stats.LinearStatsFormat",
            "net.linear.command.stats.health.LinearStatsHealth",
            "net.linear.command.stats.health.LinearStatsStatus",
            "net.linear.command.parse.LinearPermissions",
            "net.linear.command.queue.JobStatusView",
            "net.linear.command.ConvertDirection",
            "net.linear.command.queue.JobState",
            "net.linear.command.queue.QueueOp",
            "net.linear.command.LinearAction",
            "net.linear.command.CommandSender",
            "net.linear.command.stats.StatsProvider",
            "io.linearmc.horizon.HorizonLinearRegionFile",
            "io.linearmc.horizon.HorizonBufferedRegionFile",
            "io.linearmc.horizon.ChunkPosCoords",
            "net.linear.BufferedLinearRegionFile",
            "net.linear.bucket.LinearBucketReader",
            "net.linear.bucket.LinearBucketWriter",
            "net.linear.probe.LinearBucketGrid",
            "net.linear.LinearImplementation",
            "net.linear.compression.LinearHash",
            "io.linearmc.horizon.AnvilChunkSink",
            "io.linearmc.horizon.LinearCommand",
            "io.linearmc.horizon.LinearStatsCommand",
            "io.linearmc.horizon.LinearFormatPolicy",
            "net.minecraft.world.level.chunk.storage.RegionFileStorage",
            "net.minecraft.world.level.chunk.storage.RegionFile");
        final boolean natives = net.linear.compression.ZstdChunkCodec.warmup();
        getLogger().info("LinearMC: warmed " + warmed.loaded() + " classes"
            + " (" + warmed.skipped() + " skipped) in " + warmed.millis() + "ms"
            + ", natives " + (natives ? "ready" : "lazy"));
        saveDefaultConfig();
        // The bootstrapper pushed config.yml into the core before the server
        // initialized; push here only if that failed.
        LinearConfigLoader.pushIfMissing(getDataFolder().toPath(), getLogger());
        // Live core seams (Canvas parity): the suppliers below read
        // config.yml live on every call with the docs defaults
        // (flush-max-threads 1, flush-frequency 10, compression-workers 0,
        // long-distance-matching 0); mapping/clamping is core-owned
        // (LinearPolicy via LinearFormatPolicy). The flush-completion listener
        // fires the plugin's LinearRegionFlushCompletedEvent (fork parity).
        final LinearPlugin self = this;
        LinearFlushCoordinator.linear$setFlushThreadsSupplier(() -> {
            try {
                return LinearFormatPolicy.resolveFlushThreads(
                    self.getConfig().getInt("region-format.linear.flush-max-threads", 1),
                    Runtime.getRuntime().availableProcessors());
            } catch (RuntimeException e) {
                return 1;
            }
        });
        LinearFlushCoordinator.linear$setFlushFrequencySupplier(() -> {
            try {
                return (long) LinearFormatPolicy.resolveFlushFrequency(
                    self.getConfig().getInt("region-format.linear.flush-frequency", 10));
            } catch (RuntimeException e) {
                return 10L;
            }
        });
        LinearRegionFile.linear$setCompressionWorkersSupplier(() -> {
            try {
                return LinearFormatPolicy.resolveCompressionWorkers(
                    self.getConfig().getInt("region-format.linear.compression-workers", 0));
            } catch (RuntimeException e) {
                return 0;
            }
        });
        LinearRegionFile.linear$setLongDistanceMatchingSupplier(() -> {
            try {
                return LinearFormatPolicy.resolveLongDistanceMatching(
                    self.getConfig().getInt("region-format.linear.long-distance-matching", 0));
            } catch (RuntimeException e) {
                return 0;
            }
        });
        // Core construction seams (shared with the pre-plugin startup
        // conversion, which may have registered them already; idempotent).
        HorizonSeams.registerOpeners();
        LinearFlushCoordinator.linear$setFlushListener(this::fireFlushEvent);
        LinearRegionConverter.linear$setReverseSkipPredicate(LinearPlugin::reverseSkipDirty);
        // Soak parity (d-load, Oct 2026): forward defers loaded files with
        // the same folder-dirty check as reverse.
        LinearRegionConverter.linear$setForwardSkipPredicate(LinearPlugin::reverseSkipDirty);
        // Resolution is core-owned (LinearFileConfig over LinearPolicy):
        // sysprop wins over config.yml, which wins over the shipped ANVIL
        // default (fail-open). Only "LINEAR" enables Linear; anything else
        // (unset/unknown) falls back to ANVIL, level 1..22 else 6.
        final RegionFileFormat format = LinearFileConfig.resolveFormat();
        final boolean linearFamily = RegionFileFormat.isLinearFamily(format);
        final int level = LinearFileConfig.resolveLevel();
        getLogger().info("LinearMC: format=" + format + " level=" + level
            + " implementation=" + LinearFileConfig.resolveImplementation()
            + " grid-size=" + LinearFileConfig.resolveGridSize()
            + (linearFamily ? "" : " (Linear writes off: set region-format.format or -Dlinearmc.format)"));
        if (!linearFamily) {
            getLogger().warning("LinearMC: format is ANVIL — no .linear/.b_linear files will be written.");
        }
        // Storage interception is all-or-nothing: every injector is required
        // (MixinDescriptorsTest checks each host method per dev bundle).
        getLogger().info("LinearMC: storage interception armed (create and exists paths, stop barrier after final save)");
        LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_FORMAT)
            .ifPresent(getLogger()::info);
        LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_LEVEL)
            .ifPresent(getLogger()::info);
        // Durability heartbeat: the storage flush/close paths on this server
        // line cannot be trusted to run (verified live: neither fires for
        // region files), so durability must not depend on them. A repeating
        // async drain persists age-gated files on the flush-frequency rhythm
        // without ever blocking the tick, and the post-save stop barrier
        // (MinecraftServerMixin -> LinearShutdownBarrier.finalDrain) forces
        // everything out. Without this, live writes would sit in RAM until
        // process death.
        final int heartbeatSeconds = Math.max(1, getConfig().getInt("region-format.linear.flush-frequency", 10));
        // One daemon thread for the whole server (never the Bukkit
        // scheduler: region-threaded lines throw UnsupportedOperationException
        // on async timers). Age-gated background drain only; the tick and
        // explicit saves never block on it.
        // Defensive: Bukkit guarantees onDisable between enables, but never
        // leak a thread if this instance is ever re-enabled without one.
        if (this.drainService != null) {
            try {
                this.drainService.shutdownNow();
            } catch (RuntimeException ignored) {
            }
            this.drainService = null;
        }
        this.drainService = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "linearmc-flush-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.drainService.scheduleWithFixedDelay(
            () -> net.linear.flush.FlushReports.FolderDrain.onSave(false),
            heartbeatSeconds, heartbeatSeconds, java.util.concurrent.TimeUnit.SECONDS);
        getLogger().info("LinearMC: flush heartbeat every " + heartbeatSeconds + "s (async, age-gated)");
        LinearCommand command = new LinearCommand(this);
        this.registerCommand("linear", "Linear region tools (stats, convert, queue)", command);
        this.registerCommand(
            "linearstats", "Shows Linear region timing stats", new LinearStatsCommand(this));
    }

    /**
     * Whether the host NMS exposes the Moonrise exists-path bridge
     * ({@code moonrise$getRegionFileIfExists(int, int)}): false means the
     * exists-path redirect had no target and the create path covers all
     * opens on this host. Reflective probe only, never fatal.
     */
    /**
     * Flush-completed event (fork parity: {@code LinearFlushBridge}): fired
     * once per coordinator flush that attempted at least one file, off the
     * flushing thread via the async scheduler; skipped once the plugin is
     * disabled (the stop barrier flushes after that).
     */
    private void fireFlushEvent(final String folderKey, final int filesAttempted, final long elapsedMicros) {
        if (filesAttempted < 1 || folderKey == null || !isEnabled()
            || LinearRegionFlushCompletedEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return; // nobody listens: skip the snapshot copy
        }
        try {
            final LinearRegionTimings.LinearFolderSnapshot totals = LinearFlushCoordinator.snapshots()
                .getOrDefault(folderKey, LinearRegionTimings.LinearFolderSnapshot.EMPTY);
            final LinearRegionFlushCompletedEvent event = new LinearRegionFlushCompletedEvent(
                LinearFolderNames.inferWorld(folderKey), LinearFolderNames.inferFolderType(folderKey),
                totals.filesFlushed(), totals.failures(), elapsedMicros, totals);
            try {
                getServer().getAsyncScheduler().runNow(this, task -> getServer().getPluginManager().callEvent(event));
            } catch (RuntimeException schedulerGone) {
                // Fork parity (LinearFlushBridge): fire synchronously when the
                // async scheduler refuses; the event stays async-contract.
                getServer().getPluginManager().callEvent(event);
            }
        } catch (RuntimeException ignored) {
            // Observability only: never fail a flush over an event.
        }
    }

    /** Heartbeat service for the flush drain (shut down on disable). */
    private java.util.concurrent.ScheduledExecutorService drainService;

    @Override
    public void onDisable() {
        // Plugins are disabled BEFORE the host's final world save (Paper
        // stopServer, Folia/Canvas RegionShutdownThread), so this is only a
        // pre-save drain: force what is dirty now and keep the flush pool
        // alive. The real stop barrier (shutdown mode, evict everything,
        // pool shutdown) runs after the final save from MinecraftServerMixin.
        if (this.drainService != null) {
            try {
                this.drainService.shutdownNow();
            } catch (RuntimeException ignored) {
            }
            this.drainService = null;
        }
        LinearShutdownBarrier.pluginDisableDrain();
    }

    /**
     * Skip seam (forward + reverse share it): the core tests the source file; the folder
     * owns dirtiness, so a source whose folder snapshot reports
     * {@code dirtyDepth > 0} skips ("loaded or recently written, re-run to
     * finish"). Delegates to the core probe (tracked-dirty defers;
     * never-tracked proceeds: every Linear write is tracked, so unseen
     * means clean). Open-but-clean handles stay undetected; shadow
     * protection is the backstop.
     */
    private static boolean reverseSkipDirty(final Path source) {
        try {
            if (source == null) {
                return true;
            }
            final Path folder = source.toAbsolutePath().normalize().getParent();
            if (folder == null) {
                return true;
            }
            return LinearFlushCoordinator.isFolderDirty(folder);
        } catch (final RuntimeException missed) {
            return true;
        }
    }
}
