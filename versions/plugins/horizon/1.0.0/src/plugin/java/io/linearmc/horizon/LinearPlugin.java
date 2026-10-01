package io.linearmc.horizon;

import java.nio.file.Path;

import net.linear.AbstractRegionFileFactory;
import net.linear.LinearFlushCoordinator;
import net.linear.LinearRegionConverter;
import net.linear.LinearRegionFile;
import net.linear.LinearRegionTimings;
import net.linear.RegionFileFormat;
import net.linear.command.JobQueue;
import net.linear.config.LinearFileConfig;
import net.linear.config.LinearPolicy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.bukkit.plugin.java.JavaPlugin;

public class LinearPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // Eager warm-up FIRST: force-load every core/leg class plus the NMS
        // mixin targets on this enable thread, so steady-state region/tick
        // threads never pay class-load, nested-jar read, native-extract, or
        // Mixin/transform costs. Missing classes are skipped, never fatal.
        final net.linear.LinearWarmup.Result warmed = net.linear.LinearWarmup.touch(
            LinearPlugin.class,
            "net.linear.RegionFileFormat",
            "net.linear.ChunkKey",
            "net.linear.AbstractRegionFile",
            "net.linear.AbstractRegionFileFactory",
            "net.linear.LinearStorageProbe",
            "net.linear.LinearFolderNames",
            "net.linear.LinearWarmup",
            "net.linear.LinearRegionFile",
            "net.linear.ZstdChunkCodec",
            "net.linear.LinearDirectStreams",
            "net.linear.LinearEnvelopeCodec",
            "net.linear.LinearWritePath",
            "net.linear.BufferedRegionOutput",
            "net.linear.LinearFlushCoordinator",
            "net.linear.LinearRegionTimings",
            "net.linear.FlushReports",
            "net.linear.FlushEvent",
            "net.linear.LinearRegionConverter",
            "net.linear.config.LinearPolicy",
            "net.linear.config.LinearFileConfig",
            "net.linear.config.LinearFormatOverride",
            "net.linear.command.JobQueue",
            "net.linear.command.ConvertJob",
            "net.linear.command.JobRunner",
            "net.linear.command.LinearCommandParser",
            "net.linear.command.LinearCommandExecutor",
            "net.linear.command.LinearTabCompleter",
            "net.linear.command.LinearStatsView",
            "net.linear.command.LinearStatsCompact",
            "net.linear.command.LinearStatsFormat",
            "net.linear.command.LinearStatsHealth",
            "net.linear.command.LinearStatsStatus",
            "net.linear.command.LinearPermissions",
            "net.linear.command.JobStatusView",
            "net.linear.command.ConvertDirection",
            "net.linear.command.JobState",
            "net.linear.command.QueueOp",
            "net.linear.command.LinearAction",
            "net.linear.command.CommandSender",
            "net.linear.command.StatsProvider",
            "io.linearmc.horizon.HorizonLinearRegionFile",
            "io.linearmc.horizon.ChunkPosCoords",
            "io.linearmc.horizon.AnvilChunkSink",
            "io.linearmc.horizon.LinearCommand",
            "io.linearmc.horizon.LinearStatsCommand",
            "io.linearmc.horizon.LinearFormatPolicy",
            "net.minecraft.world.level.chunk.storage.RegionFileStorage",
            "net.minecraft.world.level.chunk.storage.RegionFile");
        final boolean natives = net.linear.ZstdChunkCodec.warmup();
        getLogger().info("LinearMC: warmed " + warmed.loaded() + " classes"
            + " (" + warmed.skipped() + " skipped) in " + warmed.millis() + "ms"
            + ", natives " + (natives ? "ready" : "lazy"));
        saveDefaultConfig();
        // Single file-holder lives in core: the mixin side reads it back via
        // LinearFileConfig (Horizon's split source sets let plugin see main
        // but never the reverse, so the mixin cannot reference this class).
        LinearFileConfig.setFileValues(
            getConfig().getString("region-format.format", null),
            getConfig().isInt("region-format.linear.compression-level")
                ? getConfig().getInt("region-format.linear.compression-level")
                : null);
        // Wire the core construction seam: anvil opens go through the
        // HorizonAnvilRegionFile adapter (composition over vanilla
        // RegionFile, mirroring the fork legs where RegionFile implements
        // AbstractRegionFile directly). Linear opens straight into codec.
        AbstractRegionFileFactory.linear$setAnvilOpener(
            (info, file, dir, sync) -> new HorizonAnvilRegionFile(
                (RegionStorageInfo) info, file, dir, sync));
        AbstractRegionFileFactory.linear$setLinearOpener(LinearRegionFile::new);
        // Converter opens run before any world storage exists (startup and
        // recreate triggers): the converter RegionOpener mirrors the
        // storage-ctor registration so conversion storage opens resolve here.
        LinearRegionConverter.linear$setRegionOpener((file, openFolder, openSync, format, level) ->
            AbstractRegionFileFactory.get(file,
                new RegionStorageInfo("converter", Level.OVERWORLD, "chunk"),
                openFolder, openSync, level));
        // Reverse seams: raw NBT from the linear source lands in a real NMS
        // anvil file (enveloping is vanilla-owned, see AnvilChunkSink), and a
        // linear source whose folder still has unflushed writes
        // (dirtyDepth > 0) is reported SKIPPED, never queued mid-write.
        LinearRegionConverter.linear$setChunkSinkOpener((file, folder, level) -> new AnvilChunkSink(file, folder));
        LinearRegionConverter.linear$setReverseSkipPredicate(LinearPlugin::reverseSkipDirty);
        // Soak parity (d-load, Oct 2026): forward defers loaded files with
        // the same folder-dirty check as reverse.
        LinearRegionConverter.linear$setForwardSkipPredicate(LinearPlugin::reverseSkipDirty);
        // Resolution is core-owned (LinearFileConfig over LinearPolicy):
        // sysprop wins over config.yml, which wins over the shipped ANVIL
        // default (fail-open). Only "LINEAR" enables Linear; anything else
        // (unset/unknown) falls back to ANVIL, level 1..22 else 6.
        final RegionFileFormat format = LinearFileConfig.resolveFormat();
        final boolean linear = format == RegionFileFormat.LINEAR;
        final int level = LinearFileConfig.resolveLevel();
        getLogger().info("LinearMC: format=" + (linear ? "LINEAR" : "ANVIL") + " level=" + level
                + " (set -Dlinearmc.format=LINEAR to enable)");
        if (!linear) {
            getLogger().warning("LinearMC: format is ANVIL — no .linear files will be written.");
        }
        LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_FORMAT)
            .ifPresent(getLogger()::info);
        LinearFileConfig.overrideLineIfNeeded(LinearPolicy.SYSPROP_LEVEL)
            .ifPresent(getLogger()::info);
        // The /linear queue is in-memory only: name the restart drop on every
        // boot so operators re-issue converts instead of assuming they resume.
        getLogger().info(JobQueue.droppedByRestartLine(0));
        LinearCommand command = new LinearCommand(this);
        this.registerCommand("linear", "Linear region tools (stats, convert, queue)", command);
        this.registerCommand(
            "linearstats", "Shows Linear region timing stats", new LinearStatsCommand(this));
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
