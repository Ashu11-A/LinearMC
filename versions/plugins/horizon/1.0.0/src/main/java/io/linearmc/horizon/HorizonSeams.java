package io.linearmc.horizon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import net.linear.BufferedLinearRegionFile;
import net.linear.LinearRegionConverter;
import net.linear.LinearRegionFile;
import net.linear.RegionFileFormat;
import net.linear.config.LinearFileConfig;
import net.linear.probe.AbstractRegionFileFactory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * Core construction seams for this leg, registered once from whichever runs
 * first: the pre-plugin startup conversion ({@code DedicatedServerMixin}) or
 * {@code LinearPlugin#onEnable}. Anvil opens go through the
 * {@link HorizonAnvilRegionFile} adapter (composition over vanilla
 * {@code RegionFile}, mirroring the fork legs where {@code RegionFile}
 * implements {@code AbstractRegionFile} directly); Linear opens go straight
 * into the codec; reverse conversion writes real NMS anvil files through
 * {@link AnvilChunkSink}.
 */
public final class HorizonSeams {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private HorizonSeams() {
    }

    /** Idempotent. */
    public static void registerOpeners() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        AbstractRegionFileFactory.linear$setAnvilOpener(
            (info, file, dir, sync) -> new HorizonAnvilRegionFile((RegionStorageInfo) info, file, dir, sync));
        AbstractRegionFileFactory.linear$setLinearOpener(LinearRegionFile::new);
        AbstractRegionFileFactory.linear$setBufferedOpener(BufferedLinearRegionFile::new);
        // Converter opens can run before any world storage exists (startup
        // conversion): the converter RegionOpener mirrors the storage path.
        LinearRegionConverter.linear$setRegionOpener((file, openFolder, openSync, format, level) ->
            AbstractRegionFileFactory.get(file,
                new RegionStorageInfo("converter", Level.OVERWORLD, "chunk"),
                openFolder, openSync, level));
        LinearRegionConverter.linear$setChunkSinkOpener((file, folder, level) -> new AnvilChunkSink(file, folder));
    }

    /** Host {@code new RegionFile} sites: Linear layouts by extension, anything else vanilla. */
    public static RegionFile openRegion(final RegionStorageInfo info, final Path path, final Path folder,
        final boolean sync) throws IOException {
        final String name = path.getFileName().toString();
        final int level = LinearFileConfig.resolveLevelFor(folder.toString());
        if (name.endsWith(RegionFileFormat.B_LINEAR_EXTENSION)) {
            return new HorizonBufferedRegionFile(path, level);
        }
        if (name.endsWith(RegionFileFormat.LINEAR_EXTENSION)) {
            return new HorizonLinearRegionFile(path, level);
        }
        return new RegionFile(info, path, folder, sync);
    }
}
